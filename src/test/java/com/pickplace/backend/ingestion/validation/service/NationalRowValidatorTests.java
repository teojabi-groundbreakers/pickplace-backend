package com.pickplace.backend.ingestion.validation.service;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.pickplace.backend.ingestion.validation.service.ValidationFixtures.*;

class NationalRowValidatorTests {
    @Test void realZeroIsAvailable() throws Exception {
        var row = validator("POPULATION_MONTH").validate(population(), POSITION);
        assertThat(row.disposition()).isEqualTo("ACCEPTED");
        assertThat(row.population().total()).isZero();
        assertThat(row.population().maleAges()).hasSize(111);
    }

    @Test void checksEveryAgeIncluding110Plus() throws Exception {
        var c = population(); c.set(229, "1");
        var row = validator("POPULATION_MONTH").validate(c, POSITION);
        assertThat(row.population().rowStatus()).isEqualTo("INVALID");
        assertThat(row.population().total()).isNull();
        assertThat(row.issues()).extracting("code").contains("POPULATION_SUM_MISMATCH");
    }

    @Test void verifiesMaleFemaleAndTotalRatherThanAgeSumAlone() throws Exception {
        var c = population(); c.set(5, "2"); c.set(6, "1"); c.set(8, "1");
        assertThat(validator("POPULATION_MONTH").validate(c, POSITION).population().rowStatus()).isEqualTo("INVALID");
    }

