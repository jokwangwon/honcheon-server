package com.honcheon.domain;

import java.util.List;

/**
 * 세력 장부 <b>포트</b> — "세력 대 개인"의 두 축(주목·우호)이 재기동을 넘어 사는 곳.
 *
 * <p>구현은 봇의 SQLite ({@code faction_standing} 표)다. 도메인은 그것을 모른다 —
 * 이 인터페이스가 아는 것은 <b>행(row)을 읽고 쓴다</b>는 것뿐이다.
 *
 * <p>★ <b>여기에 규칙을 넣지 말라.</b> 클램프도, 감쇠도, 단계 판정도 전부
 * {@code core.FactionReactionEngine} 의 일이다. 이 포트가 다루는 것은 <b>저장된 날것</b>이다.
 * 장부가 규칙을 알기 시작하면 그것이 곧 두 번째 정본이고, 우리는 그 병을 방금 고쳤다.
 */
public interface FactionLedger {

    /**
     * 저장된 원시 행 — <b>감쇠 정산 전</b>의 값.
     *
     * @param attention   저장된 주목 (오늘의 값이 아니다 — {@code attentionDay} 이후 세월이 깎는다)
     * @param favor       저장된 우호
     * @param attentionDay 주목을 마지막으로 적은 세계일 (감쇠의 기준점)
     * @param favorDay    우호를 마지막으로 적은 세계일
     * @param peakStage   도달했던 최고 단계 — 감쇠 하한의 근거 (3단계 이력은 잊히지 않는다)
     * @param peakFavor   도달했던 최고 우호 — 공신 이력의 근거
     */
    record Row(int attention, int favor, int attentionDay, int favorDay,
               int peakStage, int peakFavor) {

        /** 아직 아무 관계도 없는 상대 — 무관심은 기록되지 않는다 (행이 없다) */
        public static final Row NONE = new Row(0, 0, 0, 0, 0, 0);
    }

    /** 세력 × 캐릭터 한 칸 — 행이 없으면 {@link Row#NONE} */
    Row standingRow(String factionId, long characterId) throws Exception;

    /** 이 캐릭터를 아는 세력들 (행이 있는 것만 — 0 인 관계는 행이 없다) */
    List<String> standingFactions(long characterId) throws Exception;

    /** 한 칸을 적는다 (upsert) */
    void writeStanding(String factionId, long characterId, Row row) throws Exception;
}
