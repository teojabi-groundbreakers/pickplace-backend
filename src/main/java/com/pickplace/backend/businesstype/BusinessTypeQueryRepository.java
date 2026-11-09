package com.pickplace.backend.businesstype;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 전국 업종 분류(소진공 SEMAS, V4 테이블)를 읽기만 하는 조회 전용 저장소다.
// 업종에는 따로 게시 포인터가 없어서, 지역 게시본의 검증 보고서에 적힌 분류 번호를 따라간다.
@Repository
public class BusinessTypeQueryRepository {

	// 전국 지역 선택 목록의 게시 포인터 이름이다. 업종 분류도 이 게시본에 연결된 것을 쓴다.
	private static final String PUBLICATION_KEY = "NATIONWIDE_SELECTION";

	// 적재 명령이 검증 보고서(JSON)에 업종 분류 번호를 이 이름으로 적는다.
	private static final String CLASSIFICATION_ID_FIELD = "classificationId";

	// 게시 포인터 → 릴리스 상태·검증 보고서를 읽는다. 게시 포인터가 없으면 0행이다.
	private static final String PUBLISHED_RELEASE_SQL = """
			SELECT p.release_id, r.status, r.validation_report
			FROM region_publication p
			JOIN region_release r ON r.release_id = p.release_id
			WHERE p.publication_key = ?
			""";

	private static final String CLASSIFICATION_STATUS_SQL =
			"SELECT status FROM national_industry_release WHERE classification_id = ?";

	// 화면에서 고를 수 있는 업종은 소분류이면서 승인된 지원 표시가 있는 것뿐이다.
	// 이름은 원천명(industry_name)이 아니라 화면 표시명(display_name)을 쓴다.
	private static final String SUPPORTED_SQL = """
			SELECT industry_code, display_name, is_supported
			FROM national_industry_code
			WHERE classification_id = ?
			  AND industry_level = 'SMALL'
			  AND is_supported = TRUE
			  AND support_release_status = 'APPROVED'
			ORDER BY display_order, industry_code
			""";

	private final JdbcTemplate jdbc;
	private final JsonMapper jsonMapper;

	public BusinessTypeQueryRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	// 지금 게시된 지역 릴리스와 연결된 업종 분류 번호를 돌려준다. 아직 게시된 적이 없으면 비어 있다.
	public Optional<PublishedClassification> findPublishedClassification() {
		List<PublishedClassification> rows = jdbc.query(PUBLISHED_RELEASE_SQL,
				(rs, rowNum) -> new PublishedClassification(rs.getLong("release_id"), rs.getString("status"),
						classificationId(rs.getString("validation_report"))),
				PUBLICATION_KEY);
		// publication_key가 기본키라 0행 또는 1행이다.
		return rows.stream().findFirst();
	}

	// 업종 분류본의 상태(APPROVED 등)를 돌려준다. 그 번호의 분류본이 없으면 비어 있다.
	public Optional<String> findClassificationStatus(long classificationId) {
		return jdbc.queryForList(CLASSIFICATION_STATUS_SQL, String.class, classificationId).stream().findFirst();
	}

	// 한 분류본의 지원 소분류를 표시 순서 → 코드 순으로 돌려준다.
	public List<BusinessTypeItem> findSupportedBusinessTypes(long classificationId) {
		return jdbc.query(SUPPORTED_SQL, (rs, rowNum) -> new BusinessTypeItem(
				rs.getString("industry_code"), rs.getString("display_name"), rs.getBoolean("is_supported")),
				classificationId);
	}

	// 검증 보고서에서 분류 번호를 꺼낸다. 번호가 없거나 양의 정수가 아니면 null이다.
	// 문자열 "1"이나 1.5 같은 값을 숫자로 바꿔 쓰면 엉뚱한 분류본을 가리킬 수 있으므로 정수만 인정한다.
	private Long classificationId(String validationReport) {
		if (validationReport == null) {
			return null;
		}
		JsonNode id = jsonMapper.readTree(validationReport).path(CLASSIFICATION_ID_FIELD);
		if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
			return null;
		}
		return id.longValue();
	}

}
