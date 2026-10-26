-- Reference tables. Seed data is loaded separately.
-- All DATETIME values are supplied by the application in UTC.
CREATE TABLE district (
    district_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    district_name VARCHAR(100) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (district_code),
    CONSTRAINT ck_district_code CHECK (REGEXP_LIKE(district_code, '^[0-9]{5}$', 'c')),
    CONSTRAINT ck_district_name CHECK (CHAR_LENGTH(TRIM(district_name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE administrative_dong (
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_name VARCHAR(100) NOT NULL,
    district_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    official_dong_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    official_dong_name VARCHAR(100) NULL,
    official_code_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    official_created_on DATE NULL,
    official_abolished_on DATE NULL,
    reference_snapshot_on DATE NULL,
    collected_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (dong_code),
    INDEX ix_dong_district_name (district_code, dong_name, dong_code),
    CONSTRAINT fk_dong_district FOREIGN KEY (district_code)
        REFERENCES district (district_code),
    CONSTRAINT ck_dong_code CHECK (REGEXP_LIKE(dong_code, '^[0-9]{8}$', 'c')),
    CONSTRAINT ck_dong_name CHECK (CHAR_LENGTH(TRIM(dong_name)) > 0),
    CONSTRAINT ck_dong_official_code CHECK (
        official_dong_code IS NULL OR
        REGEXP_LIKE(official_dong_code, '^[0-9]{10}$', 'c')
    ),
    CONSTRAINT ck_dong_official_status CHECK (
        official_code_status IN ('CURRENT', 'LEGACY', 'UNVERIFIED')
    ),
    CONSTRAINT ck_dong_official_reference CHECK (
        (official_code_status = 'UNVERIFIED'
            AND official_dong_code IS NULL AND official_dong_name IS NULL
            AND official_created_on IS NULL AND official_abolished_on IS NULL
            AND reference_snapshot_on IS NULL)
        OR
        (official_code_status IN ('CURRENT', 'LEGACY')
            AND official_dong_code IS NOT NULL AND official_dong_name IS NOT NULL
            AND CHAR_LENGTH(TRIM(official_dong_name)) > 0
            AND reference_snapshot_on IS NOT NULL
            AND (official_code_status <> 'CURRENT' OR official_abolished_on IS NULL)
            AND (official_code_status <> 'LEGACY' OR official_abolished_on IS NOT NULL))
    ),
    CONSTRAINT ck_dong_official_dates CHECK (
        official_created_on IS NULL OR official_abolished_on IS NULL
        OR official_created_on <= official_abolished_on
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE business_type (
    business_type_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_business_type_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    is_supported BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INT NOT NULL DEFAULT 0,
    data_version VARCHAR(100) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (business_type_code),
    CONSTRAINT ck_business_type_code CHECK (
        REGEXP_LIKE(business_type_code, '^CS[0-9]{6}$', 'c')
    ),
    CONSTRAINT ck_business_type_source_name CHECK (
        CHAR_LENGTH(TRIM(source_business_type_name)) > 0
    ),
    CONSTRAINT ck_business_type_display_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT ck_business_type_supported CHECK (is_supported IN (0, 1)),
    CONSTRAINT ck_business_type_order CHECK (display_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
