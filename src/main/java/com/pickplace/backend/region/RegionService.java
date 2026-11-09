package com.pickplace.backend.region;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pickplace.backend.common.error.ApiException;
import com.pickplace.backend.common.error.ErrorCode;
import com.pickplace.backend.common.response.ErrorDetail;

// 게시된 전국 지역 기준정보로 시·도 → 그룹 → 읍면동 목록을 만든다. 코드 형식 검사는 Controller가 먼저 한다.
// 읽기 전용 트랜잭션 하나로 묶어 한 요청의 쿼리들이 같은 DB 시점의 자료를 보게 한다.
@Service
@Transactional(readOnly = true)
public class RegionService {

	private static final String APPROVED = "APPROVED";
	private static final String PROVINCE_CODE_FIELD = "provinceCode";
	private static final String GROUP_CODE_FIELD = "groupCode";
	private static final String REASON_NOT_FOUND = "NOT_FOUND";

	private final RegionQueryRepository regionQueryRepository;

	public RegionService(RegionQueryRepository regionQueryRepository) {
		this.regionQueryRepository = regionQueryRepository;
	}

	// 시·도 목록을 코드순으로 돌려준다.
	public RegionList getProvinces() {
		PublishedRegionRelease release = currentRelease();
		return new RegionList(release, regionQueryRepository.findProvinces(release.releaseId()));
	}

	// 시·도에 속한 화면 그룹 목록을 이름순으로 돌려준다. 화면에 보이는 시·도가 아니면 404다.
	public RegionList getGroups(String provinceCode) {
		return getChildren(provinceCode, PROVINCE_CODE_FIELD, SelectionLevel.PROVINCE, SelectionLevel.GROUP);
	}

	// 그룹에 속한 읍·면·동 목록을 이름순으로 돌려준다. 화면에 보이는 그룹이 아니면 404다.
	public RegionList getLocalities(String groupCode) {
		return getChildren(groupCode, GROUP_CODE_FIELD, SelectionLevel.GROUP, SelectionLevel.LOCALITY);
	}

	// 부모가 현재 게시본에서 요청한 단계로 보이는지 확인한 뒤 그 하위 목록을 돌려준다.
	private RegionList getChildren(String parentCode, String parentField, SelectionLevel parentLevel, SelectionLevel childLevel) {
		// 게시 버전은 여기서 한 번만 읽고 아래 두 쿼리에 같은 releaseId를 넘긴다.
		PublishedRegionRelease release = currentRelease();
		// 하위 0개인 정상 부모(빈 목록)와 없는·말소된·역할이 다른 부모(404)를 구분하려면 부모를 먼저 확인해야 한다.
		// 부모가 숨겨졌는데 자식만 보이는 경우는 게시 전 검증(NationalReferenceValidator)이 막는다.
		if (!regionQueryRepository.existsSelectableRegion(release.releaseId(), parentCode, parentLevel)) {
			throw new ApiException(ErrorCode.REGION_NOT_FOUND, List.of(new ErrorDetail(parentField, REASON_NOT_FOUND)));
		}
		return new RegionList(release, regionQueryRepository.findChildren(release.releaseId(), childLevel, parentCode));
	}

	// 지금 게시된 릴리스를 돌려준다. 아직 게시 전이면 503 REFERENCE_DATA_NOT_READY다.
	private PublishedRegionRelease currentRelease() {
		PublishedRegionRelease release = regionQueryRepository.findPublishedRelease()
				.orElseThrow(() -> new ApiException(ErrorCode.REFERENCE_DATA_NOT_READY));
		// 아래 두 경우는 DB 제약과 게시 절차상 일어나면 안 되는 내부 정합성 오류라 500으로 알린다.
		if (!APPROVED.equals(release.releaseStatus())) {
			throw new IllegalStateException("게시 포인터가 승인되지 않은 릴리스를 가리킵니다. releaseId=" + release.releaseId());
		}
		if (release.sourceFileVersion() == null) {
			throw new IllegalStateException("게시 릴리스에 공식 이력 파일 정보가 없습니다. releaseId=" + release.releaseId());
		}
		return release;
	}

}
