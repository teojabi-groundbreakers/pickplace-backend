-- 좌표는 지역 게시본에 연결된 검토 자료로 저장한다. 공개 여부는 후속 작업에서 결정한다.
CREATE TABLE region_map_coordinate_batch (
    batch_id BIGINT NOT NULL AUTO_INCREMENT,
    region_release_id BIGINT NOT NULL,
    snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boundary_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boundary_date DATE NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    license_url VARCHAR(500) NOT NULL,
    attribution TEXT NOT NULL,
    coordinate_crs VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculation_method VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVIEW_ONLY',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (batch_id),
    UNIQUE KEY uq_coordinate_batch_release_source (region_release_id, snapshot_sha256, boundary_sha256),
    UNIQUE KEY uq_coordinate_batch_release (batch_id, region_release_id),
    CONSTRAINT fk_coordinate_batch_release FOREIGN KEY (region_release_id) REFERENCES region_release (release_id),
    CONSTRAINT ck_coordinate_batch_hashes CHECK (
        REGEXP_LIKE(snapshot_sha256, '^[0-9a-f]{64}$', 'c') AND REGEXP_LIKE(boundary_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_coordinate_batch_metadata CHECK (
        CHAR_LENGTH(TRIM(source_url)) > 0 AND CHAR_LENGTH(TRIM(license_url)) > 0
        AND CHAR_LENGTH(TRIM(attribution)) > 0 AND CHAR_LENGTH(TRIM(calculation_method)) > 0
        AND coordinate_crs = 'EPSG:4326' AND status = 'REVIEW_ONLY'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE region_map_coordinate (
    batch_id BIGINT NOT NULL,
    region_release_id BIGINT NOT NULL,
    region_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    latitude DECIMAL(11,8) NULL,
    longitude DECIMAL(11,8) NULL,
    PRIMARY KEY (batch_id, region_code),
    CONSTRAINT fk_coordinate_batch FOREIGN KEY (batch_id, region_release_id)
        REFERENCES region_map_coordinate_batch (batch_id, region_release_id),
    CONSTRAINT fk_coordinate_region FOREIGN KEY (region_release_id, region_code)
        REFERENCES region_navigation (release_id, region_code),
    CONSTRAINT ck_coordinate_status CHECK (
        (status = 'BOUNDARY_INTERIOR' AND latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN 32 AND 39.5 AND longitude BETWEEN 124 AND 132)
        OR (status IN ('MISSING_BOUNDARY', 'DUPLICATE_BOUNDARY', 'INVALID_BOUNDARY', 'REGION_MISMATCH')
            AND latitude IS NULL AND longitude IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
