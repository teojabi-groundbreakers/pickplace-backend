package com.pickplace.backend.region;

import java.math.BigDecimal;

public record RegionMapLocation(String regionCode, String regionName, Status status, Reason reasonCode,
		Center center, String crs, BigDecimal areaSquareMeters, Source source) {

	public enum Status { AVAILABLE, UNAVAILABLE }

	public enum Reason {
		COORDINATES_NOT_PUBLISHED, COORDINATE_NOT_FOUND, MISSING_BOUNDARY, DUPLICATE_BOUNDARY,
		INVALID_BOUNDARY, REGION_MISMATCH
	}

	public record Center(BigDecimal latitude, BigDecimal longitude) {
	}

	public record Source(String boundaryDate, String sourceUrl, String licenseUrl, String attribution,
			String calculationMethod) {
	}
}
