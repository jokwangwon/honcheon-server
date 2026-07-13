package com.honcheon.domain;

import com.honcheon.core.rules.FactionReactionEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 세력 반응 <b>도메인 서비스</b> — 규칙({@code core})과 장부(포트)가 만나는 유일한 곳.
 *
 * <p>전에는 이 조합이 <b>봇 안에</b> 있었다: {@code Db.addFavor} 가 산수를 하고,
 * {@code Factions.decayedAttention} 이 감쇠를 하고, {@code core.FactionReactionEngine} 은
 * <b>같은 산수를 제 메모리 맵에 대고 다시</b> 했다 (테스트만 그것을 불렀다).
 * 파리티 테스트는 초록이었지만 <b>플레이어는 그 코드를 겪지 않았다.</b>
 *
 * <p>이제 산수는 {@link FactionReactionEngine} 하나다. 이 서비스가 하는 일은 셋뿐이다:
 * <ol>
 *   <li>장부에서 <b>날것</b>을 읽는다 (포트)</li>
 *   <li>규칙에게 <b>오늘의 값</b>과 <b>다음 값</b>을 묻는다 (core)</li>
 *   <li>장부에 <b>적는다</b> (포트)</li>
 * </ol>
 *
 * <p><b>디스코드를 모른다.</b> 임베드도, 슬래시 명령도, 25칸 한도도 모른다 —
 * 그것이 이 클래스가 {@code domain} 모듈에 있는 이유다 (build.gradle 에 JDA 가 없다).
 *
 * <p><b>감쇠는 읽는 순간 정산한다</b> — 배치 잡이 아니다. 같은 세계일이면 몇 번을 읽어도 같은 값.
 */
public final class FactionService {

    /** 세력 × 캐릭터 관계 한 칸 — {@code attention}/{@code favor} 는 <b>오늘 기준 정산값</b> (저장값이 아니다) */
    public record Standing(String faction, int attention, int favor, int peakStage, int peakFavor) {
    }

    private final FactionReactionEngine rules;
    private final FactionLedger ledger;

    public FactionService(FactionReactionEngine rules, FactionLedger ledger) {
        this.rules = rules;
        this.ledger = ledger;
    }

    /** 오늘 기준 세력 관계 — 감쇠 정산 후의 값 (세계는 잊는다, 단 이력의 하한은 남는다) */
    public Standing standing(String factionId, long characterId, int today) throws Exception {
        FactionLedger.Row raw = ledger.standingRow(factionId, characterId);
        int favor = rules.decayedFavor(raw.favor(), raw.peakFavor(), raw.favorDay(), today);
        int attention = rules.decayedAttention(raw.attention(), raw.peakStage(), favor,
                raw.attentionDay(), today);
        return new Standing(factionId, attention, favor, raw.peakStage(), raw.peakFavor());
    }

    /** 이 캐릭터를 아는 모든 세력 (0 인 관계는 행이 없다 — 무관심은 기록되지 않는다) */
    public List<Standing> standings(long characterId, int today) throws Exception {
        List<Standing> out = new ArrayList<>();
        for (String faction : ledger.standingFactions(characterId)) {
            out.add(standing(faction, characterId, today));
        }
        return out;
    }

    /** 우호 조회 (오늘 기준 정산값) — 직행 루트·게시판 게이트의 입력 */
    public int favor(String factionId, long characterId, int today) throws Exception {
        return standing(factionId, characterId, today).favor();
    }

    /** 주목 가산 — 정산 후 더하고 클램프. peak_stage(감쇠 하한의 근거)를 갱신한다 */
    public int addAttention(String factionId, long characterId, int delta, int today) throws Exception {
        Standing now = standing(factionId, characterId, today);
        int next = rules.nextAttention(now.attention(), delta);
        int peakStage = Math.max(now.peakStage(), rules.stageOf(next).stage());
        ledger.writeStanding(factionId, characterId, new FactionLedger.Row(
                next, now.favor(), today, today, peakStage,
                Math.max(now.peakFavor(), now.favor())));
        return next;
    }

    /** 우호 가산 — 상한 없이 (favor.range 상한까지) */
    public int addFavor(String factionId, long characterId, int delta, int today) throws Exception {
        return addFavor(factionId, characterId, delta, rules.favorMax(), today);
    }

    /**
     * 우호 가산 — 정산 후 더하고 [0, min(cap, favorMax)] 클램프. peak_favor(공신 이력)를 갱신한다.
     *
     * @param cap 부르는 쪽이 아는 상한 (입문 루트의 잔심부름 상한 등 — faction_entry_routes.yml)
     */
    public int addFavor(String factionId, long characterId, int delta, int cap, int today)
            throws Exception {
        Standing now = standing(factionId, characterId, today);
        int next = rules.nextFavor(now.favor(), delta, cap);
        ledger.writeStanding(factionId, characterId, new FactionLedger.Row(
                now.attention(), next, today, today,
                Math.max(now.peakStage(), rules.stageOf(now.attention()).stage()),
                Math.max(now.peakFavor(), next)));
        return next;
    }

    /**
     * ★ <b>절연의 집행</b> — 우호를 깎고 <b>공신 이력(peak_favor)까지 무효화</b>한다.
     *
     * <p>{@code favor.decay.floor_after_공신}(8) 을 무너뜨리는 유일한 예외
     * (faction_politics.yml politics_hook.disavowal): peak_favor 를 새 값으로 재스탬프하면
     * <b>하한의 근거 자체가 사라진다</b> — 어느 문파도 관 앞에서 그를 감싸지 않는다.
     */
    public int breakFavor(String factionId, long characterId, int delta, int today) throws Exception {
        Standing now = standing(factionId, characterId, today);
        int next = rules.nextFavor(now.favor(), delta, rules.favorMax());
        ledger.writeStanding(factionId, characterId, new FactionLedger.Row(
                now.attention(), next, today, today, now.peakStage(), next));
        return next;
    }
}
