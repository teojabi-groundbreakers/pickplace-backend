package com.pickplace.backend.reference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReferenceSnapshot(
		@NotBlank @Size(max = 100) String version,
		@NotNull LocalDate officialSnapshotOn,
		@NotNull @Size(min = 25, max = 25) List<@NotNull @Valid District> districts,
		@NotNull @Size(min = 425, max = 425) List<@NotNull @Valid Dong> dongs,
		@NotNull @Size(min = 100, max = 100) List<@NotNull @Valid BusinessType> businessTypes) {

	public record District(
			@NotNull @Pattern(regexp = "[0-9]{5}") String code,
			@NotBlank @Size(max = 100) String name,
			@NotNull Instant collectedAt) {}

	public record Dong(
			@NotNull @Pattern(regexp = "[0-9]{8}") String code,
			@NotBlank @Size(max = 100) String name,
			@NotNull @Pattern(regexp = "[0-9]{5}") String districtCode,
			@NotNull @Pattern(regexp = "[0-9]{10}") String officialCode,
			@NotBlank @Size(max = 100) String officialName,
			@NotNull @Pattern(regexp = "CURRENT|LEGACY") String status,
			LocalDate createdOn,
			LocalDate abolishedOn,
			@NotNull Instant collectedAt) {}

	public record BusinessType(
			@NotNull @Pattern(regexp = "CS[0-9]{6}") String code,
			@NotBlank @Size(max = 100) String sourceName,
			@NotBlank @Size(max = 100) String displayName,
			boolean supported,
			@Min(0) int displayOrder,
			@NotNull Instant collectedAt) {}
}
