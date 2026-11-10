package com.pickplace.backend.reference.nationwide;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class NationalReferenceLoader {
    private final JsonMapper mapper;

    public NationalReferenceLoader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Loaded load(Path path, String expectedSha256) {
        try {
            if (Files.size(path) > 32 * 1024 * 1024) {
                throw new IllegalArgumentException("Reference snapshot exceeds 32 MiB");
            }
            byte[] bytes = Files.readAllBytes(path);
            String actual = sha256(bytes);
            if (!actual.equals(expectedSha256)) {
                throw new IllegalArgumentException("Reference snapshot checksum mismatch");
            }
            return new Loaded(mapper.readValue(bytes, NationalReferenceSnapshot.class), actual);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read nationwide reference snapshot", exception);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Loaded(NationalReferenceSnapshot snapshot, String sha256) {}
}
