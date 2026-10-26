package com.pickplace.backend.reference;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

@Component
public class ReferenceSnapshotValidator {

	private final Validator validator;

	public ReferenceSnapshotValidator(Validator validator) {
		this.validator = validator;
	}

	public void validate(ReferenceSnapshot snapshot) {
		if (snapshot == null) throw new IllegalArgumentException("Snapshot is required");
		var violations = validator.validate(snapshot);
		if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
		Set<String> districts = uniqueCodes(snapshot.districts(), ReferenceSnapshot.District::code);
		uniqueCodes(snapshot.dongs(), ReferenceSnapshot.Dong::code);
		uniqueCodes(snapshot.businessTypes(), ReferenceSnapshot.BusinessType::code);
		Set<String> usedDistricts = new HashSet<>();
		for (var dong : snapshot.dongs()) {
			if (!districts.contains(dong.districtCode())) {
				throw new IllegalArgumentException("Unknown parent district: " + dong.code());
			}
			usedDistricts.add(dong.districtCode());
			if ("LEGACY".equals(dong.status()) != (dong.abolishedOn() != null)
					|| (dong.createdOn() != null && dong.abolishedOn() != null
						&& dong.createdOn().isAfter(dong.abolishedOn()))) {
				throw new IllegalArgumentException("Invalid official code dates: " + dong.code());
			}
		}
		if (!usedDistricts.equals(districts)) throw new IllegalArgumentException("District without any source dong");
		Set<String> supported = new HashSet<>();
		snapshot.businessTypes().stream().filter(ReferenceSnapshot.BusinessType::supported)
				.forEach(type -> supported.add(type.code()));
		if (!supported.equals(Set.of("CS100010", "CS100008", "CS100005"))) {
			throw new IllegalArgumentException("Initial supported business types must match the reviewed three codes");
		}
	}

	private <T> Set<String> uniqueCodes(List<T> rows, Function<T, String> code) {
		Set<String> result = new HashSet<>();
		for (T row : rows) {
			if (!result.add(code.apply(row))) throw new IllegalArgumentException("Duplicate code: " + code.apply(row));
		}
		return result;
	}
}
