package com.honcheon.bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 명분, 법명분, 문파 부담, 소문 전파를 다루는 저장소 포트. */
interface PoliticsStore {
    List<MyeongbunIssue> issues() throws Exception;

    Optional<MyeongbunIssue> issue(String key) throws Exception;

    MyeongbunIssue addMyeongbun(String key, String target, List<String> victims,
                                List<String> tags, int delta, int accuracy, String rumorGroup,
                                String trueTarget, int day, int max, Politics politics)
            throws Exception;

    Map<String, Integer> sectBurdens(int today, int decayEveryDays) throws Exception;

    int addSectBurden(String faction, int delta, String source, int today,
                      int decayEveryDays, int max) throws Exception;

    void transferMyeongbun(String key, String newTarget, int accuracy, int day) throws Exception;

    int mandate(long characterId, int today, Politics politics) throws Exception;

    int addMandate(long characterId, int delta, int today, Politics politics) throws Exception;

    int rumorAccuracyIn(String group, String network, int day) throws Exception;

    int spreadRumor(String group, String truth, String subject, Long subjectId,
                    List<String> tags, int strength, List<RumorArrival> arrivals,
                    String region) throws Exception;

    List<Rumors.Heard> heard(int day, String network, int decayEveryDays) throws Exception;

    /** ★B-190 ① — 이 캐릭터가 주체인 소문이 그 망에 살아 있는가 (하오문 보고 = 소문망의 실물) */
    boolean hasSubjectRumor(long subjectId, String network, int day, int decayEveryDays)
            throws Exception;

    int arrivalCountOn(int day) throws Exception;

    List<Map<String, Object>> arrivalsThrough(int day, int lookbackDays) throws Exception;

    boolean rumorGroupExists(String group) throws Exception;
}
