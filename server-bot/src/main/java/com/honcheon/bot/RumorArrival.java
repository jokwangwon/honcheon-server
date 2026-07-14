package com.honcheon.bot;

/** 소문 한 건이 특정 망에 도달하는 시점과 정확도. */
record RumorArrival(String network, int day, int accuracy) {
}
