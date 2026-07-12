package com.honcheon.mvt;

import com.honcheon.core.rules.ProgressionEngine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 플레이어 화후 원장 (MVT — 메모리 보관).
 * "판정은 정수, 자원은 실수" — 기술은 수련 일치(실수)로 쌓이고 레벨은 환산표로 파생된다.
 *
 * <p><b>2026-07 성장 축 패스</b> — 이 원장은 <b>숙련만</b> 갖고 있었다. 능력치는 아예 없었고,
 * 그래서 {@code Vitality} 는 체력을 경지에서 유도했으며({@code defaultChe} = 캡 −1)
 * {@code SkillListener} 는 공격 판정에서 능력치 항을 통째로 뺐다
 * (<i>"MVT 근사: 능력치 시트가 없다"</i>). <b>같은 경지의 두 사람이 완전히 같은 사람이었다.</b>
 *
 * <p>이제 이 원장이 <b>캐릭터 시트</b>다. 세 사다리가 전부 여기 쌓인다 (전부 실수 — 화후 규칙):
 * <ul>
 *   <li><b>능력치</b> {@code attrDays} — 수련 배분이 붓는다 ({@link Growth#train})</li>
 *   <li><b>무공 숙련</b> {@code skillDays} — 수련 + <b>실전</b> (다섯 과목 중 유일)</li>
 *   <li><b>내공</b> {@code naegong} — 심법이 있어야 쌓인다 (개화 게이트)</li>
 * </ul>
 * 그리고 <b>선택</b>이 하나: {@code curriculum} — 하루 5구간을 어디에 쓰는가.
 */
public final class PlayerLedger {

    private final Map<String, Double> skillDays = new HashMap<>();
    private final Map<String, Integer> repetitionByType = new HashMap<>();
    private double grantedToday;
    private long lastGameDay = -1;
    private int money;
    private int marks실전;
    private int marks사선;

    // ─── 성장 축 (2026-07) — 능력치 화후 · 수련 배분 · 단전 ───

    /** 능력치 화후 (실수 적립) — 판정은 정수부, 파생치(내구·내력)는 실수치 */
    private final Map<String, Double> attrDays = new LinkedHashMap<>();
    /** 수련 배분 — 과목 → 구간 수 (합 ≤ 5). <b>이것이 플레이어의 선택이다</b> */
    private final Map<String, Integer> curriculum = new LinkedHashMap<>();
    /** 내공 실수치 — 내력 풀 = round(내공 × 3) */
    private double naegong;
    /** 익힌 심법 (simbeop.yml id) — null = 개화 전 (내공 과목 봉쇄 · 결 없음) */
    private String simbeop;
    /** 주력 무공 — 초식 과목의 구간이 쌓이는 곳 (승급 요건의 '주력 무공 숙련') */
    private String primaryArt = "육합검";
    /** 오늘 캡에 막혀 버려진 일치 — HUD 가 "몸이 더는 받지 않는다"를 띄울 근거 */
    private double wastedToday;

    /** MC 하루(24000틱) 단위 리셋 — 일일 상한·반복 감쇠의 시계 */
    public void rollDay(long gameDay) {
        if (gameDay != lastGameDay) {
            lastGameDay = gameDay;
            grantedToday = 0;
            wastedToday = 0;
            repetitionByType.clear();
        }
    }

    /** 날이 바뀌었는가 — 하루치 수련 정산(Growth.train)의 계기 */
    public boolean isNewDay(long gameDay) {
        return gameDay != lastGameDay;
    }

    public long lastGameDay() {
        return lastGameDay;
    }

    // ─── 능력치 화후 ───

    /** 능력치 실수치 — 판정은 정수부만 본다 (3.9와 4.0은 세계가 다르다) */
    public double attr(String name) {
        return attrDays.getOrDefault(name, 0.0);
    }

    /** 능력치 화후 적립 (단위: 능력치 점수 — 일치가 아니라 이미 환산된 값) */
    public void grantAttr(String name, double points) {
        attrDays.merge(name, points, Double::sum);
    }

    /** 생성 시 능력치 세팅 (player_creation 프리셋 전개 · 연무장 시험) */
    public void setAttr(String name, double value) {
        attrDays.put(name, value);
    }

    public Map<String, Double> allAttrs() {
        return attrDays;
    }

    // ─── 단전 ───

    public double naegong() {
        return naegong;
    }

    public void grantNaegong(double points) {
        naegong += points;
    }

    public void setNaegong(double value) {
        naegong = value;
    }

    /** 익힌 심법 — null 이면 개화 전 (축기 불가 · 결 없음) */
    public String simbeop() {
        return simbeop;
    }

    /** 개화 — 심법을 얻는다 (취걸개 기연 · 문파 전수) */
    public void setSimbeop(String id) {
        simbeop = id;
    }

    // ─── 수련 배분 — 플레이어의 선택 ───

    /** 과목 → 구간 수. 비어 있으면 아무것도 수련하지 않는다 (사냥·의뢰에 하루를 다 쓴 것) */
    public Map<String, Integer> curriculum() {
        return curriculum;
    }

    /** 배분을 세운다 — 구간 합이 상한을 넘으면 호출자가 막는다 (Growth.segmentsPerDay) */
    public void setSegments(String subject, int segments) {
        if (segments <= 0) {
            curriculum.remove(subject);
        } else {
            curriculum.put(subject, segments);
        }
    }

    public void clearCurriculum() {
        curriculum.clear();
    }

    /** 구간이 가장 많은 과목 — 사선 마크의 '관련 능력치'가 여기로 간다 (cultivation.yml) */
    public String primarySubject() {
        return curriculum.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 주력 무공 — 초식 과목·실전 적립이 쌓이는 원장 */
    public String primaryArt() {
        return primaryArt;
    }

    public void setPrimaryArt(String art) {
        primaryArt = art;
    }

    /** 오늘 천장에 막혀 버려진 일치 — "몸이 더는 받지 않는다" */
    public double wastedToday() {
        return wastedToday;
    }

    public void addWasted(double days) {
        wastedToday += days;
    }

    public int repetitionOf(String mobType) {
        return repetitionByType.getOrDefault(mobType, 0);
    }

    public void countRepetition(String mobType) {
        repetitionByType.merge(mobType, 1, Integer::sum);
    }

    public double grantedToday() {
        return grantedToday;
    }

    /** 적립 반영 — 반영 전 기술 레벨을 돌려준다 (돌파 감지는 호출자가 levelOf 비교) */
    public void grant(String skill, double days) {
        grantedToday += days;
        skillDays.merge(skill, days, Double::sum);
    }

    /** 누적 일치 → 숙련 레벨 (환산표 걷기 — 0→1 90일, 1→2 180일 …) */
    public int levelOf(String skill, ProgressionEngine progression) {
        double remaining = skillDays.getOrDefault(skill, 0.0);
        int level = 0;
        int cost;
        while ((cost = progression.skillLevelUpDays(level)) > 0 && remaining >= cost) {
            remaining -= cost;
            level++;
        }
        return level;
    }

    public double daysOf(String skill) {
        return skillDays.getOrDefault(skill, 0.0);
    }

    /** 다음 숙련까지의 진행도 0.0~1.0 — 화후 게이지의 입력 (환산표 상한 도달 시 1.0) */
    public double progressToNext(String skill, ProgressionEngine progression) {
        double remaining = skillDays.getOrDefault(skill, 0.0);
        int level = 0;
        int cost;
        while ((cost = progression.skillLevelUpDays(level)) > 0 && remaining >= cost) {
            remaining -= cost;
            level++;
        }
        return cost <= 0 ? 1.0 : remaining / cost;
    }

    public Map<String, Double> allSkills() {
        return skillDays;
    }

    public int money() {
        return money;
    }

    public void earn(int amount) {
        money += amount;
    }

    public void mark실전() {
        marks실전++;
    }

    public void mark사선() {
        marks사선++;
    }

    public int marks실전() {
        return marks실전;
    }

    public int marks사선() {
        return marks사선;
    }
}
