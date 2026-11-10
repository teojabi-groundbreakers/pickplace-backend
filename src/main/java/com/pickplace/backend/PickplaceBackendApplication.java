package com.pickplace.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PickplaceBackendApplication {

	public static void main(String[] args) {
		var context = SpringApplication.run(PickplaceBackendApplication.class, args);
		if (context.getEnvironment().matchesProfiles("reference-import")) {
			context.close();
		}
	}

}
