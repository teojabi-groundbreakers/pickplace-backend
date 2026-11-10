package com.pickplace.backend.ingestion.validation.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pickplace.backend.ingestion.download.model.NationalDownloadSource;
import com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot;
import tools.jackson.databind.json.JsonMapper;

// 코드가 연결된다는 사실과 통계 기준일에 사용할 수 있다는 판단은 분리한다.
public final class ValidationReference {
    private final NationalReferenceSnapshot snapshot;
    private final Map<String, NationalReferenceSnapshot.Node> nodes = new HashMap<>();
    private final Map<String, List<NationalReferenceSnapshot.Mapping>> mappings = new HashMap<>();
    private final Map<String, NationalReferenceSnapshot.Source> sources = new HashMap<>();
    private final Map<String, NationalReferenceSnapshot.Industry> industries = new HashMap<>();
    private final Map<String, Resolution> resolutions = new HashMap<>();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ValidationReference(NationalReferenceSnapshot snapshot) {
        this.snapshot = snapshot;
        // 약 275만 업소 행마다 전체 목록을 탐색하지 않도록 조회용 인덱스를 한 번 만든다.
        snapshot.nodes().forEach(n -> nodes.put(n.code(), n));
        snapshot.industries().forEach(i -> industries.put(i.code(), i));
        snapshot.sources().forEach(s -> {
            if (sources.putIfAbsent(key(s.system(), s.code(), s.version()), s) != null)
                throw new IllegalArgumentException("중복 원천 지역입니다.");
        });
        snapshot.mappings().forEach(m -> mappings.computeIfAbsent(key(m.system(), m.sourceCode(), m.sourceVersion()),
                ignored -> new java.util.ArrayList<>()).add(m));
    }

    public String sourceVersion(NationalDownloadSource source) {
        // 같은 코드라도 배포본마다 의미가 달라질 수 있어 기준일·출처·원본 해시까지 맞춰 버전을 고정한다.
        var versions = snapshot.sources().stream().filter(s -> s.system().equals(source.sourceSystem())
                && s.referenceOn().toString().equals(source.referenceOn()) && s.url().equals(source.downloadUrl()))
                .filter(s -> source.sha256().equals(mapper.readTree(s.evidence()).path("sha256").asText()))
                .map(NationalReferenceSnapshot.Source::version).distinct().toList();
        if (versions.size() != 1) throw new IllegalArgumentException("원본과 일치하는 원천 지역 버전을 하나로 확정할 수 없습니다.");
        // 일부 지역만 출처가 다른 경우도 통과하지 않도록 선택한 버전의 모든 지역을 검사한다.
        boolean consistent = snapshot.sources().stream().filter(s -> s.system().equals(source.sourceSystem())
                && s.version().equals(versions.getFirst())).allMatch(s -> s.referenceOn().toString().equals(source.referenceOn())
                    && s.url().equals(source.downloadUrl())
                    && source.sha256().equals(mapper.readTree(s.evidence()).path("sha256").asText()));
        if (!consistent) throw new IllegalArgumentException("동일 원천 버전의 출처가 일치하지 않습니다.");
        // 코드 문자열이 같아도 다른 원본에서 만든 분류를 섞어 쓰지 않는다.
        if (source.dataset().equals("STORE_SNAPSHOT") && !source.sha256().equals(snapshot.classificationSourceSha256()))
            throw new IllegalArgumentException("업소 원본과 업종 분류의 출처가 다릅니다.");
        return versions.getFirst();
    }

    public Resolution resolve(String system, String code, String version, LocalDate on) {
        // 모르는 코드는 캐시에 쌓지 않는다. 잘못된 코드가 많아도 메모리가 계속 증가하지 않게 한다.
        if (!sources.containsKey(key(system, code, version))) return new Resolution(null, "PENDING", "MAPPING_UNRESOLVED");
        // 같은 지역을 반복 사용하는 업소 행은 기준일별 판정을 재사용한다. 병렬 호출용 클래스는 아니다.
        return resolutions.computeIfAbsent(key(system, code, version) + ":" + on,
                ignored -> resolveUncached(system, code, version, on));
    }

    private Resolution resolveUncached(String system, String code, String version, LocalDate on) {
        var source = sources.get(key(system, code, version));
        var candidates = mappings.getOrDefault(key(system, code, version), List.of());
        // 여러 후보 중 하나를 임의로 선택하면 다른 지역으로 집계될 수 있으므로 보류한다.
        if (source == null || candidates.size() != 1) return new Resolution(null, "PENDING", "MAPPING_UNRESOLVED");
        var mapping = candidates.getFirst();
        var node = nodes.get(mapping.targetCode());
        if (node == null || !"VERIFIED".equals(mapping.reviewStatus())
                || !source.referenceOn().equals(on) || !mapping.referenceOn().equals(on)
                || (system.equals("MOIS_RESIDENT") && !code.equals(mapping.targetCode())))
            return new Resolution(null, "PENDING", "MAPPING_UNRESOLVED");
        // 대응 관계가 확인됐더라도 공식 생성·말소일이 불명확하면 통계 사용은 별도 보류한다.
        if (!"VALID".equals(node.dateStatus()) || node.createdOn() == null)
            return new Resolution(node.code(), "PENDING", "BOUNDARY_REVIEW_PENDING");
        if (on.isBefore(node.createdOn()) || (node.abolishedOn() != null && on.isAfter(node.abolishedOn())))
            return new Resolution(node.code(), "BOUNDARY_MISMATCH", "BOUNDARY_MISMATCH");
        // 변경 당일은 경계 적용 시점이 확정되지 않았다. 후속 동으로 자동 치환하거나 배분하지 않는다.
        String temporal = mapper.readTree(mapping.evidence()).path("temporalStatus").asText();
        if (on.equals(node.createdOn()) || on.equals(node.abolishedOn()) || "CHANGE_DATE_HOLD".equals(temporal))
            return new Resolution(node.code(), "PENDING", "BOUNDARY_REVIEW_PENDING");
        if (!"WITHIN_RECORDED_PERIOD".equals(temporal))
            return new Resolution(node.code(), "PENDING", "BOUNDARY_REVIEW_PENDING");
        return new Resolution(node.code(), "VALID", null);
    }

    public boolean validIndustry(String large, String middle, String small) {
        // 지원 업종 3개 여부와 무관하게 전체 업종의 계층을 검증한다. 화면 노출 필터는 API의 책임이다.
        var l = industries.get(large); var m = industries.get(middle); var s = industries.get(small);
        return l != null && m != null && s != null && "LARGE".equals(l.level()) && "MIDDLE".equals(m.level())
                && "SMALL".equals(s.level()) && large.equals(m.parentCode()) && middle.equals(s.parentCode());
    }

    private static String key(String system, String code, String version) { return system + ":" + version + ":" + code; }
    public record Resolution(String regionCode, String temporalStatus, String reason) {}
}
