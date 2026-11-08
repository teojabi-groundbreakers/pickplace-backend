-- 기존 서울 참조와 대응표를 보존하면서 전국 원천을 추가한다.
ALTER TABLE region_stat_mapping DROP FOREIGN KEY fk_region_mapping_source;

ALTER TABLE source_region_reference
    DROP CHECK ck_source_region_system,
    DROP CHECK ck_source_region_codes,
    DROP CHECK ck_source_region_text,
    MODIFY source_region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY source_district_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY source_district_name VARCHAR(100) NULL,
    ADD CONSTRAINT ck_source_region_system CHECK (
        source_system IN ('SEOUL_COMMERCIAL', 'MOIS_RESIDENT', 'SEMAS_STORE')
    ),
    ADD CONSTRAINT ck_source_region_codes CHECK (
        (source_system = 'MOIS_RESIDENT'
            AND CHAR_LENGTH(source_region_code) = 10
            AND REGEXP_LIKE(source_region_code, '^[0-9]{10}$', 'c'))
        OR (source_system IN ('SEOUL_COMMERCIAL', 'SEMAS_STORE')
            AND CHAR_LENGTH(source_region_code) = 8
            AND REGEXP_LIKE(source_region_code, '^[0-9]{8}$', 'c'))
    ),
    ADD CONSTRAINT ck_source_region_parent CHECK (
        (source_system = 'MOIS_RESIDENT'
            AND (source_district_code IS NULL OR (CHAR_LENGTH(source_district_code) = 10
                AND REGEXP_LIKE(source_district_code, '^[0-9]{10}$', 'c'))))
        OR (source_system IN ('SEOUL_COMMERCIAL', 'SEMAS_STORE')
            AND source_district_code IS NOT NULL AND CHAR_LENGTH(source_district_code) = 5
            AND REGEXP_LIKE(source_district_code, '^[0-9]{5}$', 'c'))
    ),
    ADD CONSTRAINT ck_source_region_text CHECK (
        CHAR_LENGTH(TRIM(source_region_version)) > 0 AND CHAR_LENGTH(TRIM(source_region_name)) > 0
        AND CHAR_LENGTH(TRIM(source_url)) > 0
        AND (source_system <> 'SEOUL_COMMERCIAL'
            OR (source_district_name IS NOT NULL AND CHAR_LENGTH(TRIM(source_district_name)) > 0))
    );

ALTER TABLE region_stat_mapping
    MODIFY source_region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT fk_region_mapping_source FOREIGN KEY (source_system, source_region_code, source_region_version)
        REFERENCES source_region_reference (source_system, source_region_code, source_region_version);

CREATE TABLE national_industry_release (
    classification_id BIGINT NOT NULL AUTO_INCREMENT,
    code_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SEMAS_BUSINESS',
    version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_url TEXT NOT NULL,
    source_sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_snapshot_on DATE NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LOADING',
    validation_report JSON NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    PRIMARY KEY (classification_id),
    UNIQUE KEY uq_national_industry_version (version),
    UNIQUE KEY uq_national_industry_status (classification_id, status),
    CONSTRAINT ck_national_industry_system CHECK (code_system = 'SEMAS_BUSINESS'),
    CONSTRAINT ck_national_industry_metadata CHECK (
        CHAR_LENGTH(TRIM(version)) > 0 AND CHAR_LENGTH(TRIM(source_url)) > 0
        AND CHAR_LENGTH(source_sha256) = 64 AND REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_national_industry_status CHECK (status IN ('LOADING', 'VALIDATING', 'READY', 'APPROVED', 'FAILED')),
    CONSTRAINT ck_national_industry_approval CHECK (
        (status = 'APPROVED' AND validation_report IS NOT NULL AND approved_at IS NOT NULL
            AND approved_at >= collected_at AND approved_by IS NOT NULL AND CHAR_LENGTH(TRIM(approved_by)) > 0)
        OR (status <> 'APPROVED' AND approved_at IS NULL AND approved_by IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE national_industry_code (
    classification_id BIGINT NOT NULL,
    industry_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    industry_name VARCHAR(100) NOT NULL,
    industry_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parent_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    display_name VARCHAR(100) NOT NULL,
    is_supported BOOLEAN NOT NULL DEFAULT FALSE,
    support_release_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    display_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (classification_id, industry_code),
    UNIQUE KEY uq_national_industry_level (classification_id, industry_code, industry_level),
    INDEX ix_national_industry_supported (classification_id, is_supported, display_order, industry_code),
    CONSTRAINT fk_national_industry_release FOREIGN KEY (classification_id)
        REFERENCES national_industry_release (classification_id),
    CONSTRAINT fk_national_industry_parent FOREIGN KEY (classification_id, parent_code, parent_level)
        REFERENCES national_industry_code (classification_id, industry_code, industry_level),
    CONSTRAINT fk_national_industry_support FOREIGN KEY (classification_id, support_release_status)
        REFERENCES national_industry_release (classification_id, status),
    CONSTRAINT ck_national_industry_code CHECK (
        CHAR_LENGTH(industry_code) > 0 AND NOT REGEXP_LIKE(industry_code, '[^A-Z0-9]', 'c')
    ),
    CONSTRAINT ck_national_industry_text CHECK (
        CHAR_LENGTH(TRIM(industry_name)) > 0 AND CHAR_LENGTH(TRIM(display_name)) > 0 AND display_order >= 0
    ),
    -- 부모 단계까지 FK로 묶어 같은 분류본의 대→중→소 관계만 허용한다.
    CONSTRAINT ck_national_industry_hierarchy CHECK (
        (industry_level = 'LARGE' AND parent_code IS NULL AND parent_level IS NULL)
        OR (industry_level = 'MIDDLE' AND parent_code IS NOT NULL AND parent_level IS NOT NULL
            AND parent_level = 'LARGE' AND parent_code <> industry_code)
        OR (industry_level = 'SMALL' AND parent_code IS NOT NULL AND parent_level IS NOT NULL
            AND parent_level = 'MIDDLE' AND parent_code <> industry_code)
    ),
    CONSTRAINT ck_national_industry_support CHECK (
        is_supported IN (0, 1)
        AND ((is_supported = FALSE AND support_release_status IS NULL)
            OR (is_supported = TRUE AND industry_level = 'SMALL'
                AND support_release_status IS NOT NULL AND support_release_status = 'APPROVED'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