    @Test void acceptsGroupedIntegerWithMatchingSums() throws Exception {
        var c = population(); c.set(5, "1,000"); c.set(6, "1000"); c.set(8, "1000");
        assertThat(validator("POPULATION_MONTH").validate(c, POSITION).population().total()).isEqualTo(1000L);
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "1.5", "NaN", "1e3", "１２", "1,00", "9007199254740992", "9999999999999999999999999"})
    void invalidNumbersNeverBecomeZero(String raw) throws Exception {
        var c = population(); c.set(8, raw);
        var row = validator("POPULATION_MONTH").validate(c, POSITION);
        assertThat(row.population().rowStatus()).isEqualTo("INVALID");
        assertThat(row.population().total()).isNull();
        assertThat(row.issues()).extracting("rawValue").contains(raw);
    }

    @Test void missingAndSuppressedAreSeparate() throws Exception {
        var c = population(); c.set(8, "");
        assertThat(validator("POPULATION_MONTH").validate(c, POSITION).population().rowStatus()).isEqualTo("NO_DATA");
        c.set(8, "*");
        assertThat(validator("POPULATION_MONTH").validate(c, POSITION).population().rowStatus()).isEqualTo("SUPPRESSED");
    }

    @Test void populationDateMismatchIsHeld() throws Exception {
        var c = population(); c.set(1, "2026-10-31");
        assertThat(validator("POPULATION_MONTH").validate(c, POSITION).issues()).extracting("code").contains("REFERENCE_DATE_MISMATCH");
    }

    @Test void coordinateWarningDoesNotRemoveStoreFromCount() throws Exception {
        var c = store("STORE1"); c.set(37, "NaN");
        var row = validator("STORE_SNAPSHOT").validate(c, POSITION);
        assertThat(row.disposition()).isEqualTo("ACCEPTED");
        assertThat(row.store().longitude()).isNull(); assertThat(row.store().latitude()).isNull();
        assertThat(row.issues()).allMatch(i -> !i.affectsCount() && i.impactScope().equals("NONE"));
    }

    @Test void optionalFloorAndUnitAreNotCountRequirements() throws Exception {
        var c = store("STORE1"); c.set(35, "정보없음"); c.set(36, "-");
        assertThat(validator("STORE_SNAPSHOT").validate(c, POSITION).disposition()).isEqualTo("ACCEPTED");
    }

    @Test void oversizedOptionalNameIsOmittedWithoutChangingTheCount() throws Exception {
        var c = store("STORE1"); c.set(1, "가".repeat(301));
        var row = validator("STORE_SNAPSHOT").validate(c, POSITION);
        assertThat(row.disposition()).isEqualTo("ACCEPTED");
        assertThat(row.store().name()).isNull();
        assertThat(row.issues()).anyMatch(i -> i.code().equals("OPTIONAL_VALUE_TOO_LONG") && !i.affectsCount());
    }

    @Test void legalDongIsNotUsedAsAdministrativeDong() throws Exception {
        var c = store("STORE1"); c.set(15, "99999999");
        var row = validator("STORE_SNAPSHOT").validate(c, POSITION);
        assertThat(row.regionCode()).isNull();
        assertThat(row.disposition()).isEqualTo("HOLD");
        assertThat(row.issues()).anyMatch(i -> i.impactScope().equals("GLOBAL"));
    }

    @Test void unknownIndustryHoldsWholeKnownRegion() throws Exception {
        var c = store("STORE1"); c.set(7, "UNKNOWN");
        var row = validator("STORE_SNAPSHOT").validate(c, POSITION);
        assertThat(row.industryCode()).isNull();
        assertThat(row.issues()).anyMatch(i -> i.impactScope().equals("REGION") && i.affectedIndustry() == null);
    }

    @Test void parentIndustryMismatchIsNotSilentlyJoinedBySmallCode() throws Exception {
        var c = store("STORE1"); c.set(5, "I999");
        assertThat(validator("STORE_SNAPSHOT").validate(c, POSITION).disposition()).isEqualTo("HOLD");
    }

    @Test void invalidIdIsRejectedButCountImpactIsPreserved() throws Exception {
        var c = store("잘못된 업소번호");
        var row = validator("STORE_SNAPSHOT").validate(c, POSITION);
        assertThat(row.disposition()).isEqualTo("REJECTED");
        assertThat(row.issues()).anyMatch(i -> i.code().equals("INVALID_STORE_ID") && i.impactScope().equals("REGION_INDUSTRY"));
    }

    @Test void unapprovedMappingIsNotUsed() throws Exception {
        var source = source("STORE_SNAPSHOT"); var s = snapshot(source);
        var mappings = s.mappings().stream().map(m -> new com.pickplace.backend.reference.nationwide.NationalReferenceSnapshot.Mapping(
                m.system(), m.sourceCode(), m.sourceVersion(), m.targetCode(), m.referenceOn(), "PENDING", m.evidence())).toList();
        var ref = new ValidationReference(copy(s, s.nodes(), s.sources(), mappings, s.classificationSourceSha256()));
        assertThat(ref.resolve("SEMAS_STORE", "11110515", "semas-test-v1", LocalDate.parse("2026-09-30")).reason()).isEqualTo("MAPPING_UNRESOLVED");
    }

    @ParameterizedTest @ValueSource(strings = {"2026-09-30", "2026-09-29"})
    void boundaryDayIsHeldAndExpiredCodeIsNotRedistributed(String abolished) throws Exception {
        var source = source("STORE_SNAPSHOT");
        var s = changeDates(snapshot(source), LocalDate.parse("2000-01-01"), LocalDate.parse(abolished));
        var row = new NationalRowValidator(new ValidationReference(s), source).validate(store("STORE1"), POSITION);
        assertThat(row.disposition()).isEqualTo("HOLD");
        assertThat(row.regionCode()).isEqualTo("1111051500");
        assertThat(row.temporalStatus()).isEqualTo(abolished.endsWith("30") ? "PENDING" : "BOUNDARY_MISMATCH");
    }

    @Test void differentSourceFingerprintIsRejectedBeforeParsing() throws Exception {
        var source = source("STORE_SNAPSHOT"); var s = snapshot(source);
        var wrong = copy(s, s.nodes(), s.sources(), s.mappings(), "b".repeat(64));
        assertThatThrownBy(() -> new NationalRowValidator(new ValidationReference(wrong), source)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ambiguousMappingsAreNotGuessed() throws Exception {
        var source = source("STORE_SNAPSHOT"); var s = snapshot(source);
        var list = new java.util.ArrayList<>(s.mappings()); list.add(s.mappings().getLast());
        var ref = new ValidationReference(copy(s, s.nodes(), s.sources(), list, s.classificationSourceSha256()));
        assertThat(ref.resolve("SEMAS_STORE", "11110515", "semas-test-v1", LocalDate.parse("2026-09-30")).regionCode()).isNull();
    }
}
