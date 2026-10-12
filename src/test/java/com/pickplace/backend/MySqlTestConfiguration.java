package com.pickplace.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class MySqlTestConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer("mysql:8.4.11")
				.withDatabaseName("pickplace_test")
				.withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
	}

}
