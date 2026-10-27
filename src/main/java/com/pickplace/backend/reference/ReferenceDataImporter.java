package com.pickplace.backend.reference;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferenceDataImporter {

	private final JdbcTemplate jdbc;
	private final ReferenceSnapshotValidator validator;

	public ReferenceDataImporter(JdbcTemplate jdbc, ReferenceSnapshotValidator validator) {
		this.jdbc = jdbc;
		this.validator = validator;
	}

	@Transactional
	public ImportResult importSnapshot(ReferenceSnapshot snapshot) {
		validator.validate(snapshot);
		requireVersion("district", "data_version", snapshot.version());
		requireVersion("administrative_dong", "region_version", snapshot.version());
		requireVersion("business_type", "data_version", snapshot.version());
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		int districts = insertDistricts(snapshot, now);
		int dongs = insertDongs(snapshot, now);
		int types = insertBusinessTypes(snapshot, now);
		return new ImportResult(districts, dongs, types);
	}

	private int insertDistricts(ReferenceSnapshot snapshot, LocalDateTime now) {
		var existing = index(jdbc.query("SELECT * FROM district", (rs, number) -> new ReferenceSnapshot.District(
				rs.getString("district_code"), rs.getString("district_name"),
				rs.getObject("collected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))), ReferenceSnapshot.District::code);
		var fresh = missing(snapshot.districts(), existing, ReferenceSnapshot.District::code);
		jdbc.batchUpdate("""
				INSERT INTO district (district_code, district_name, data_version, collected_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""", fresh, 100, (ps, row) -> {
			ps.setString(1, row.code()); ps.setString(2, row.name()); ps.setString(3, snapshot.version());
			timestamps(ps, 4, row.collectedAt(), now);
		});
		return fresh.size();
	}

	private int insertDongs(ReferenceSnapshot snapshot, LocalDateTime now) {
		int otherDates = jdbc.queryForObject("""
				SELECT COUNT(*) FROM administrative_dong WHERE NOT (reference_snapshot_on <=> ?)
				""", Integer.class, snapshot.officialSnapshotOn());
		if (otherDates != 0) throw new IllegalStateException("Official reference snapshot changed; review migration first");
		var existing = index(jdbc.query("SELECT * FROM administrative_dong", (rs, number) -> new ReferenceSnapshot.Dong(
				rs.getString("dong_code"), rs.getString("dong_name"), rs.getString("district_code"),
				rs.getString("official_dong_code"), rs.getString("official_dong_name"), rs.getString("official_code_status"),
				rs.getObject("official_created_on", LocalDate.class), rs.getObject("official_abolished_on", LocalDate.class),
				rs.getObject("collected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))), ReferenceSnapshot.Dong::code);
		var fresh = missing(snapshot.dongs(), existing, ReferenceSnapshot.Dong::code);
		jdbc.batchUpdate("""
				INSERT INTO administrative_dong (dong_code, dong_name, district_code, region_version,
				    official_dong_code, official_dong_name, official_code_status, official_created_on,
				    official_abolished_on, reference_snapshot_on, collected_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", fresh, 100, (ps, row) -> {
			ps.setString(1, row.code()); ps.setString(2, row.name()); ps.setString(3, row.districtCode());
			ps.setString(4, snapshot.version()); ps.setString(5, row.officialCode()); ps.setString(6, row.officialName());
			ps.setString(7, row.status()); ps.setObject(8, row.createdOn()); ps.setObject(9, row.abolishedOn());
			ps.setObject(10, snapshot.officialSnapshotOn()); timestamps(ps, 11, row.collectedAt(), now);
		});
		return fresh.size();
	}

	private int insertBusinessTypes(ReferenceSnapshot snapshot, LocalDateTime now) {
		var existing = index(jdbc.query("SELECT * FROM business_type", (rs, number) -> new ReferenceSnapshot.BusinessType(
				rs.getString("business_type_code"), rs.getString("source_business_type_name"),
				rs.getString("display_name"), rs.getBoolean("is_supported"), rs.getInt("display_order"),
				rs.getObject("collected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))), ReferenceSnapshot.BusinessType::code);
		if (!snapshot.businessTypes().stream().map(ReferenceSnapshot.BusinessType::code).collect(Collectors.toSet()).containsAll(existing.keySet())) {
			throw new IllegalStateException("Business codes outside reviewed snapshot");
		}
		var fresh = snapshot.businessTypes().stream().filter(row -> {
			var old = existing.get(row.code());
			if (old != null && (!old.sourceName().equals(row.sourceName()) || !old.collectedAt().equals(row.collectedAt()))) {
				throw new IllegalStateException("Source business type changed: " + row.code());
			}
			return old == null;
		}).toList();
		jdbc.batchUpdate("""
				INSERT INTO business_type (business_type_code, source_business_type_name, display_name,
				    is_supported, display_order, data_version, collected_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", fresh, 100, (ps, row) -> {
			ps.setString(1, row.code()); ps.setString(2, row.sourceName()); ps.setString(3, row.displayName());
			ps.setBoolean(4, row.supported()); ps.setInt(5, row.displayOrder()); ps.setString(6, snapshot.version());
			timestamps(ps, 7, row.collectedAt(), now);
		});
		return fresh.size();
	}

	private void requireVersion(String table, String column, String version) {
		if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " <> ?", Integer.class, version) != 0) {
			throw new IllegalStateException("Existing " + table + " uses another source version; review migration first");
		}
	}

	private <T> Map<String, T> index(List<T> rows, Function<T, String> code) {
		return rows.stream().collect(Collectors.toMap(code, Function.identity()));
	}

	private <T> List<T> missing(List<T> rows, Map<String, T> existing, Function<T, String> code) {
		if (!rows.stream().map(code).collect(Collectors.toSet()).containsAll(existing.keySet())) {
			throw new IllegalStateException("Codes outside reviewed snapshot");
		}
		return rows.stream().filter(row -> {
			T old = existing.get(code.apply(row));
			if (old != null && !old.equals(row)) throw new IllegalStateException("Source changed: " + code.apply(row));
			return old == null;
		}).toList();
	}

	private void timestamps(PreparedStatement ps, int start, Instant collectedAt, LocalDateTime now) throws SQLException {
		ps.setObject(start, LocalDateTime.ofInstant(collectedAt, ZoneOffset.UTC));
		ps.setObject(start + 1, now); ps.setObject(start + 2, now);
	}

	public record ImportResult(int districtsInserted, int dongsInserted, int businessTypesInserted) {}
}
