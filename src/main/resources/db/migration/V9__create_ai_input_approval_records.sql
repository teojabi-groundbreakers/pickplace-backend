-- AI 입력 사용 승인은 기존 통계의 기술적 게시와 분리한다. 이 마이그레이션은 승인 데이터를 생성하지 않는다.
CREATE TABLE ai_input_scope_review (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    region_release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    classification_id BIGINT NOT NULL,
    business_type_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_industry_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_industry_name VARCHAR(100) NOT NULL,
    industry_match VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valid_from DATE NOT NULL,
    valid_through DATE NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    evidence TEXT NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    PRIMARY KEY (review_id),
    UNIQUE KEY uq_ai_scope_version (review_version, region_release_id, region_code, classification_id, business_type_code),
    KEY ix_ai_scope_lookup (region_release_id, region_code, classification_id, business_type_code, status),
    CONSTRAINT fk_ai_scope_region FOREIGN KEY (region_release_id, region_code) REFERENCES region_navigation (release_id, region_code),
    CONSTRAINT fk_ai_scope_industry FOREIGN KEY (classification_id, business_type_code) REFERENCES national_industry_code (classification_id, industry_code),
    CONSTRAINT ck_ai_scope_codes CHECK (REGEXP_LIKE(source_region_code, '^[0-9]{8}$', 'c')
        AND REGEXP_LIKE(source_industry_code, '^CS[0-9]{6}$', 'c')),
    CONSTRAINT ck_ai_scope_dates CHECK (valid_from <= valid_through),
    CONSTRAINT ck_ai_scope_industry_match CHECK (industry_match IN ('EXACT','PARTIAL')
        AND CHAR_LENGTH(TRIM(source_industry_name)) > 0),
    CONSTRAINT ck_ai_scope_review CHECK (CHAR_LENGTH(TRIM(review_version)) > 0 AND CHAR_LENGTH(TRIM(evidence)) > 0
        AND ((status = 'APPROVED' AND approved_at IS NOT NULL AND approved_by IS NOT NULL AND CHAR_LENGTH(TRIM(approved_by)) > 0)
            OR (status IN ('PENDING', 'REJECTED') AND approved_at IS NULL AND approved_by IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_input_area_review (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    scope_review_id BIGINT NOT NULL,
    source_dataset VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OA-22160',
    source_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    area_square_meters DECIMAL(20,4) NOT NULL,
    valid_from DATE NOT NULL,
    valid_through DATE NOT NULL,
    source_published_at DATETIME(6) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    boundary_verified BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    evidence TEXT NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    PRIMARY KEY (review_id),
    UNIQUE KEY uq_ai_area_version (scope_review_id, source_version),
    CONSTRAINT fk_ai_area_scope FOREIGN KEY (scope_review_id) REFERENCES ai_input_scope_review (review_id),
    CONSTRAINT ck_ai_area_fields CHECK (area_square_meters > 0 AND valid_from <= valid_through
        AND REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c') AND CHAR_LENGTH(TRIM(source_version)) > 0
        AND source_dataset = 'OA-22160' AND source_url LIKE 'https://data.seoul.go.kr/dataList/OA-22160/%'
        AND source_published_at <= collected_at AND CHAR_LENGTH(TRIM(evidence)) > 0 AND boundary_verified IN (0, 1)),
    CONSTRAINT ck_ai_area_review CHECK ((status = 'APPROVED' AND boundary_verified = TRUE
        AND approved_at IS NOT NULL AND approved_by IS NOT NULL AND CHAR_LENGTH(TRIM(approved_by)) > 0)
        OR (status IN ('PENDING', 'REJECTED') AND approved_at IS NULL AND approved_by IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_input_metric_review (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    scope_review_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    metric_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valid_from DATE NOT NULL,
    valid_through DATE NOT NULL,
    unit VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boundary_verified BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    evidence TEXT NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    PRIMARY KEY (review_id),
    UNIQUE KEY uq_ai_metric_review (scope_review_id, batch_id, metric_kind, valid_from, valid_through),
    CONSTRAINT fk_ai_metric_scope FOREIGN KEY (scope_review_id) REFERENCES ai_input_scope_review (review_id),
    CONSTRAINT fk_ai_metric_batch FOREIGN KEY (batch_id) REFERENCES regional_statistic_batch (batch_id),
    CONSTRAINT ck_ai_metric_kind CHECK ((metric_kind = 'SALES' AND unit = 'KRW')
        OR (metric_kind IN ('STORE_COUNT', 'CLOSING_COUNT') AND unit = 'COUNT')
        OR (metric_kind = 'RESIDENT_POPULATION' AND unit = 'PERSON')),
    CONSTRAINT ck_ai_metric_dates CHECK (valid_from <= valid_through),
    CONSTRAINT ck_ai_metric_review CHECK (CHAR_LENGTH(TRIM(evidence)) > 0 AND boundary_verified IN (0, 1)
        AND ((status = 'APPROVED' AND boundary_verified = TRUE AND approved_at IS NOT NULL AND approved_by IS NOT NULL AND CHAR_LENGTH(TRIM(approved_by)) > 0)
            OR (status IN ('PENDING', 'REJECTED') AND approved_at IS NULL AND approved_by IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
