package com.pickplace.backend.ingestion.validation.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import tools.jackson.databind.json.JsonMapper;

// 같은 ID는 같은 파일로 보내 ZIP 전체의 중복을 검사한다. 한 파일씩 읽어 메모리를 제한한다.
final class PartitionedRowProcessor implements AutoCloseable {
    private static final int PARTITIONS = 64;
    // 특정 파일에 키가 과도하게 몰리면 메모리를 계속 늘리는 대신 검증을 실패시킨다.
    private static final int MAX_KEYS_PER_PARTITION = 150_000;
    private final Path directory;
    private final JsonMapper mapper;
    private final BufferedWriter[] writers = new BufferedWriter[PARTITIONS];

    PartitionedRowProcessor(Path directory, JsonMapper mapper) throws IOException {
        this.directory = directory; this.mapper = mapper;
        Files.createDirectory(directory);
    }

    void add(List<String> cells, ValidationRow row) throws IOException {
        String key = row.sourceKey();
        // 빈 ID는 원본 위치별로 구분한다. 접두사를 달아 실제 원천 ID와도 충돌하지 않게 한다.
        if (key == null || key.isBlank()) key = "\u0000" + row.position().artifactNo() + ":" + row.position().rowNumber();
        else key = "\u0001" + key;
        // 모든 CSV에서 같은 키가 같은 파티션으로 가므로 파일을 넘나드는 중복도 찾을 수 있다.
        int partition = Math.floorMod(key.hashCode(), PARTITIONS);
        if (writers[partition] == null) writers[partition] = Files.newBufferedWriter(path(partition), StandardOpenOption.CREATE_NEW);
        String fingerprint;
        try {
            // 정제 과정에서 버린 필드도 비교에 포함한다. 따옴표 표기 대신 CSV 필드 값 전체를 해시한다.
            fingerprint = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(cells)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        writers[partition].write(mapper.writeValueAsString(new Entry(key, fingerprint, row)));
        writers[partition].newLine();
    }

    void finish(RowConsumer consumer) throws IOException {
        // 버퍼를 모두 비운 다음 읽는다. 쓰기 도중의 임시 파일로 중복 여부를 판정하지 않는다.
        close();
        for (int i = 0; i < PARTITIONS; i++) {
            Path path = path(i);
            if (!Files.exists(path)) continue;
            var index = new HashMap<String, Identity>();
            // 첫 번째 읽기: 같은 키의 내용 충돌 여부를 끝까지 확인한다.
            try (var reader = Files.newBufferedReader(path)) {
                for (String line; (line = reader.readLine()) != null;) {
                    interrupted();
                    Entry entry = mapper.readValue(line, Entry.class);
                    Identity identity = index.get(entry.key());
                    if (identity == null) {
                        if (index.size() >= MAX_KEYS_PER_PARTITION) throw new IOException("중복 검사 파티션 한도 초과. 결과를 게시하지 않습니다.");
                        index.put(entry.key(), new Identity(entry.fingerprint()));
                    } else if (!identity.fingerprint.equals(entry.fingerprint())) identity.conflict = true;
                }
            }
            // 두 번째 읽기: 첫 행까지 포함해 최종 판정을 전달한다. 충돌을 나중에 발견해도 되돌릴 필요가 없다.
            try (var reader = Files.newBufferedReader(path)) {
                for (String line; (line = reader.readLine()) != null;) {
                    interrupted();
                    Entry entry = mapper.readValue(line, Entry.class);
                    Identity identity = index.get(entry.key());
                    ValidationRow row = entry.row();
                    if (identity.conflict) row = row.withDuplicate(true);
                    else if (identity.seen) row = row.withDuplicate(false);
                    identity.seen = true;
                    consumer.accept(row);
                }
            }
            // 전달이 끝난 파티션은 바로 지워 임시 디스크 사용량을 줄인다.
            Files.delete(path);
        }
        Files.delete(directory);
    }

    private Path path(int index) { return directory.resolve("part-%02d.jsonl".formatted(index)); }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("정제 검사가 중단되었습니다.");
    }

    @Override public void close() throws IOException {
        // 하나를 닫다가 실패해도 나머지 파일의 핸들은 모두 닫아 후속 정리가 가능하게 한다.
        IOException failure = null;
        for (int i = 0; i < writers.length; i++) {
            if (writers[i] == null) continue;
            try { writers[i].close(); } catch (IOException exception) { failure = exception; }
            writers[i] = null;
        }
        if (failure != null) throw failure;
    }

    private static final class Identity {
        final String fingerprint;
        boolean conflict;
        boolean seen;
        Identity(String fingerprint) { this.fingerprint = fingerprint; }
    }
    record Entry(String key, String fingerprint, ValidationRow row) {}
    @FunctionalInterface interface RowConsumer { void accept(ValidationRow row) throws IOException; }
}
