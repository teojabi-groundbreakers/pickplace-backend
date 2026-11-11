package com.pickplace.backend.ingestion.detail.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DetailLocalityNameTests {
    @ParameterizedTest
    @CsvSource({
        "명일1동,명일제1동,true",
        "홍제1동,홍제제1동,true",
        "홍제2동,홍제제2동,true",
        "면목3·8동,면목제3.8동,true",
        "금호2·3가동,금호2.3가동,true",
        "종로1·2·3·4가동,종로1.2.3.4가동,true",
        "청운효자동,다른동,false",
        "명일1동,명일2동,false",
        "상일동,상일1동,false",
        "홍제1동,홍1동,false"
    })
    void allowsOnlyDocumentedNotationVariants(String first, String second, boolean same) {
        assertThat(DetailRowValidator.sameLocalityName(first, second)).isEqualTo(same);
        assertThat(DetailRowValidator.sameLocalityName(second, first)).isEqualTo(same);
    }
}
