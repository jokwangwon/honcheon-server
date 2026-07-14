package com.honcheon.bot;

import com.honcheon.domain.FactionLedger;
import com.honcheon.domain.RegionLedger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** MVT 사건 수신과 세계 스냅숏 발행이 요구하는 영속화 포트. */
interface BridgeStore extends FactionLedger, RegionLedger, WorldMetaReader {
    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    void setMeta(String key, String value) throws Exception;

    boolean applyBridgeEvent(String eventId, String kind, String cursorKey,
                             String checkpoint, Work work) throws Exception;

    int worldDay() throws Exception;

    void linkMvt(String mcUuid, String mcName, Long characterId) throws Exception;

    List<LinkRequest> livingLinkRequests(long now) throws Exception;

    Optional<LinkRequest> linkRequest(String token) throws Exception;

    boolean burnLinkRequest(String token, String state, long now, int day) throws Exception;

    Optional<Long> characterOfMc(String mcUuid) throws Exception;

    Map<String, Long> linkedBodies() throws Exception;

    Optional<Map<String, Object>> findCharacterById(long id) throws Exception;

    void updateCharacter(long id, Map<String, Object> sheet, int wallet,
                         String realm, String status, String location) throws Exception;

    void logEvent(String type, String actorType, String actorId, Map<String, Object> data)
            throws Exception;

    void logEvent(String type, String actorType, String actorId, String targetType,
                  String targetId, Map<String, Object> data) throws Exception;

    int mandate(long characterId, int today, Politics politics) throws Exception;

    int addMandate(long characterId, int delta, int today, Politics politics) throws Exception;

    void killNpc(String npcKey, int tier, Map<String, Object> state) throws Exception;

    Set<String> liveRumorTags(int day, int decayEveryDays) throws Exception;

    BloodDebtEntry bloodDebt(String subject) throws Exception;

    BloodDebtEntry bloodDebtOf(long characterId) throws Exception;

    BloodDebtEntry addBloodDebt(String subject, Long characterId, double hidden,
                                double known, boolean publicKill, int day) throws Exception;
}
