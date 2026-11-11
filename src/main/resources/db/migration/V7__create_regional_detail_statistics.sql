-- 원천별 상세 통계는 전국 월 현황 및 과거 V2 테이블과 분리한다.
CREATE TABLE regional_statistic_batch (
    batch_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dataset_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_keys JSON NOT NULL,
    region_scheme VARCHAR(80) NOT NULL,
    industry_scheme VARCHAR(80) NOT NULL,
    mapping_release_id BIGINT NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RUNNING',
    active_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
        (CASE WHEN status <> 'FAILED' THEN fingerprint ELSE NULL END) STORED,
    definition_json JSON NOT NULL,
    mapping_json JSON NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    source_row_count BIGINT NULL,
    accepted_row_count BIGINT NULL,
    held_row_count BIGINT NULL,
    invalid_row_count BIGINT NULL,
    duplicate_row_count BIGINT NULL,
    validation_report JSON NULL,
    failure_code VARCHAR(64) NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    UNIQUE KEY uq_regional_attempt (source_system,dataset_code,fingerprint,attempt_no),
    UNIQUE KEY uq_regional_active (source_system,dataset_code,active_fingerprint),
    UNIQUE KEY uq_regional_batch_dataset (batch_id,source_system,dataset_code,period_type),
    UNIQUE KEY uq_regional_batch_mapping (batch_id,mapping_release_id),
    FOREIGN KEY (mapping_release_id) REFERENCES region_release(release_id),
    CONSTRAINT ck_regional_batch_state CHECK (status IN ('RUNNING','READY','APPROVED','FAILED')),
    CONSTRAINT ck_regional_batch_period CHECK (period_type IN ('MONTH','QUARTER','YEAR','SNAPSHOT') AND JSON_TYPE(period_keys)='ARRAY' AND JSON_LENGTH(period_keys)>0),
    CONSTRAINT ck_regional_batch_identity CHECK (attempt_no>0 AND REGEXP_LIKE(fingerprint,'^[0-9a-f]{64}$','c')
        AND CHAR_LENGTH(TRIM(source_system))>0 AND CHAR_LENGTH(TRIM(dataset_code))>0),
    CONSTRAINT ck_regional_batch_counts CHECK ((status IN ('RUNNING','FAILED')) OR
        (finished_at IS NOT NULL AND validation_report IS NOT NULL AND source_row_count>0
        AND accepted_row_count>=0 AND held_row_count>=0 AND invalid_row_count>=0 AND duplicate_row_count>=0
        AND source_row_count=accepted_row_count+held_row_count+invalid_row_count+duplicate_row_count)),
    CONSTRAINT ck_regional_batch_approval CHECK ((status='APPROVED' AND approved_at IS NOT NULL AND approved_by IS NOT NULL)
        OR (status<>'APPROVED' AND approved_at IS NULL AND approved_by IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regional_statistic_artifact (
    batch_id BIGINT PRIMARY KEY,
    storage_key TEXT NOT NULL,
    source_url TEXT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_count BIGINT NOT NULL,
    metadata_json JSON NOT NULL,
    FOREIGN KEY (batch_id) REFERENCES regional_statistic_batch(batch_id),
    CHECK (byte_count>0 AND REGEXP_LIKE(sha256,'^[0-9a-f]{64}$','c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regional_statistic_row (
    batch_id BIGINT NOT NULL,
    period_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_industry_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    mapping_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    temporal_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) NULL,
    metrics JSON NOT NULL,
    raw_payload JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_number BIGINT NOT NULL,
    PRIMARY KEY (batch_id,period_key,source_region_code,source_industry_code),
    INDEX ix_regional_lookup (region_code,period_key,batch_id,source_industry_code),
    FOREIGN KEY (batch_id,mapping_release_id) REFERENCES regional_statistic_batch(batch_id,mapping_release_id),
    FOREIGN KEY (mapping_release_id,region_code) REFERENCES official_region_node(release_id,region_code),
    CHECK (source_row_number>0 AND row_status IN ('VALID','HOLD','INVALID')),
    CHECK (mapping_status IN ('VERIFIED','UNRESOLVED') AND temporal_status IN ('VALID','PENDING','BOUNDARY_MISMATCH')),
    CHECK (row_status<>'VALID' OR (mapping_status='VERIFIED' AND temporal_status='VALID' AND region_code IS NOT NULL AND reason_code IS NULL)),
    CHECK (row_status='VALID' OR reason_code IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regional_statistic_issue (
    issue_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    source_row_number BIGINT NOT NULL,
    issue_code VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL,
    INDEX ix_regional_issue (batch_id,source_row_number),
    FOREIGN KEY (batch_id) REFERENCES regional_statistic_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regional_statistic_publication (
    source_system VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dataset_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_system,dataset_code,period_type,period_key),
    FOREIGN KEY (batch_id,source_system,dataset_code,period_type)
        REFERENCES regional_statistic_batch(batch_id,source_system,dataset_code,period_type),
    CHECK (revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regional_statistic_publication_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(40) NOT NULL,
    dataset_code VARCHAR(40) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_key VARCHAR(16) NOT NULL,
    previous_batch_id BIGINT NULL,
    batch_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    actor VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    FOREIGN KEY (previous_batch_id) REFERENCES regional_statistic_batch(batch_id),
    FOREIGN KEY (batch_id) REFERENCES regional_statistic_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regional_statistic_mutex (operation_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY);
INSERT INTO regional_statistic_mutex VALUES ('IMPORT_START'),('PUBLICATION');

-- 검증 중이거나 보류된 행은 이 읽기 경로에 나타나지 않는다. 기존 현황 API는 이 뷰를 사용하지 않는다.
CREATE VIEW published_regional_statistic AS
SELECT p.source_system,p.dataset_code,p.period_type,p.revision,p.published_at,r.*
FROM regional_statistic_publication p JOIN regional_statistic_batch b ON b.batch_id=p.batch_id AND b.status='APPROVED'
JOIN regional_statistic_row r ON r.batch_id=p.batch_id AND r.period_key=p.period_key AND r.row_status='VALID';
