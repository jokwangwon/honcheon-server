package com.honcheon.bot;

import java.util.List;

/** 세력 정치가 읽고 갱신하는 명분 원장 행. */
record MyeongbunIssue(String issue, String target, List<String> victims, List<String> tags,
                      int rawGauge, int originAccuracy, String originRumor, String trueTarget,
                      int createdDay, int updatedDay) {
}
