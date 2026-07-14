package com.honcheon.bot;

import java.util.Map;
import java.util.Optional;

/** 디스코드 캐릭터와 MVT 몸의 접합 및 혈채 연결 포트. */
interface IdentityStore {
    Optional<Long> characterOfMc(String mcUuid) throws Exception;

    Map<String, Long> linkedBodies() throws Exception;

    Optional<String> mcOfCharacter(long characterId) throws Exception;

    Optional<String> mcName(String mcUuid) throws Exception;

    Optional<Long> rawCharacterOfMc(String mcUuid) throws Exception;

    void unlinkMc(String mcUuid) throws Exception;

    void linkMvt(String mcUuid, String mcName, Long characterId) throws Exception;

    Optional<LinkRequest> lastLinkRequestTo(String mcUuid) throws Exception;

    void pendLinkRequest(String token, String mcUuid, String mcName, long characterId,
                         String discordId, String discordName, long issuedAt, long expiresAt)
            throws Exception;

    BloodDebtEntry bloodDebtOf(long characterId) throws Exception;

    BloodDebtEntry mergeBloodDebt(String from, long characterId, int day) throws Exception;

    void setExposureFloor(String subject, Long characterId, double floor, int day)
            throws Exception;
}
