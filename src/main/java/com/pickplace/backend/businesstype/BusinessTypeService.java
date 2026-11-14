package com.pickplace.backend.businesstype;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ErrorDetail;

// 게시된 지역 릴리스에 연결된 승인 업종 분류에서 지원 소분류 목록을 만든다.
// 읽기 전용 트랜잭션 하나로 묶어 한 요청의 쿼리들이 같은 DB 시점의 자료를 보게 한다.
@Service
@Transactional(readOnly = true)
public class BusinessTypeService {

	private static final String APPROVED = "APPROVED";
	private static final String BUSINESS_TYPE_CODE_FIELD = "businessTypeCode";
	private static final String REASON_NOT_FOUND = "NOT_FOUND";
	private static final String REASON_NOT_SUPPORTED = "NOT_SUPPORTED";

	private final BusinessTypeQueryRepository businessTypeQueryRepository;

	public BusinessTypeService(BusinessTypeQueryRepository businessTypeQueryRepository) {
		this.businessTypeQueryRepository = businessTypeQueryRepository;
	}

	// 화면에서 선택할 수 있는 지원 업종 목록을 표시 순서대로 돌려준다.
	// 지원 여부를 바꾸면 다음 요청부터 바로 반영되도록 결과를 따로 저장해 두지 않고 매번 DB를 읽는다.
	public List<BusinessTypeItem> getSupportedBusinessTypes() {
		// 분류 번호는 여기서 한 번만 정하고 아래 조회에 그대로 넘긴다.
		long classificationId = currentClassificationId();
		return businessTypeQueryRepository.findSupportedBusinessTypes(classificationId);
	}

	// 현재 승인 분류의 지원 소분류이면 그 항목을 돌려준다. 분류에 없으면 404, 등록됐지만 지원하지 않으면 422다.
	// 다른 기능(추이 조회 등)이 업종 확인 규칙을 다시 만들지 않고 이 확인을 그대로 쓰게 하려고 공개한다.
	public BusinessTypeItem getSupportedBusinessType(String businessTypeCode) {
		BusinessTypeItem item = businessTypeQueryRepository.findSmallBusinessType(currentClassificationId(), businessTypeCode)
				.orElseThrow(() -> new ApiException(ErrorCode.BUSINESS_TYPE_NOT_FOUND,
						List.of(new ErrorDetail(BUSINESS_TYPE_CODE_FIELD, REASON_NOT_FOUND))));
		if (!item.supported()) {
			throw new ApiException(ErrorCode.BUSINESS_TYPE_NOT_SUPPORTED,
					List.of(new ErrorDetail(BUSINESS_TYPE_CODE_FIELD, REASON_NOT_SUPPORTED)));
		}
		return item;
	}

	// 지금 게시본에 연결된 승인 업종 분류 번호를 돌려준다. 아직 게시 전이면 503 REFERENCE_DATA_NOT_READY다.
	// 연결이 깨졌을 때 가장 최근 분류본 같은 다른 분류로 대신하지 않는다. 잘못된 업종 목록을 보여주게 되기 때문이다.
	private long currentClassificationId() {
		PublishedClassification published = businessTypeQueryRepository.findPublishedClassification()
				.orElseThrow(() -> new ApiException(ErrorCode.REFERENCE_DATA_NOT_READY));
		// 아래 경우들은 승인·게시 명령이 미리 막으므로 일어나면 안 되는 내부 정합성 오류라 500으로 알린다.
		if (!APPROVED.equals(published.releaseStatus())) {
			throw new IllegalStateException("게시 포인터가 승인되지 않은 릴리스를 가리킵니다. releaseId=" + published.releaseId());
		}
		Long classificationId = published.classificationId();
		if (classificationId == null) {
			throw new IllegalStateException("게시 릴리스에 업종 분류 연결이 없습니다. releaseId=" + published.releaseId());
		}
		String classificationStatus = businessTypeQueryRepository.findClassificationStatus(classificationId)
				.orElseThrow(() -> new IllegalStateException(
						"게시 릴리스가 없는 업종 분류를 가리킵니다. classificationId=" + classificationId));
		if (!APPROVED.equals(classificationStatus)) {
			throw new IllegalStateException("게시 릴리스의 업종 분류가 승인 상태가 아닙니다. classificationId=" + classificationId);
		}
		return classificationId;
	}

}
