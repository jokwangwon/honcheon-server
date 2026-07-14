package com.honcheon.bot;

/** 디스코드가 청하고 마크의 몸이 답하는 접합 요청 한 장. */
record LinkRequest(String token, String mcUuid, String mcName, long characterId,
                   String discordId, String discordName, long issuedAt, long expiresAt,
                   String state) {
    boolean expired(long now) {
        return now > expiresAt;
    }

    boolean pending() {
        return "대기".equals(state);
    }
}
