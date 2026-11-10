package com.pickplace.backend.reference.nationwide;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.pickplace.backend.reference.nationwide.NationalReferenceValidator.require;

@Component
public class InitialRegionSelectionPolicy {
    private final Definition definition;
    private final Set<String> excludedCodes;
    private final JsonMapper mapper;

    public InitialRegionSelectionPolicy(JsonMapper mapper) {
        this.mapper = mapper;
        try (var input = new ClassPathResource("reference-data/national-initial-selection-policy.json").getInputStream()) {
            definition = mapper.readValue(input, Definition.class);
            excludedCodes = Set.copyOf(definition.excludedCodes());
            require(excludedCodes.size() == definition.excludedCodes().size()
                    && excludedCodes.stream().allMatch(code -> code.matches("[0-9]{10}")), "Invalid exclusion policy codes");
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load initial selection policy", exception);
        }
    }

    public boolean acceptsExcluded(NationalReferenceSnapshot snapshot, NationalReferenceSnapshot.Navigation node) {
        if (!definition.version().equals(snapshot.policyVersion()) || !excludedCodes.contains(node.code())
                || !node.reviewStatus().equals("BLOCKED") || node.selectable() || node.autoSelect()
                || !node.role().equals("NONE") || node.evidence() == null) return false;
        boolean sameSource = snapshot.artifacts().stream().anyMatch(artifact -> artifact.role().equals("H_CURRENT_ZIP")
                && definition.currentArtifactSha256().equals(artifact.sha256()));
        if (!sameSource) return false;
        var evidence = mapper.readTree(node.evidence());
        return definition.version().equals(evidence.path("policy").asText())
                && evidence.path("reason").asText().equals("INITIAL_SELECTION_EXCLUDED");
    }

    public void validate(NationalReferenceSnapshot snapshot) {
        if (!definition.version().equals(snapshot.policyVersion())) return;
        var nodes = snapshot.navigation().stream().collect(java.util.stream.Collectors.toMap(
                NationalReferenceSnapshot.Navigation::code, java.util.function.Function.identity()));
        for (String code : excludedCodes) {
            require(nodes.containsKey(code) && acceptsExcluded(snapshot, nodes.get(code)),
                    "Confirmed exclusion missing or changed: " + code);
        }
    }

    private record Definition(String version, String currentArtifactSha256, List<String> excludedCodes) {}
}
