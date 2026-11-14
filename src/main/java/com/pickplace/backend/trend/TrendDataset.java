package com.pickplace.backend.trend;

import java.util.Arrays;
import java.util.Optional;

// 추이를 조회할 수 있는 상세 통계 자료다. 공개 이름(datasetCode)과 적재 배치의 원천·자료 이름을 짝지어 둔다.
// 서울 점포·매출은 서울 CS 업종, 전국 수록 업소는 소진공 소분류 업종, 나머지는 업종 없이 저장돼 있다.
public enum TrendDataset {

	// 직장인구·시설은 분기마다 새로 관측된 값인지 확인할 수 없어(이전 분기 값의 재사용 가능) 증감을 계산하지 않는다.
	STORE(SourceSystem.SEOUL_COMMERCIAL, "STORE", IndustryKind.SEOUL_CS, true),
	SALES(SourceSystem.SEOUL_COMMERCIAL, "SALES", IndustryKind.SEOUL_CS, true),
	WORKER(SourceSystem.SEOUL_COMMERCIAL, "WORKER", IndustryKind.NONE, false),
	FACILITY(SourceSystem.SEOUL_COMMERCIAL, "FACILITY", IndustryKind.NONE, false),
	FLOW(SourceSystem.SEOUL_COMMERCIAL, "FLOW", IndustryKind.NONE, true),
	LISTED_STORE(SourceSystem.SEMAS_STORE, "STORE_SNAPSHOT", IndustryKind.SEMAS, true);

	private final SourceSystem sourceSystem;
	private final String storedDatasetCode;
	private final IndustryKind industryKind;
	private final boolean changesComparable;

	TrendDataset(SourceSystem sourceSystem, String storedDatasetCode, IndustryKind industryKind, boolean changesComparable) {
		this.sourceSystem = sourceSystem;
		this.storedDatasetCode = storedDatasetCode;
		this.industryKind = industryKind;
		this.changesComparable = changesComparable;
	}

	// 대소문자 변환 없이 정확히 같은 이름의 자료를 찾는다.
	static Optional<TrendDataset> find(String code) {
		return Arrays.stream(values()).filter(dataset -> dataset.name().equals(code)).findFirst();
	}

	// 적재 배치의 source_system 값이다.
	String sourceSystem() {
		return sourceSystem.name();
	}

	// 적재 배치의 dataset_code 값이다. 공개 이름과 다를 수 있다(LISTED_STORE → STORE_SNAPSHOT).
	String storedDatasetCode() {
		return storedDatasetCode;
	}

	// 분기 간 증감을 계산해도 되는 자료인지 알려준다. false면 값은 주되 증감은 비교 불가로 둔다.
	boolean changesComparable() {
		return changesComparable;
	}

	// 이 자료가 어떤 업종 체계로 저장돼 있는지 알려준다.
	IndustryKind industryKind() {
		return industryKind;
	}

	// 상세 통계의 원천이다. 원천마다 지역·업종 코드 체계가 다르므로 섞어 읽지 않는다.
	enum SourceSystem {
		SEOUL_COMMERCIAL, SEMAS_STORE
	}

	// 업종 체계와 그 업종 코드를 받는 쿼리 파라미터 이름, 적재 배치에 기록돼 있어야 하는 체계 이름이다.
	// 두 체계를 서버가 서로 바꿔 찾지 않도록 파라미터 이름부터 나눈다.
	enum IndustryKind {
		NONE(null, "NONE"),
		SEOUL_CS("industryCode", "SEOUL_CS100"),
		SEMAS("businessTypeCode", "SEMAS_SMALL");

		private final String parameter;
		private final String scheme;

		IndustryKind(String parameter, String scheme) {
			this.parameter = parameter;
			this.scheme = scheme;
		}

		// 업종 코드를 받는 쿼리 파라미터 이름이다. 업종이 없는 자료는 null이다.
		String parameter() {
			return parameter;
		}

		// 적재 배치의 industry_scheme 값이다.
		String scheme() {
			return scheme;
		}
	}

}
