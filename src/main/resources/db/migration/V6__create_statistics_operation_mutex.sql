-- 첫 게시처럼 아직 포인터 행이 없는 경우에도 경합을 직렬화할 수 있도록 고정 행을 둔다.
-- 커넥션에 묶이는 GET_LOCK 대신 일반 트랜잭션의 SELECT ... FOR UPDATE로 사용한다.
CREATE TABLE national_statistics_mutex (
    operation_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (operation_key),
    CONSTRAINT ck_statistics_mutex CHECK (operation_key IN ('IMPORT_START', 'SUMMARY_PUBLICATION'))
) ENGINE=InnoDB;

INSERT INTO national_statistics_mutex (operation_key) VALUES ('IMPORT_START'), ('SUMMARY_PUBLICATION');
