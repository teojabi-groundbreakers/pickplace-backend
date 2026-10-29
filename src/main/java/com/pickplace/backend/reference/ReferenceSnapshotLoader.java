package com.pickplace.backend.reference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReferenceSnapshotLoader {

	private static final String EXPECTED_SHA256 = "da5e5e5aaccc7f6ca0e2706dc925a6076274491211060bd386388759e35ec796";
	private final JsonMapper mapper;

	public ReferenceSnapshotLoader(JsonMapper mapper) {
		this.mapper = mapper;
	}

	public ReferenceSnapshot load() {
		try (var input = new ClassPathResource("reference-data/seoul-reference.json").getInputStream()) {
			byte[] bytes = input.readAllBytes();
			String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			if (!EXPECTED_SHA256.equals(actual)) {
				throw new IllegalStateException("Reference snapshot checksum mismatch; review the source manifest first");
			}
			return mapper.readValue(bytes, ReferenceSnapshot.class);
		} catch (IOException exception) {
			throw new UncheckedIOException("Cannot read reference snapshot", exception);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
