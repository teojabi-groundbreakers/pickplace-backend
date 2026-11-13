package com.pickplace.backend.trend;

import java.util.ArrayList;
import java.util.List;

// 분기 코드(YYYYQ, 예: 20262)를 다루는 도우미다. 분기를 월로 나누거나 다른 분기로 바꾸지 않는다.
final class Quarters {

	// 한 번에 조회할 수 있는 최대 분기 수다. 현재 게시 범위인 최근 2년(8분기)에 맞춘다.
	static final int MAX_QUARTERS = 8;

	// 연도는 1000 이상으로 받는다. 0으로 시작하는 연도는 분기를 만들 때 자릿수가 줄어 원래 코드와 달라지기 때문이다.
	private static final String QUARTER_PATTERN = "[1-9][0-9]{3}[1-4]";

	private Quarters() {
	}

	// 숫자 4자리 연도 + 1~4 분기 형식인지 확인한다. 공백·다른 자리수는 고쳐 쓰지 않고 틀린 형식으로 본다.
	static boolean isValid(String quarter) {
		return quarter != null && quarter.matches(QUARTER_PATTERN);
	}

	// 시작·종료 분기를 포함해 몇 분기인지 센다. 종료가 시작보다 앞이면 0 이하다.
	static int count(String from, String to) {
		return index(to) - index(from) + 1;
	}

	// 시작부터 종료까지 모든 분기를 시간순으로 만든다. 연도가 바뀌면 20244 다음은 20251이다.
	static List<String> between(String from, String to) {
		List<String> quarters = new ArrayList<>();
		for (int i = index(from); i <= index(to); i++) {
			quarters.add(String.valueOf(i / 4) + (i % 4 + 1));
		}
		return quarters;
	}

	// 몇 분기 앞(음수)이나 뒤(양수)의 분기를 돌려준다. 연도 1000 미만으로는 내려가지 않고 1000년 1분기에서 멈춘다.
	static String shift(String quarter, int quarters) {
		int target = Math.max(index(quarter) + quarters, 1000 * 4);
		return String.valueOf(target / 4) + (target % 4 + 1);
	}

	// 분기 코드를 증감 계산기가 쓰는 분기 값으로 바꾼다.
	static QuarterlyChangeCalculator.Quarter toCalculatorQuarter(String quarter) {
		return new QuarterlyChangeCalculator.Quarter(Integer.parseInt(quarter.substring(0, 4)), quarter.charAt(4) - '0');
	}

	// 분기를 연속된 정수로 바꿔 순서 비교와 개수 계산을 쉽게 한다.
	private static int index(String quarter) {
		return Integer.parseInt(quarter.substring(0, 4)) * 4 + (quarter.charAt(4) - '1');
	}

}
