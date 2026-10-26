package com.pickplace.backend.health;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseHealthChecker {

	private static final int QUERY_TIMEOUT_SECONDS = 2;

	private final JdbcTemplate jdbcTemplate;

	public DatabaseHealthChecker(DataSource dataSource) {
		// 공용 JdbcTemplate의 타임아웃을 바꾸면 다른 조회에도 영향이 가므로 상태 확인 전용으로 만든다.
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
	}

	// DB에 SELECT 1을 보내 연결을 확인한다. 실패하면 DataAccessException이 발생한다.
	public void check() {
		jdbcTemplate.queryForObject("SELECT 1", Integer.class);
	}

}
