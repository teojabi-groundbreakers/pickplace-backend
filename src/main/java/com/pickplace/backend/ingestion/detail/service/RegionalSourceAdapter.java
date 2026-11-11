package com.pickplace.backend.ingestion.detail.service;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/** 다른 제공처를 추가할 때 파싱만 구현하고 배치·품질·게시 코드는 재사용한다. */
public interface RegionalSourceAdapter {
    String id();

    void read(DetailInputLoader.Input input, Consumer<Map<String, Object>> row) throws IOException;
}
