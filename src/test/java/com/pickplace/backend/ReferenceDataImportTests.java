package com.pickplace.backend;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;

import com.pickplace.backend.reference.ReferenceDataImporter;
import com.pickplace.backend.reference.ReferenceSnapshot;
import com.pickplace.backend.reference.ReferenceSnapshotLoader;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class ReferenceDataImportTests {

	@Autowired
	private ReferenceDataImporter importer;
	@Autowired
	private ReferenceSnapshotLoader loader;
	@Autowired
	private JdbcTemplate jdbc;
	private ReferenceSnapshot snapshot;

	@BeforeEach
	void prepare() {
		clearIsolatedTables();
		snapshot = loader.load();
	}

	@AfterEach
	void cleanup() {
		clearIsolatedTables();
	}

	@Test
	void normalServerStartupDoesNotImportData() {
		assertCounts(0, 0, 0);
	}

	@Test
	void importsAllReviewedCodesAndOfficialMappings() {
		assertThat(importer.importSnapshot(snapshot)).isEqualTo(new ReferenceDataImporter.ImportResult(25, 425, 100));
		assertCounts(25, 425, 100);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM administrative_dong WHERE official_code_status = 'CURRENT'", Integer.class))
				.isEqualTo(422);
		assertThat(jdbc.queryForList("SELECT dong_code FROM administrative_dong WHERE official_code_status = 'LEGACY'", String.class))
				.containsExactlyInAnyOrder("11230536", "11740520", "11680740");
		assertThat(jdbc.queryForList("SELECT business_type_code FROM business_type WHERE is_supported = TRUE", String.class))
				.containsExactlyInAnyOrder("CS100010", "CS100008", "CS100005");
		for (var dong : snapshot.dongs()) {
			assertThat(jdbc.queryForMap("""
					SELECT district_code, official_dong_code, official_dong_name FROM administrative_dong WHERE dong_code = ?
					""", dong.code())).containsAllEntriesOf(Map.of("district_code", dong.districtCode(),
							"official_dong_code", dong.officialCode(), "official_dong_name", dong.officialName()));
		}
		var district = snapshot.districts().getFirst();
		assertThat(jdbc.queryForObject("SELECT collected_at FROM district WHERE district_code = ?", LocalDateTime.class, district.code()))
				.isEqualTo(LocalDateTime.ofInstant(district.collectedAt(), java.time.ZoneOffset.UTC));
	}

	@Test
	void repeatedImportKeepsEveryRowTimestampAndServiceSetting() {
		importer.importSnapshot(snapshot);
		jdbc.update("UPDATE business_type SET display_name = '팀 표시명', is_supported = FALSE, display_order = 99 WHERE business_type_code = 'CS100010'");
		var districtsBefore = jdbc.queryForList("SELECT * FROM district ORDER BY district_code");
		var dongsBefore = jdbc.queryForList("SELECT * FROM administrative_dong ORDER BY dong_code");
		var typesBefore = jdbc.queryForList("SELECT * FROM business_type ORDER BY business_type_code");
		assertThat(importer.importSnapshot(snapshot)).isEqualTo(new ReferenceDataImporter.ImportResult(0, 0, 0));
		assertThat(importer.importSnapshot(snapshot)).isEqualTo(new ReferenceDataImporter.ImportResult(0, 0, 0));
		assertThat(jdbc.queryForList("SELECT * FROM district ORDER BY district_code")).isEqualTo(districtsBefore);
		assertThat(jdbc.queryForList("SELECT * FROM administrative_dong ORDER BY dong_code")).isEqualTo(dongsBefore);
		assertThat(jdbc.queryForList("SELECT * FROM business_type ORDER BY business_type_code")).isEqualTo(typesBefore);
	}

	@Test
	void rejectsDuplicateCodesBeforeWriting() {
		var dongs = new ArrayList<>(snapshot.dongs());
		dongs.set(1, dongs.getFirst());
		assertThatThrownBy(() -> importer.importSnapshot(withDongs(dongs))).isInstanceOf(IllegalArgumentException.class);
		assertCounts(0, 0, 0);
	}

	@Test
	void rejectsUnknownParentAndMissingSourceNamesBeforeWriting() {
		var old = snapshot.dongs().getFirst();
		var dongs = new ArrayList<>(snapshot.dongs());
		dongs.set(0, new ReferenceSnapshot.Dong(old.code(), old.name(), "99999", old.officialCode(), old.officialName(),
				old.status(), old.createdOn(), old.abolishedOn(), old.collectedAt()));
		assertThatThrownBy(() -> importer.importSnapshot(withDongs(dongs))).isInstanceOf(IllegalArgumentException.class);
		var districts = new ArrayList<>(snapshot.districts());
		var district = districts.getFirst();
		districts.set(0, new ReferenceSnapshot.District(district.code(), " ", district.collectedAt()));
		assertThatThrownBy(() -> importer.importSnapshot(new ReferenceSnapshot(snapshot.version(), snapshot.officialSnapshotOn(),
				districts, snapshot.dongs(), snapshot.businessTypes()))).isInstanceOf(ConstraintViolationException.class);
		assertCounts(0, 0, 0);
	}

	@Test
	void rejectsInvalidLegacyMetadataBeforeWriting() {
		var dongs = new ArrayList<>(snapshot.dongs());
		var old = dongs.getFirst();
		dongs.set(0, new ReferenceSnapshot.Dong(old.code(), old.name(), old.districtCode(), old.officialCode(), old.officialName(),
				"LEGACY", old.createdOn(), null, old.collectedAt()));
		assertThatThrownBy(() -> importer.importSnapshot(withDongs(dongs))).isInstanceOf(IllegalArgumentException.class);
		assertCounts(0, 0, 0);
	}

	@Test
	void abortsInsteadOfOverwritingChangedRegionOrSourceVersion() {
		importer.importSnapshot(snapshot);
		jdbc.update("UPDATE administrative_dong SET district_code = '11140' WHERE dong_code = '11110515'");
		assertThatThrownBy(() -> importer.importSnapshot(snapshot)).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT district_code FROM administrative_dong WHERE dong_code = '11110515'", String.class))
				.isEqualTo("11140");
		jdbc.update("UPDATE administrative_dong SET district_code = '11110' WHERE dong_code = '11110515'");
		jdbc.update("UPDATE district SET data_version = 'another-version' WHERE district_code = '11110'");
		assertThatThrownBy(() -> importer.importSnapshot(snapshot)).isInstanceOf(IllegalStateException.class);
		assertCounts(25, 425, 100);
	}

	@Test
	void rollsBackEarlierInsertsWhenBusinessSourceConflictIsFound() {
		importer.importSnapshot(snapshot);
		jdbc.update("DELETE FROM administrative_dong");
		jdbc.update("DELETE FROM district");
		jdbc.update("UPDATE business_type SET source_business_type_name = '충돌 원천명' WHERE business_type_code = 'CS100010'");
		assertThatThrownBy(() -> importer.importSnapshot(snapshot)).isInstanceOf(IllegalStateException.class);
		assertCounts(0, 0, 100);
		assertThat(jdbc.queryForObject("SELECT source_business_type_name FROM business_type WHERE business_type_code = 'CS100010'", String.class))
				.isEqualTo("충돌 원천명");
	}

	@Test
	void fillsMissingRowsWithoutRecreatingExistingOnes() {
		importer.importSnapshot(snapshot);
		jdbc.update("DELETE FROM administrative_dong WHERE dong_code = '11110515'");
		jdbc.update("DELETE FROM business_type WHERE business_type_code = 'CS100010'");
		assertThat(importer.importSnapshot(snapshot)).isEqualTo(new ReferenceDataImporter.ImportResult(0, 1, 1));
		assertCounts(25, 425, 100);
	}

	private ReferenceSnapshot withDongs(java.util.List<ReferenceSnapshot.Dong> dongs) {
		return new ReferenceSnapshot(snapshot.version(), snapshot.officialSnapshotOn(), snapshot.districts(), dongs, snapshot.businessTypes());
	}

	private void assertCounts(int districts, int dongs, int types) {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM district", Integer.class)).isEqualTo(districts);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM administrative_dong", Integer.class)).isEqualTo(dongs);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_type", Integer.class)).isEqualTo(types);
	}

	private void clearIsolatedTables() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pickplace_test");
		jdbc.update("DELETE FROM administrative_dong");
		jdbc.update("DELETE FROM business_type");
		jdbc.update("DELETE FROM district");
	}
}
