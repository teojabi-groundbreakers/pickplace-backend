package com.pickplace.backend.region;

import java.util.List;

// Service가 Controller에 넘기는 조회 결과다. 목록과 함께 어느 게시 버전에서 읽었는지(출처)를 담는다.
public record RegionList(PublishedRegionRelease release, List<RegionItem> items) {
}
