package com.honcheon.bot;

/** 혈채 원장의 백엔드 중립 행. knownRaw는 저장값이며 감쇠 판정은 규칙층의 몫이다. */
record BloodDebtEntry(String subject, Long characterId, double hidden, double knownRaw,
                      int knownDay, int publicCount, int kills, double exposureFloor) {
    static BloodDebtEntry empty(String subject) {
        return new BloodDebtEntry(subject, null, 0, 0, 0, 0, 0, 0);
    }
}
