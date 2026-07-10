package com.honcheon.mvt;

import com.honcheon.core.rules.ProgressionEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * 플레이어 화후 원장 (MVT — 메모리 보관).
 * "판정은 정수, 자원은 실수" — 기술은 수련 일치(실수)로 쌓이고 레벨은 환산표로 파생된다.
 */
public final class PlayerLedger {

    private final Map<String, Double> skillDays = new HashMap<>();
    private final Map<String, Integer> repetitionByType = new HashMap<>();
    private double grantedToday;
    private long lastGameDay = -1;
    private int money;
    private int marks실전;
    private int marks사선;

    /** MC 하루(24000틱) 단위 리셋 — 일일 상한·반복 감쇠의 시계 */
    public void rollDay(long gameDay) {
        if (gameDay != lastGameDay) {
            lastGameDay = gameDay;
            grantedToday = 0;
            repetitionByType.clear();
        }
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
