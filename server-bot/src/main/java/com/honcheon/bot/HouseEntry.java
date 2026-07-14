package com.honcheon.bot;

/** 세계에 실재하는 한 채의 집과 누적 출생 수. */
record HouseEntry(long id, String family, String name, String region, String state, int born) {
}
