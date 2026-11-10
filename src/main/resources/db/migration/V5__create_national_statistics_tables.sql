-- Nationwide monthly population and store snapshots are separate from the Seoul V2 datasets.
-- UTC timestamps; immutable completed data and publication workflow are enforced by the T14 writer.
CREATE TABLE national_import_batch (
    batch_id BIGINT NOT NULL AUTO_INCREMENT,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_period_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_period_key VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_reference_on DATE NOT NULL,
    source_label VARCHAR(200) NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_release_id BIGINT NOT NULL,
    mapping_release_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPROVED',
    classification_id BIGINT NULL,
    classification_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    parser_version VARCHAR(100) NOT NULL,
    transform_version VARCHAR(100) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RUNNING',
    active_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status <> 'FAILED' THEN fingerprint ELSE NULL END) STORED,
    collected_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    source_row_count BIGINT NULL,
    accepted_row_count BIGINT NULL,
    held_row_count BIGINT NULL,
    rejected_row_count BIGINT NULL,
    duplicate_row_count BIGINT NULL,
    validation_report JSON NULL,
    failure_code VARCHAR(100) NULL,
    PRIMARY KEY (batch_id),
    UNIQUE KEY uq_nbatch_attempt (dataset_code, fingerprint, attempt_no),
    UNIQUE KEY uq_nbatch_active (dataset_code, active_fingerprint),
    UNIQUE KEY uq_nbatch_scope (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version),
    UNIQUE KEY uq_nbatch_classification (batch_id, classification_id),
    UNIQUE KEY uq_nbatch_publishable (batch_id, dataset_code, source_period_key, mapping_release_id, status),
    INDEX ix_nbatch_period (dataset_code, source_period_key, status),
    CONSTRAINT fk_nbatch_region FOREIGN KEY (mapping_release_id, mapping_release_status) REFERENCES region_release (release_id, status),
    CONSTRAINT fk_nbatch_classification FOREIGN KEY (classification_id, classification_status)
        REFERENCES national_industry_release (classification_id, status),
    CONSTRAINT ck_nbatch_source CHECK (
        (dataset_code = 'POPULATION_MONTH' AND source_system = 'MOIS_RESIDENT' AND source_period_type = 'MONTH'
            AND classification_id IS NULL AND classification_status IS NULL)
        OR (dataset_code = 'STORE_SNAPSHOT' AND source_system = 'SEMAS_STORE' AND source_period_type = 'SNAPSHOT'
            AND classification_id IS NOT NULL AND classification_status IS NOT NULL AND classification_status = 'APPROVED')
    ),
    CONSTRAINT ck_nbatch_period CHECK (
        REGEXP_LIKE(source_period_key, '^[1-9][0-9]{3}(0[1-9]|1[0-2])$', 'c')
        AND source_period_key = DATE_FORMAT(source_reference_on, '%Y%m')
        AND (dataset_code <> 'POPULATION_MONTH' OR source_reference_on = LAST_DAY(source_reference_on))
    ),
    CONSTRAINT ck_nbatch_metadata CHECK (
        mapping_release_status = 'APPROVED' AND attempt_no > 0
        AND CHAR_LENGTH(fingerprint) = 64 AND REGEXP_LIKE(fingerprint, '^[0-9a-f]{64}$', 'c')
        AND CHAR_LENGTH(TRIM(source_label)) > 0 AND CHAR_LENGTH(TRIM(source_region_version)) > 0
        AND CHAR_LENGTH(TRIM(parser_version)) > 0 AND CHAR_LENGTH(TRIM(transform_version)) > 0
        AND CHAR_LENGTH(TRIM(data_version)) > 0 AND CHAR_LENGTH(TRIM(policy_version)) > 0
    ),
    CONSTRAINT ck_nbatch_counts CHECK (
        (source_row_count IS NULL AND accepted_row_count IS NULL AND held_row_count IS NULL
            AND rejected_row_count IS NULL AND duplicate_row_count IS NULL)
        OR (source_row_count IS NOT NULL AND accepted_row_count IS NOT NULL AND held_row_count IS NOT NULL
            AND rejected_row_count IS NOT NULL AND duplicate_row_count IS NOT NULL
            AND source_row_count >= 0 AND accepted_row_count >= 0 AND held_row_count >= 0
            AND rejected_row_count >= 0 AND duplicate_row_count >= 0
            AND source_row_count = accepted_row_count + held_row_count + rejected_row_count + duplicate_row_count)
    ),
    CONSTRAINT ck_nbatch_state CHECK (
        (status IN ('RUNNING', 'VALIDATING') AND finished_at IS NULL AND failure_code IS NULL)
        OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND source_row_count IS NOT NULL
            AND validation_report IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL AND CHAR_LENGTH(TRIM(failure_code)) > 0)
    ),
    CONSTRAINT ck_nbatch_dates CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE national_source_artifact (
    batch_id BIGINT NOT NULL,
    artifact_no INT NOT NULL,
    artifact_kind VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_artifact_no INT NULL,
    source_url TEXT NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    member_path VARCHAR(500) NULL,
    sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_count BIGINT NOT NULL,
    crc32 VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    encoding VARCHAR(40) NULL,
    column_names JSON NULL,
    source_row_count BIGINT NULL,
    collected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (batch_id, artifact_no),
    CONSTRAINT fk_nartifact_batch FOREIGN KEY (batch_id) REFERENCES national_import_batch (batch_id),
    CONSTRAINT fk_nartifact_parent FOREIGN KEY (batch_id, parent_artifact_no) REFERENCES national_source_artifact (batch_id, artifact_no),
    CONSTRAINT ck_nartifact_identity CHECK (
        artifact_no > 0 AND CHAR_LENGTH(TRIM(source_url)) > 0 AND CHAR_LENGTH(TRIM(storage_key)) > 0
        AND CHAR_LENGTH(sha256) = 64 AND REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')
        AND byte_count > 0 AND (source_row_count IS NULL OR source_row_count >= 0)
    ),
    CONSTRAINT ck_nartifact_kind CHECK (
        (artifact_kind = 'ZIP' AND parent_artifact_no IS NULL AND member_path IS NULL)
        OR (artifact_kind = 'CSV' AND parent_artifact_no IS NULL AND member_path IS NULL AND encoding IS NOT NULL
            AND CHAR_LENGTH(TRIM(encoding)) > 0 AND column_names IS NOT NULL AND JSON_TYPE(column_names) = 'ARRAY')
        OR (artifact_kind = 'ZIP_MEMBER' AND parent_artifact_no IS NOT NULL AND parent_artifact_no <> artifact_no
            AND member_path IS NOT NULL AND CHAR_LENGTH(TRIM(member_path)) > 0 AND encoding IS NOT NULL
            AND CHAR_LENGTH(TRIM(encoding)) > 0 AND column_names IS NOT NULL AND JSON_TYPE(column_names) = 'ARRAY'
            AND crc32 IS NOT NULL AND CHAR_LENGTH(crc32) = 8 AND REGEXP_LIKE(crc32, '^[0-9a-f]{8}$', 'c'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resident_population_month (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'POPULATION_MONTH',
    reference_month VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_on DATE NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MOIS_RESIDENT',
    source_region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_release_id BIGINT NOT NULL,
    total_population BIGINT NULL,
    male_population BIGINT NULL,
    female_population BIGINT NULL,
    age_sex_counts JSON NULL,
    row_status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(100) NULL,
    temporal_region_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_artifact_no INT NOT NULL,
    source_row_number BIGINT NOT NULL,
    PRIMARY KEY (batch_id, region_code),
    UNIQUE KEY uq_npopulation_source (batch_id, source_region_code),
    UNIQUE KEY uq_npopulation_row (batch_id, source_artifact_no, source_row_number),
    CONSTRAINT fk_npopulation_batch FOREIGN KEY (batch_id, dataset_code, source_system, reference_month, mapping_release_id, source_region_version)
        REFERENCES national_import_batch (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version),
    CONSTRAINT fk_npopulation_region FOREIGN KEY (region_code) REFERENCES service_region (region_code),
    CONSTRAINT fk_npopulation_mapping FOREIGN KEY (mapping_release_id, source_system, source_region_code, source_region_version, region_code)
        REFERENCES region_stat_mapping (release_id, source_system, source_region_code, source_region_version, region_code),
    CONSTRAINT fk_npopulation_artifact FOREIGN KEY (batch_id, source_artifact_no) REFERENCES national_source_artifact (batch_id, artifact_no),
    CONSTRAINT ck_npopulation_identity CHECK (
        dataset_code = 'POPULATION_MONTH' AND source_system = 'MOIS_RESIDENT' AND region_code = source_region_code
        AND reference_month = DATE_FORMAT(reference_on, '%Y%m') AND reference_on = LAST_DAY(reference_on) AND source_row_number > 0
    ),
    CONSTRAINT ck_npopulation_values CHECK (
        (total_population IS NULL OR total_population BETWEEN 0 AND 9007199254740991)
        AND (male_population IS NULL OR male_population BETWEEN 0 AND 9007199254740991)
        AND (female_population IS NULL OR female_population BETWEEN 0 AND 9007199254740991)
    ),
    CONSTRAINT ck_npopulation_state CHECK (
        (row_status = 'AVAILABLE' AND reason_code IS NULL AND total_population IS NOT NULL
            AND male_population IS NOT NULL AND female_population IS NOT NULL
            AND total_population = male_population + female_population AND age_sex_counts IS NOT NULL
            AND JSON_TYPE(age_sex_counts) = 'OBJECT'
            AND JSON_CONTAINS_PATH(age_sex_counts, 'all', '$.male', '$.female') = 1
            AND JSON_TYPE(JSON_EXTRACT(age_sex_counts, '$.male')) = 'ARRAY'
            AND JSON_TYPE(JSON_EXTRACT(age_sex_counts, '$.female')) = 'ARRAY'
            AND JSON_LENGTH(age_sex_counts, '$.male') = 111 AND JSON_LENGTH(age_sex_counts, '$.female') = 111)
        OR (row_status IN ('NO_DATA', 'INVALID', 'SUPPRESSED') AND reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0
            AND total_population IS NULL AND male_population IS NULL AND female_population IS NULL AND age_sex_counts IS NULL)
    ),
    CONSTRAINT ck_npopulation_temporal CHECK (temporal_region_status IN ('VALID', 'PENDING', 'BOUNDARY_MISMATCH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_snapshot_entry (
    batch_id BIGINT NOT NULL,
    source_store_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STORE_SNAPSHOT',
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SEMAS_STORE',
    source_period_key VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_dong_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    mapping_release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    classification_id BIGINT NOT NULL,
    industry_small_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    industry_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SMALL',
    raw_industry_large VARCHAR(100) NULL,
    raw_industry_middle VARCHAR(100) NULL,
    raw_industry_small VARCHAR(100) NULL,
    store_name VARCHAR(300) NULL,
    road_address VARCHAR(500) NULL,
    longitude DECIMAL(12,8) NULL,
    latitude DECIMAL(11,8) NULL,
    location_quality_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(100) NULL,
    temporal_region_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_artifact_no INT NOT NULL,
    source_row_number BIGINT NOT NULL,
    PRIMARY KEY (batch_id, source_store_id),
    UNIQUE KEY uq_nstore_source_row (batch_id, source_artifact_no, source_row_number),
    INDEX ix_nstore_aggregate (batch_id, region_code, classification_id, industry_small_code),
    CONSTRAINT fk_nstore_batch FOREIGN KEY (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version)
        REFERENCES national_import_batch (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version),
    CONSTRAINT fk_nstore_source FOREIGN KEY (source_system, source_region_code, source_region_version)
        REFERENCES source_region_reference (source_system, source_region_code, source_region_version),
    CONSTRAINT fk_nstore_region FOREIGN KEY (region_code) REFERENCES service_region (region_code),
    CONSTRAINT fk_nstore_mapping FOREIGN KEY (mapping_release_id, source_system, source_region_code, source_region_version, region_code)
        REFERENCES region_stat_mapping (release_id, source_system, source_region_code, source_region_version, region_code),
    CONSTRAINT fk_nstore_batch_class FOREIGN KEY (batch_id, classification_id) REFERENCES national_import_batch (batch_id, classification_id),
    CONSTRAINT fk_nstore_industry FOREIGN KEY (classification_id, industry_small_code, industry_level)
        REFERENCES national_industry_code (classification_id, industry_code, industry_level),
    CONSTRAINT fk_nstore_artifact FOREIGN KEY (batch_id, source_artifact_no) REFERENCES national_source_artifact (batch_id, artifact_no),
    CONSTRAINT ck_nstore_identity CHECK (
        dataset_code = 'STORE_SNAPSHOT' AND source_system = 'SEMAS_STORE' AND industry_level = 'SMALL'
        AND CHAR_LENGTH(TRIM(source_store_id)) > 0 AND source_row_number > 0
        AND CHAR_LENGTH(source_region_code) = 8 AND REGEXP_LIKE(source_region_code, '^[0-9]{8}$', 'c')
        AND (legal_dong_code IS NULL OR (CHAR_LENGTH(legal_dong_code) = 10 AND REGEXP_LIKE(legal_dong_code, '^[0-9]{10}$', 'c')))
    ),
    CONSTRAINT ck_nstore_state CHECK (
        (row_status = 'ACCEPTED' AND region_code IS NOT NULL AND industry_small_code IS NOT NULL
            AND temporal_region_status = 'VALID' AND reason_code IS NULL)
        OR (row_status IN ('HOLD', 'REJECTED') AND reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0)
    ),
    CONSTRAINT ck_nstore_temporal CHECK (temporal_region_status IN ('VALID', 'PENDING', 'BOUNDARY_MISMATCH')),
    CONSTRAINT ck_nstore_location CHECK (
        (location_quality_status = 'VALID' AND longitude IS NOT NULL AND latitude IS NOT NULL
            AND longitude BETWEEN -180 AND 180 AND latitude BETWEEN -90 AND 90)
        OR (location_quality_status IN ('MISSING', 'INVALID') AND longitude IS NULL AND latitude IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_region_coverage (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STORE_SNAPSHOT',
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SEMAS_STORE',
    source_period_key VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_row_count BIGINT NOT NULL,
    accepted_row_count BIGINT NOT NULL,
    held_row_count BIGINT NOT NULL,
    rejected_row_count BIGINT NOT NULL,
    duplicate_row_count BIGINT NOT NULL,
    industry_distribution JSON NOT NULL,
    temporal_region_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    coverage_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(100) NULL,
    PRIMARY KEY (batch_id, source_region_code),
    INDEX ix_ncoverage_target (batch_id, region_code),
    CONSTRAINT fk_ncoverage_batch FOREIGN KEY (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version)
        REFERENCES national_import_batch (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version),
    CONSTRAINT fk_ncoverage_source FOREIGN KEY (source_system, source_region_code, source_region_version)
        REFERENCES source_region_reference (source_system, source_region_code, source_region_version),
    CONSTRAINT fk_ncoverage_mapping FOREIGN KEY (mapping_release_id, source_system, source_region_code, source_region_version, region_code)
        REFERENCES region_stat_mapping (release_id, source_system, source_region_code, source_region_version, region_code),
    CONSTRAINT ck_ncoverage_counts CHECK (
        source_row_count > 0 AND accepted_row_count >= 0 AND held_row_count >= 0 AND rejected_row_count >= 0 AND duplicate_row_count >= 0
        AND source_row_count = accepted_row_count + held_row_count + rejected_row_count + duplicate_row_count
        AND JSON_TYPE(industry_distribution) = 'OBJECT'
    ),
    CONSTRAINT ck_ncoverage_state CHECK (
        dataset_code = 'STORE_SNAPSHOT' AND source_system = 'SEMAS_STORE'
        AND temporal_region_status IN ('VALID', 'PENDING', 'BOUNDARY_MISMATCH')
        AND ((coverage_status = 'VALID' AND region_code IS NOT NULL AND temporal_region_status = 'VALID' AND reason_code IS NULL)
            OR (coverage_status = 'HOLD' AND reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE store_industry_count (
    batch_id BIGINT NOT NULL,
    dataset_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STORE_SNAPSHOT',
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SEMAS_STORE',
    source_period_key VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    classification_id BIGINT NOT NULL,
    industry_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    industry_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SMALL',
    accepted_store_count BIGINT NOT NULL,
    count_quality_status VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    count_quality_reason VARCHAR(100) NULL,
    count_affecting_issue_count BIGINT NOT NULL,
    PRIMARY KEY (batch_id, region_code, classification_id, industry_code),
    CONSTRAINT fk_ncount_batch FOREIGN KEY (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version)
        REFERENCES national_import_batch (batch_id, dataset_code, source_system, source_period_key, mapping_release_id, source_region_version),
    CONSTRAINT fk_ncount_node FOREIGN KEY (mapping_release_id, region_code) REFERENCES official_region_node (release_id, region_code),
    CONSTRAINT fk_ncount_batch_class FOREIGN KEY (batch_id, classification_id) REFERENCES national_import_batch (batch_id, classification_id),
    CONSTRAINT fk_ncount_industry FOREIGN KEY (classification_id, industry_code, industry_level)
        REFERENCES national_industry_code (classification_id, industry_code, industry_level),
    CONSTRAINT ck_ncount_scope CHECK (dataset_code = 'STORE_SNAPSHOT' AND source_system = 'SEMAS_STORE' AND industry_level = 'SMALL'),
    CONSTRAINT ck_ncount_quality CHECK (
        accepted_store_count BETWEEN 0 AND 9007199254740991 AND count_affecting_issue_count >= 0
        AND ((count_quality_status = 'VALID' AND count_quality_reason IS NULL AND count_affecting_issue_count = 0)
            OR (count_quality_status = 'HOLD' AND count_quality_reason IS NOT NULL
                AND CHAR_LENGTH(TRIM(count_quality_reason)) > 0 AND count_affecting_issue_count > 0))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE national_quality_issue (
    issue_id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    source_artifact_no INT NULL,
    source_row_number BIGINT NULL,
    source_region_code VARCHAR(100) NULL,
    source_store_id VARCHAR(100) NULL,
    field_name VARCHAR(100) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    severity VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    affects_count BOOLEAN NOT NULL,
    impact_scope VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    affected_region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    classification_id BIGINT NULL,
    affected_industry_code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    affected_industry_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_value JSON NULL,
    evidence JSON NOT NULL,
    PRIMARY KEY (issue_id),
    INDEX ix_nissue_lookup (batch_id, source_region_code, reason_code),
    INDEX ix_nissue_impact (batch_id, affected_region_code, affected_industry_code),
    CONSTRAINT fk_nissue_batch FOREIGN KEY (batch_id) REFERENCES national_import_batch (batch_id),
    CONSTRAINT fk_nissue_artifact FOREIGN KEY (batch_id, source_artifact_no) REFERENCES national_source_artifact (batch_id, artifact_no),
    CONSTRAINT fk_nissue_region FOREIGN KEY (affected_region_code) REFERENCES service_region (region_code),
    CONSTRAINT fk_nissue_batch_class FOREIGN KEY (batch_id, classification_id) REFERENCES national_import_batch (batch_id, classification_id),
    CONSTRAINT fk_nissue_industry FOREIGN KEY (classification_id, affected_industry_code, affected_industry_level)
        REFERENCES national_industry_code (classification_id, industry_code, industry_level),
    CONSTRAINT ck_nissue_source CHECK (
        (source_artifact_no IS NULL AND source_row_number IS NULL)
        OR (source_artifact_no IS NOT NULL AND source_row_number IS NOT NULL AND source_row_number > 0)
    ),
    CONSTRAINT ck_nissue_metadata CHECK (
        CHAR_LENGTH(TRIM(field_name)) > 0 AND CHAR_LENGTH(TRIM(reason_code)) > 0
        AND severity IN ('WARNING', 'HOLD', 'ERROR') AND affects_count IN (0, 1)
    ),
    CONSTRAINT ck_nissue_impact CHECK (
        (impact_scope IN ('NONE', 'GLOBAL') AND affected_region_code IS NULL
            AND classification_id IS NULL AND affected_industry_code IS NULL AND affected_industry_level IS NULL)
        OR (impact_scope = 'REGION' AND affected_region_code IS NOT NULL
            AND classification_id IS NULL AND affected_industry_code IS NULL AND affected_industry_level IS NULL)
        OR (impact_scope = 'REGION_INDUSTRY' AND affected_region_code IS NOT NULL AND classification_id IS NOT NULL
            AND affected_industry_code IS NOT NULL AND affected_industry_level IS NOT NULL AND affected_industry_level = 'SMALL')
    ),
    CONSTRAINT ck_nissue_count CHECK ((affects_count = FALSE AND impact_scope = 'NONE') OR (affects_count = TRUE AND impact_scope <> 'NONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE national_summary_release (
    summary_release_id BIGINT NOT NULL AUTO_INCREMENT,
    release_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_month VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    population_batch_id BIGINT NOT NULL,
    population_dataset VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'POPULATION_MONTH',
    store_batch_id BIGINT NOT NULL,
    store_dataset VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STORE_SNAPSHOT',
    batch_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SUCCEEDED',
    mapping_release_id BIGINT NOT NULL,
    mapping_release_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPROVED',
    classification_id BIGINT NOT NULL,
    classification_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPROVED',
    policy_version VARCHAR(100) NOT NULL,
    status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
    validation_report JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    PRIMARY KEY (summary_release_id),
    UNIQUE KEY uq_nsummary_version (release_version),
    UNIQUE KEY uq_nsummary_publishable (summary_release_id, reference_month, status),
    CONSTRAINT fk_nsummary_population FOREIGN KEY (population_batch_id, population_dataset, reference_month, mapping_release_id, batch_status)
        REFERENCES national_import_batch (batch_id, dataset_code, source_period_key, mapping_release_id, status),
    CONSTRAINT fk_nsummary_store FOREIGN KEY (store_batch_id, store_dataset, reference_month, mapping_release_id, batch_status)
        REFERENCES national_import_batch (batch_id, dataset_code, source_period_key, mapping_release_id, status),
    CONSTRAINT fk_nsummary_store_class FOREIGN KEY (store_batch_id, classification_id) REFERENCES national_import_batch (batch_id, classification_id),
    CONSTRAINT fk_nsummary_region FOREIGN KEY (mapping_release_id, mapping_release_status) REFERENCES region_release (release_id, status),
    CONSTRAINT fk_nsummary_class FOREIGN KEY (classification_id, classification_status) REFERENCES national_industry_release (classification_id, status),
    CONSTRAINT ck_nsummary_scope CHECK (
        population_dataset = 'POPULATION_MONTH' AND store_dataset = 'STORE_SNAPSHOT' AND batch_status = 'SUCCEEDED'
        AND mapping_release_status = 'APPROVED' AND classification_status = 'APPROVED' AND population_batch_id <> store_batch_id
        AND REGEXP_LIKE(reference_month, '^[1-9][0-9]{3}(0[1-9]|1[0-2])$', 'c')
        AND CHAR_LENGTH(TRIM(release_version)) > 0 AND CHAR_LENGTH(TRIM(policy_version)) > 0
    ),
    CONSTRAINT ck_nsummary_approval CHECK (
        (status = 'READY' AND approved_at IS NULL AND approved_by IS NULL)
        OR (status = 'APPROVED' AND approved_at IS NOT NULL AND approved_at >= created_at
            AND approved_by IS NOT NULL AND CHAR_LENGTH(TRIM(approved_by)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE national_summary_publication (
    publication_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_month VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summary_release_id BIGINT NOT NULL,
    release_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPROVED',
    revision BIGINT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (publication_key),
    CONSTRAINT fk_npublication_summary FOREIGN KEY (summary_release_id, reference_month, release_status)
        REFERENCES national_summary_release (summary_release_id, reference_month, status),
    CONSTRAINT ck_npublication_key CHECK (publication_key = 'DEFAULT' OR publication_key = CONCAT('MONTH:', reference_month)),
    CONSTRAINT ck_npublication_state CHECK (release_status = 'APPROVED' AND revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE national_summary_publication_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    publication_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_summary_release_id BIGINT NULL,
    from_reference_month VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_summary_release_id BIGINT NOT NULL,
    to_reference_month VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    release_status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPROVED',
    from_revision BIGINT NOT NULL,
    to_revision BIGINT NOT NULL,
    action VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    default_synchronized BOOLEAN NOT NULL DEFAULT FALSE,
    actor VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_nevent_revision (publication_key, to_revision),
    CONSTRAINT fk_nevent_from FOREIGN KEY (from_summary_release_id, from_reference_month, release_status)
        REFERENCES national_summary_release (summary_release_id, reference_month, status),
    CONSTRAINT fk_nevent_to FOREIGN KEY (to_summary_release_id, to_reference_month, release_status)
        REFERENCES national_summary_release (summary_release_id, reference_month, status),
    CONSTRAINT ck_nevent_key CHECK (publication_key = 'DEFAULT' OR (publication_key = CONCAT('MONTH:', to_reference_month)
        AND (from_reference_month IS NULL OR from_reference_month = to_reference_month))),
    CONSTRAINT ck_nevent_revision CHECK (
        from_revision >= 0 AND to_revision = from_revision + 1
        AND ((from_revision = 0 AND from_summary_release_id IS NULL AND from_reference_month IS NULL AND action <> 'ROLLBACK')
            OR (from_revision > 0 AND from_summary_release_id IS NOT NULL AND from_reference_month IS NOT NULL))
    ),
    CONSTRAINT ck_nevent_action CHECK (
        release_status = 'APPROVED' AND action IN ('PUBLISH', 'ROLLBACK', 'SET_DEFAULT')
        AND (action <> 'SET_DEFAULT' OR publication_key = 'DEFAULT') AND default_synchronized IN (0, 1)
        AND CHAR_LENGTH(TRIM(actor)) > 0 AND CHAR_LENGTH(TRIM(reason)) > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
