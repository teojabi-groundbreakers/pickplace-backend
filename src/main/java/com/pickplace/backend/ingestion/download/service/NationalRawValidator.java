package com.pickplace.backend.ingestion.download.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;

// 원본 무결성·CSV 구조·기간을 검사한다. 숫자 정제·지역 대응·업소 중복 판정은 T13에서 한다.
public final class NationalRawValidator {
    private static final long MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024;
    private static final CSVFormat CSV = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(false).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();

    public Validation validate(Path raw, NationalDownloadSource source) throws IOException {
        if (Files.size(raw) != source.expectedBytes() || !sha256(raw).equals(source.sha256())) {
            throw new IOException("원본 바이트 수 또는 SHA-256이 검증된 배포본과 다릅니다.");
        }
        var members = new ArrayList<Member>();
        var ignored = new ArrayList<String>();
        long[] budget = {MAX_EXPANDED_BYTES};
        if (source.format().equals("CSV")) {
            try (var input = new MeasuredInput(Files.newInputStream(raw), budget)) {
                long rows = csv(input, source, true);
                members.add(input.member(null, rows, source.headers()));
            }
        } else {
            try (var zip = new ZipFile(raw.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
                var names = new HashSet<String>();
                var csvNames = new HashSet<String>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (!names.add(name) || name.startsWith("/") || name.contains("\\")
                            || java.util.Arrays.asList(name.split("/")).contains("..")) throw new IOException("ZIP 멤버 이름 오류");
                    if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) { ignored.add(name); continue; }
                    if (!source.csvMembers().contains(name) || entry.isDirectory() || entry.getSize() < 0
                            || entry.getSize() > budget[0]) throw new IOException("ZIP CSV 구성 또는 크기 오류");
                    csvNames.add(name);
                    try (var input = new MeasuredInput(zip.getInputStream(entry), budget)) {
                        long rows = csv(input, source, false);
                        if (input.bytes != entry.getSize() || input.crc.getValue() != entry.getCrc()) throw new IOException("ZIP 멤버 길이 또는 CRC 오류");
                        members.add(input.member(name, rows, source.headers()));
                    }
                }
                if (!csvNames.equals(new HashSet<>(source.csvMembers()))) throw new IOException("필수 ZIP CSV 멤버가 누락되었습니다.");
            }
        }
        long rows = members.stream().mapToLong(Member::rows).sum();
        if (rows != source.expectedRows()) throw new IOException("원본 데이터 행수가 검증된 배포본과 다릅니다.");
        return new Validation(rows, List.copyOf(members), List.copyOf(ignored));
    }

    private long csv(InputStream input, NationalDownloadSource source, boolean population) throws IOException {
        var decoder = Charset.forName(source.encoding()).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new PushbackReader(new InputStreamReader(input, decoder), 1);
        int first = reader.read();
        if (first != -1 && first != 0xFEFF) reader.unread(first);
        try (var parser = CSV.parse(reader)) {
            if (!parser.getHeaderNames().equals(source.headers())) throw new IOException("CSV 헤더·순서가 검증본과 다릅니다.");
            long count = 0;
            for (var row : parser) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("CSV 검사가 중단되었습니다.");
                if (!row.isConsistent()) throw new IOException("CSV 행의 컬럼 수가 헤더와 다릅니다.");
                if (population && !source.referenceOn().equals(row.get("기준연월"))) throw new IOException("인구 원본의 기준일이 요청과 다릅니다.");
                count++;
                if (count > source.expectedRows()) throw new IOException("원본 행수 제한 초과");
            }
            if (count == 0) throw new IOException("비어 있는 CSV입니다.");
            return count;
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } catch (IllegalArgumentException exception) {
            throw new IOException("CSV 구조 오류", exception);
        }
    }

    public static String sha256(Path path) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("원본 해시 검사가 중단되었습니다.");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static final class MeasuredInput extends FilterInputStream {
        private final MessageDigest hash = digest();
        private final CRC32 crc = new CRC32();
        private final long[] budget;
        private long bytes;

        MeasuredInput(InputStream input, long[] budget) { super(input); this.budget = budget; }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) { measured(1); hash.update((byte) value); crc.update(value); }
            return value;
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            int count = in.read(data, offset, length);
            if (count > 0) { measured(count); hash.update(data, offset, count); crc.update(data, offset, count); }
            return count;
        }

        private void measured(int count) throws IOException {
            bytes += count; budget[0] -= count;
            if (budget[0] < 0) throw new IOException("ZIP 해제 크기 제한 초과");
        }

        Member member(String name, long rows, List<String> headers) {
            return new Member(name, bytes, HexFormat.of().formatHex(hash.digest()), String.format("%08x", crc.getValue()), rows, headers);
        }
    }

    public record Member(String name, long bytes, String sha256, String crc32, long rows, List<String> columns) {}
    public record Validation(long rows, List<Member> members, List<String> ignoredMembers) {}
}
