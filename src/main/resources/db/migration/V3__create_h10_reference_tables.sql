-- H10 reference storage. Import, approval and publication are separate operations.
-- V1/V2 and their source-code references remain intact until the T11/API cutover.
-- DATETIME values are supplied by the application in UTC.
CREATE TABLE service_region (
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (region_code),
    CONSTRAINT ck_service_region_code CHECK (
        CHAR_LENGTH(region_code) = 10 AND REGEXP_LIKE(region_code, '^[0-9]{10}$', 'c')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_release (
    release_id BIGINT NOT NULL AUTO_INCREMENT,
    release_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LOADING',
    parser_version VARCHAR(100) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(100) NULL,
    expected_counts JSON NULL,
    actual_counts JSON NULL,
    validation_report JSON NULL,
    failure_code VARCHAR(100) NULL,
    PRIMARY KEY (release_id),
    UNIQUE KEY uq_region_release_version (release_version),
    UNIQUE KEY uq_region_release_status (release_id, status),
    CONSTRAINT ck_region_release_version CHECK (
        REGEXP_LIKE(release_version, '^[A-Za-z0-9]', 'c')
        AND NOT REGEXP_LIKE(release_version, '[^A-Za-z0-9._-]', 'c')
    ),
    CONSTRAINT ck_region_release_status CHECK (status IN ('LOADING', 'VALIDATING', 'READY', 'APPROVED', 'FAILED')),
    CONSTRAINT ck_region_release_versions CHECK (
        CHAR_LENGTH(TRIM(parser_version)) > 0 AND CHAR_LENGTH(TRIM(policy_version)) > 0
    ),
    CONSTRAINT ck_region_release_times CHECK (
        (finished_at IS NULL OR finished_at >= started_at)
        AND (approved_at IS NULL OR (finished_at IS NOT NULL AND approved_at >= finished_at))
    ),
    CONSTRAINT ck_region_release_approval CHECK (
        (status = 'APPROVED' AND finished_at IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by IS NOT NULL AND CHAR_LENGTH(TRIM(approved_by)) > 0
            AND validation_report IS NOT NULL AND expected_counts IS NOT NULL AND actual_counts IS NOT NULL)
        OR (status <> 'APPROVED' AND approved_at IS NULL AND approved_by IS NULL)
    ),
    CONSTRAINT ck_region_release_failure CHECK (
        (status = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL
            AND CHAR_LENGTH(TRIM(failure_code)) > 0)
        OR (status <> 'FAILED' AND failure_code IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_source_artifact (
    release_id BIGINT NOT NULL,
    artifact_role VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    notice_url TEXT NOT NULL,
    download_url TEXT NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_count BIGINT NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    notice_reference_on DATE NULL,
    attachment_version VARCHAR(100) NOT NULL,
    member_path VARCHAR(500) NULL,
    encoding VARCHAR(40) NOT NULL,
    usage_review_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    usage_evidence TEXT NULL,
    PRIMARY KEY (release_id, artifact_role),
    CONSTRAINT fk_region_artifact_release FOREIGN KEY (release_id) REFERENCES region_release (release_id),
    CONSTRAINT ck_region_artifact_role CHECK (artifact_role IN ('H_CURRENT_ZIP', 'H_HISTORY_ZIP', 'LAYOUT')),
    CONSTRAINT ck_region_artifact_hash CHECK (
        CHAR_LENGTH(sha256) = 64 AND REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_region_artifact_bytes CHECK (byte_count > 0),
    CONSTRAINT ck_region_artifact_text CHECK (
        CHAR_LENGTH(TRIM(notice_url)) > 0 AND CHAR_LENGTH(TRIM(download_url)) > 0
        AND CHAR_LENGTH(TRIM(storage_key)) > 0 AND CHAR_LENGTH(TRIM(attachment_version)) > 0
        AND CHAR_LENGTH(TRIM(encoding)) > 0
    ),
    CONSTRAINT ck_region_artifact_usage CHECK (
        usage_review_status IN ('PENDING', 'VERIFIED', 'BLOCKED')
        AND (usage_review_status <> 'VERIFIED' OR (usage_evidence IS NOT NULL AND CHAR_LENGTH(TRIM(usage_evidence)) > 0))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE official_region_node (
    release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    province_name VARCHAR(100) NOT NULL,
    district_name VARCHAR(100) NULL,
    locality_name VARCHAR(100) NULL,
    raw_created_on VARCHAR(32) NOT NULL,
    raw_abolished_on VARCHAR(32) NULL,
    created_on DATE NULL,
    abolished_on DATE NULL,
    date_quality_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    date_issue_code VARCHAR(100) NULL,
    official_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    artifact_role VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_number BIGINT NOT NULL,
    PRIMARY KEY (release_id, region_code),
    UNIQUE KEY uq_region_node_source_row (release_id, artifact_role, source_row_number),
    CONSTRAINT fk_region_node_release FOREIGN KEY (release_id) REFERENCES region_release (release_id),
    CONSTRAINT fk_region_node_region FOREIGN KEY (region_code) REFERENCES service_region (region_code),
    CONSTRAINT fk_region_node_artifact FOREIGN KEY (release_id, artifact_role)
        REFERENCES region_source_artifact (release_id, artifact_role),
    CONSTRAINT ck_region_node_source CHECK (
        source_row_number > 0 AND CHAR_LENGTH(TRIM(province_name)) > 0
        AND artifact_role IN ('H_CURRENT_ZIP', 'H_HISTORY_ZIP')
    ),
    CONSTRAINT ck_region_node_status CHECK (
        (official_status = 'CURRENT' AND raw_abolished_on IS NULL)
        OR (official_status = 'LEGACY' AND raw_abolished_on IS NOT NULL AND CHAR_LENGTH(TRIM(raw_abolished_on)) > 0)
    ),
    CONSTRAINT ck_region_node_dates CHECK (
        (date_quality_status = 'VALID' AND created_on IS NOT NULL
            AND date_issue_code IS NULL
            AND ((official_status = 'CURRENT' AND abolished_on IS NULL)
                OR (official_status = 'LEGACY' AND abolished_on IS NOT NULL AND created_on <= abolished_on)))
        OR (date_quality_status = 'INVALID' AND created_on IS NULL AND abolished_on IS NULL
            AND date_issue_code IS NOT NULL AND CHAR_LENGTH(TRIM(date_issue_code)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_navigation (
    release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    node_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hierarchy_parent_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    province_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    selection_parent_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
    display_name VARCHAR(200) NOT NULL,
    display_name_derived BOOLEAN NOT NULL DEFAULT FALSE,
    selection_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE',
    selectable BOOLEAN NOT NULL DEFAULT FALSE,
    auto_select_group BOOLEAN NOT NULL DEFAULT FALSE,
    review_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    review_evidence TEXT NULL,
    PRIMARY KEY (release_id, region_code),
    INDEX ix_region_navigation_children (release_id, selection_parent_code, selectable, review_status, display_name, region_code),
    INDEX ix_region_navigation_hierarchy (release_id, hierarchy_parent_code, region_code),
    INDEX ix_region_navigation_province (release_id, province_code, selection_role, region_code),
    CONSTRAINT fk_region_navigation_node FOREIGN KEY (release_id, region_code)
        REFERENCES official_region_node (release_id, region_code),
    CONSTRAINT fk_region_navigation_parent FOREIGN KEY (release_id, hierarchy_parent_code)
        REFERENCES official_region_node (release_id, region_code),
    CONSTRAINT fk_region_navigation_province FOREIGN KEY (release_id, province_code)
        REFERENCES official_region_node (release_id, region_code),
    CONSTRAINT fk_region_navigation_selection_parent FOREIGN KEY (release_id, selection_parent_code)
        REFERENCES official_region_node (release_id, region_code),
    CONSTRAINT ck_region_navigation_kind CHECK (
        node_kind IN ('PROVINCE', 'CITY_CONTAINER', 'GROUP', 'EUP', 'MYEON', 'DONG', 'BRANCH', 'UNKNOWN')
    ),
    CONSTRAINT ck_region_navigation_role CHECK (selection_role IN ('PROVINCE', 'GROUP', 'LOCALITY', 'NONE')),
    CONSTRAINT ck_region_navigation_review CHECK (
        review_status IN ('PENDING', 'VERIFIED', 'BLOCKED')
        AND (review_status <> 'VERIFIED' OR (review_evidence IS NOT NULL AND CHAR_LENGTH(TRIM(review_evidence)) > 0))
    ),
    CONSTRAINT ck_region_navigation_flags CHECK (
        selectable IN (0, 1) AND auto_select_group IN (0, 1) AND display_name_derived IN (0, 1)
        AND (selectable = FALSE OR (review_status = 'VERIFIED' AND selection_role <> 'NONE'))
        AND (auto_select_group = FALSE OR (selectable = TRUE AND selection_role = 'GROUP'))
    ),
    CONSTRAINT ck_region_navigation_parents CHECK (
        (hierarchy_parent_code IS NULL OR hierarchy_parent_code <> region_code)
        AND (selection_parent_code IS NULL OR selection_parent_code <> region_code)
        AND ((selection_role = 'PROVINCE' AND selection_parent_code IS NULL AND province_code = region_code)
            OR (selection_role IN ('GROUP', 'LOCALITY') AND selection_parent_code IS NOT NULL)
            OR selection_role = 'NONE')
    ),
    CONSTRAINT ck_region_navigation_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_region_reference (
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_name VARCHAR(100) NOT NULL,
    source_district_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_district_name VARCHAR(100) NOT NULL,
    reference_snapshot_on DATE NOT NULL,
    source_url TEXT NOT NULL,
    reference_evidence JSON NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_system, source_region_code, source_region_version),
    CONSTRAINT ck_source_region_system CHECK (source_system = 'SEOUL_COMMERCIAL'),
    CONSTRAINT ck_source_region_codes CHECK (
        CHAR_LENGTH(source_region_code) = 8 AND REGEXP_LIKE(source_region_code, '^[0-9]{8}$', 'c')
        AND CHAR_LENGTH(source_district_code) = 5 AND REGEXP_LIKE(source_district_code, '^[0-9]{5}$', 'c')
    ),
    CONSTRAINT ck_source_region_text CHECK (
        CHAR_LENGTH(TRIM(source_region_version)) > 0 AND CHAR_LENGTH(TRIM(source_region_name)) > 0
        AND CHAR_LENGTH(TRIM(source_district_name)) > 0 AND CHAR_LENGTH(TRIM(source_url)) > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_stat_mapping (
    release_id BIGINT NOT NULL,
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_region_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    reference_snapshot_on DATE NOT NULL,
    evidence TEXT NULL,
    PRIMARY KEY (release_id, source_system, source_region_code, source_region_version, region_code),
    INDEX ix_region_mapping_region (release_id, region_code, source_system, source_region_code, source_region_version),
    CONSTRAINT fk_region_mapping_node FOREIGN KEY (release_id, region_code)
        REFERENCES official_region_node (release_id, region_code),
    CONSTRAINT fk_region_mapping_source FOREIGN KEY (source_system, source_region_code, source_region_version)
        REFERENCES source_region_reference (source_system, source_region_code, source_region_version),
    CONSTRAINT ck_region_mapping_review CHECK (
        review_status IN ('PENDING', 'VERIFIED', 'BLOCKED')
        AND (review_status <> 'VERIFIED' OR (evidence IS NOT NULL AND CHAR_LENGTH(TRIM(evidence)) > 0))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_publication (
    publication_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    release_id BIGINT NOT NULL,
    release_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'APPROVED',
    revision BIGINT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (publication_key),
    CONSTRAINT fk_region_publication_release FOREIGN KEY (release_id, release_status)
        REFERENCES region_release (release_id, status),
    CONSTRAINT ck_region_publication_key CHECK (publication_key = 'NATIONWIDE_SELECTION'),
    CONSTRAINT ck_region_publication_status CHECK (release_status = 'APPROVED'),
    CONSTRAINT ck_region_publication_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_publication_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    publication_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_release_id BIGINT NULL,
    to_release_id BIGINT NOT NULL,
    from_revision BIGINT NOT NULL,
    to_revision BIGINT NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_region_publication_event_revision (publication_key, to_revision),
    CONSTRAINT fk_region_event_from_release FOREIGN KEY (from_release_id) REFERENCES region_release (release_id),
    CONSTRAINT fk_region_event_to_release FOREIGN KEY (to_release_id) REFERENCES region_release (release_id),
    CONSTRAINT ck_region_event_key CHECK (publication_key = 'NATIONWIDE_SELECTION'),
    CONSTRAINT ck_region_event_action CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_region_event_revision CHECK (
        from_revision >= 0 AND to_revision = from_revision + 1
        AND ((from_revision = 0 AND from_release_id IS NULL AND action = 'PUBLISH')
            OR (from_revision > 0 AND from_release_id IS NOT NULL))
    ),
    CONSTRAINT ck_region_event_actor CHECK (
        CHAR_LENGTH(TRIM(actor)) > 0 AND CHAR_LENGTH(TRIM(reason)) > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
