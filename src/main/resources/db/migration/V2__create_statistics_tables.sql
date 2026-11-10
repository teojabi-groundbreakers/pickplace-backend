-- Statistics are versioned by import batch; only published batches may be served.
-- All DATETIME values are supplied by the application in UTC.
CREATE TABLE data_import_batch (
    batch_id BIGINT NOT NULL AUTO_INCREMENT,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    source_url TEXT NOT NULL,
    source_checksum VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parser_version VARCHAR(100) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RUNNING',
    collected_at DATETIME(6) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    source_row_count BIGINT NULL,
    accepted_row_count BIGINT NULL,
    rejected_row_count BIGINT NULL,
    duplicate_row_count BIGINT NULL,
    validation_report JSON NULL,
    failure_code VARCHAR(64) NULL,
    PRIMARY KEY (batch_id),
    UNIQUE KEY uk_import_dataset_quarter (batch_id, dataset_code, quarter_code),
    UNIQUE KEY uk_import_publishable (batch_id, dataset_code, quarter_code, status),
    INDEX ix_import_dataset_quarter_status (dataset_code, quarter_code, status),
    INDEX ix_import_fingerprint (dataset_code, quarter_code, source_checksum, parser_version),
    CONSTRAINT ck_import_dataset CHECK (
        dataset_code IN ('RESIDENT', 'WORKER', 'FACILITY', 'STORE', 'SALES', 'FLOW')
    ),
    CONSTRAINT ck_import_quarter CHECK (REGEXP_LIKE(quarter_code, '^[0-9]{4}[1-4]$', 'c')),
    CONSTRAINT ck_import_source CHECK (
        CHAR_LENGTH(TRIM(source_id)) > 0 AND CHAR_LENGTH(TRIM(source_url)) > 0
        AND REGEXP_LIKE(source_checksum, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_import_versions CHECK (
        CHAR_LENGTH(TRIM(parser_version)) > 0 AND CHAR_LENGTH(TRIM(data_version)) > 0
    ),
    CONSTRAINT ck_import_counts CHECK (
        (source_row_count IS NULL AND accepted_row_count IS NULL
            AND rejected_row_count IS NULL AND duplicate_row_count IS NULL)
        OR
        (source_row_count IS NOT NULL AND accepted_row_count IS NOT NULL
            AND rejected_row_count IS NOT NULL AND duplicate_row_count IS NOT NULL
            AND source_row_count >= 0 AND accepted_row_count >= 0
            AND rejected_row_count >= 0 AND duplicate_row_count >= 0
            AND source_row_count = accepted_row_count + rejected_row_count + duplicate_row_count)
    ),
    CONSTRAINT ck_import_state CHECK (
        (status = 'RUNNING' AND finished_at IS NULL AND failure_code IS NULL)
        OR
        (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND collected_at IS NOT NULL
            AND source_row_count IS NOT NULL AND validation_report IS NOT NULL AND failure_code IS NULL)
        OR
        (status = 'FAILED' AND finished_at IS NOT NULL
            AND failure_code IS NOT NULL AND CHAR_LENGTH(TRIM(failure_code)) > 0)
    ),
    CONSTRAINT ck_import_dates CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dataset_publication (
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id BIGINT NOT NULL,
    batch_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SUCCEEDED',
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (dataset_code, quarter_code),
    CONSTRAINT ck_publication_dataset CHECK (dataset_code IN ('RESIDENT', 'WORKER', 'FACILITY', 'STORE')),
    CONSTRAINT ck_publication_status CHECK (batch_status = 'SUCCEEDED'),
    CONSTRAINT fk_publication_batch FOREIGN KEY (batch_id, dataset_code, quarter_code, batch_status)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE data_quality_issue (
    issue_id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    source_row_number BIGINT NOT NULL,
    raw_dong_code VARCHAR(100) NULL,
    raw_business_type_code VARCHAR(100) NULL,
    field_name VARCHAR(100) NOT NULL,
    issue_code VARCHAR(64) NOT NULL,
    severity VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_value JSON NULL,
    PRIMARY KEY (issue_id),
    INDEX ix_quality_issue_lookup (batch_id, raw_dong_code, raw_business_type_code),
    CONSTRAINT fk_quality_issue_batch FOREIGN KEY (batch_id) REFERENCES data_import_batch (batch_id),
    CONSTRAINT ck_quality_issue_row CHECK (source_row_number >= 1),
    CONSTRAINT ck_quality_issue_fields CHECK (
        CHAR_LENGTH(TRIM(field_name)) > 0 AND CHAR_LENGTH(TRIM(issue_code)) > 0
    ),
    CONSTRAINT ck_quality_issue_severity CHECK (severity IN ('WARNING', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dong_resident_quarter (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RESIDENT',
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    resident_population BIGINT NULL,
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_payload JSON NOT NULL,
    PRIMARY KEY (batch_id, dong_code),
    INDEX ix_resident_lookup (dong_code, quarter_code, batch_id),
    CONSTRAINT fk_resident_batch FOREIGN KEY (batch_id, dataset_code, quarter_code)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code),
    CONSTRAINT fk_resident_dong FOREIGN KEY (dong_code) REFERENCES administrative_dong (dong_code),
    CONSTRAINT ck_resident_dataset CHECK (dataset_code = 'RESIDENT'),
    CONSTRAINT ck_resident_region_version CHECK (CHAR_LENGTH(TRIM(region_version)) > 0),
    CONSTRAINT ck_resident_value CHECK (resident_population IS NULL OR resident_population >= 0 AND resident_population <= 9007199254740991),
    CONSTRAINT ck_resident_row_status CHECK (
        (row_status = 'AVAILABLE' AND resident_population IS NOT NULL AND reason_code IS NULL)
        OR
        (row_status = 'NO_DATA' AND resident_population IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'SOURCE_VALUE_MISSING')
        OR
        (row_status = 'INVALID' AND resident_population IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'QUALITY_CHECK_FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dong_worker_quarter (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WORKER',
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    worker_population BIGINT NULL,
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_payload JSON NOT NULL,
    PRIMARY KEY (batch_id, dong_code),
    INDEX ix_worker_lookup (dong_code, quarter_code, batch_id),
    CONSTRAINT fk_worker_batch FOREIGN KEY (batch_id, dataset_code, quarter_code)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code),
    CONSTRAINT fk_worker_dong FOREIGN KEY (dong_code) REFERENCES administrative_dong (dong_code),
    CONSTRAINT ck_worker_dataset CHECK (dataset_code = 'WORKER'),
    CONSTRAINT ck_worker_region_version CHECK (CHAR_LENGTH(TRIM(region_version)) > 0),
    CONSTRAINT ck_worker_value CHECK (worker_population IS NULL OR worker_population >= 0 AND worker_population <= 9007199254740991),
    CONSTRAINT ck_worker_row_status CHECK (
        (row_status = 'AVAILABLE' AND worker_population IS NOT NULL AND reason_code IS NULL)
        OR
        (row_status = 'NO_DATA' AND worker_population IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'SOURCE_VALUE_MISSING')
        OR
        (row_status = 'INVALID' AND worker_population IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'QUALITY_CHECK_FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dong_facility_quarter (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'FACILITY',
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    total_facility_count BIGINT NULL,
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_payload JSON NOT NULL,
    PRIMARY KEY (batch_id, dong_code),
    INDEX ix_facility_lookup (dong_code, quarter_code, batch_id),
    CONSTRAINT fk_facility_batch FOREIGN KEY (batch_id, dataset_code, quarter_code)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code),
    CONSTRAINT fk_facility_dong FOREIGN KEY (dong_code) REFERENCES administrative_dong (dong_code),
    CONSTRAINT ck_facility_dataset CHECK (dataset_code = 'FACILITY'),
    CONSTRAINT ck_facility_region_version CHECK (CHAR_LENGTH(TRIM(region_version)) > 0),
    CONSTRAINT ck_facility_value CHECK (total_facility_count IS NULL OR total_facility_count >= 0 AND total_facility_count <= 9007199254740991),
    CONSTRAINT ck_facility_row_status CHECK (
        (row_status = 'AVAILABLE' AND total_facility_count IS NOT NULL AND reason_code IS NULL)
        OR
        (row_status = 'NO_DATA' AND total_facility_count IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'SOURCE_VALUE_MISSING')
        OR
        (row_status = 'INVALID' AND total_facility_count IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'QUALITY_CHECK_FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dong_store_quarter (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STORE',
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_type_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    total_store_count BIGINT NULL,
    general_store_count BIGINT NULL,
    franchise_store_count BIGINT NULL,
    opened_store_count BIGINT NULL,
    closed_store_count BIGINT NULL,
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_payload JSON NOT NULL,
    PRIMARY KEY (batch_id, dong_code, business_type_code),
    INDEX ix_store_lookup (dong_code, business_type_code, quarter_code, batch_id),
    CONSTRAINT fk_store_batch FOREIGN KEY (batch_id, dataset_code, quarter_code)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code),
    CONSTRAINT fk_store_dong FOREIGN KEY (dong_code) REFERENCES administrative_dong (dong_code),
    CONSTRAINT fk_store_business_type FOREIGN KEY (business_type_code) REFERENCES business_type (business_type_code),
    CONSTRAINT ck_store_dataset CHECK (dataset_code = 'STORE'),
    CONSTRAINT ck_store_region_version CHECK (CHAR_LENGTH(TRIM(region_version)) > 0),
    CONSTRAINT ck_store_value CHECK (total_store_count IS NULL OR total_store_count >= 0 AND total_store_count <= 9007199254740991),
    CONSTRAINT ck_store_general_store_count CHECK (general_store_count IS NULL OR general_store_count BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_store_franchise_store_count CHECK (franchise_store_count IS NULL OR franchise_store_count BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_store_opened_store_count CHECK (opened_store_count IS NULL OR opened_store_count BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_store_closed_store_count CHECK (closed_store_count IS NULL OR closed_store_count BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_store_total CHECK (
        total_store_count IS NULL OR general_store_count IS NULL OR franchise_store_count IS NULL
        OR total_store_count = general_store_count + franchise_store_count
    ),
    CONSTRAINT ck_store_row_status CHECK (
        (row_status = 'AVAILABLE' AND total_store_count IS NOT NULL AND reason_code IS NULL)
        OR
        (row_status = 'NO_DATA' AND total_store_count IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'SOURCE_VALUE_MISSING')
        OR
        (row_status = 'INVALID' AND total_store_count IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'QUALITY_CHECK_FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dong_sales_quarter (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SALES',
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_type_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    source_sales_amount DECIMAL(20, 0) NULL,
    source_sales_count BIGINT NULL,
    aggregation_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNVERIFIED',
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_payload JSON NOT NULL,
    PRIMARY KEY (batch_id, dong_code, business_type_code),
    INDEX ix_sales_lookup (dong_code, business_type_code, quarter_code, batch_id),
    CONSTRAINT fk_sales_batch FOREIGN KEY (batch_id, dataset_code, quarter_code)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code),
    CONSTRAINT fk_sales_dong FOREIGN KEY (dong_code) REFERENCES administrative_dong (dong_code),
    CONSTRAINT fk_sales_business_type FOREIGN KEY (business_type_code) REFERENCES business_type (business_type_code),
    CONSTRAINT ck_sales_dataset CHECK (dataset_code = 'SALES'),
    CONSTRAINT ck_sales_region_version CHECK (CHAR_LENGTH(TRIM(region_version)) > 0),
    CONSTRAINT ck_sales_value CHECK (source_sales_amount IS NULL OR source_sales_amount >= 0),
    CONSTRAINT ck_sales_source_sales_count CHECK (source_sales_count IS NULL OR source_sales_count BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sales_aggregation CHECK (aggregation_status = 'UNVERIFIED'),
    CONSTRAINT ck_sales_row_status CHECK (
        (row_status = 'AVAILABLE' AND source_sales_amount IS NOT NULL AND reason_code IS NULL)
        OR
        (row_status = 'NO_DATA' AND source_sales_amount IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'SOURCE_VALUE_MISSING')
        OR
        (row_status = 'INVALID' AND source_sales_amount IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'QUALITY_CHECK_FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dong_flow_quarter (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'FLOW',
    quarter_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dong_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_version VARCHAR(100) NOT NULL,
    source_flow_population BIGINT NULL,
    aggregation_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNVERIFIED',
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_payload JSON NOT NULL,
    PRIMARY KEY (batch_id, dong_code),
    INDEX ix_flow_lookup (dong_code, quarter_code, batch_id),
    CONSTRAINT fk_flow_batch FOREIGN KEY (batch_id, dataset_code, quarter_code)
        REFERENCES data_import_batch (batch_id, dataset_code, quarter_code),
    CONSTRAINT fk_flow_dong FOREIGN KEY (dong_code) REFERENCES administrative_dong (dong_code),
    CONSTRAINT ck_flow_dataset CHECK (dataset_code = 'FLOW'),
    CONSTRAINT ck_flow_region_version CHECK (CHAR_LENGTH(TRIM(region_version)) > 0),
    CONSTRAINT ck_flow_value CHECK (source_flow_population IS NULL OR source_flow_population >= 0),
    CONSTRAINT ck_flow_aggregation CHECK (aggregation_status = 'UNVERIFIED'),
    CONSTRAINT ck_flow_row_status CHECK (
        (row_status = 'AVAILABLE' AND source_flow_population IS NOT NULL AND reason_code IS NULL)
        OR
        (row_status = 'NO_DATA' AND source_flow_population IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'SOURCE_VALUE_MISSING')
        OR
        (row_status = 'INVALID' AND source_flow_population IS NULL
            AND reason_code IS NOT NULL AND reason_code = 'QUALITY_CHECK_FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
