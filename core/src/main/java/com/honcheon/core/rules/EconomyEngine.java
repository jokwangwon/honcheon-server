package com.honcheon.core.rules;

import java.util.List;
import java.util.Map;

/**
 * 경제 엔진 — config/economy.yml 의 자바 구현.
 * 물가(지역 경제 지표 배율) · 매매(매입가·상술 캡) · 전장(수수료) · 품목 시세 이벤트(선형 감쇠).
 * 원칙: 강함은 돈으로 못 산다 — 엔진은 비매품에 가격을 만들지 않는다.
 */
public final class EconomyEngine {

    private final List<Map<String, Object>> regionMultipliers;
    private final Map<String, Object> priceTable;
    private final double npcBuyRate;
    private final double merchantCheckCap;
    private final double withdrawFee;
    private final double remoteWithdrawFee;

    @SuppressWarnings("unchecked")
    public EconomyEngine(Map<String, Object> economy) {
        this.regionMultipliers = (List<Map<String, Object>>) economy.get("region_multipliers");
        this.priceTable = RulesConfig.section(economy, "price_table");
        Map<String, Object> trading = RulesConfig.section(economy, "trading");
        this.npcBuyRate = ((Number) trading.get("npc_buy_rate")).doubleValue();
        this.merchantCheckCap = ((Number) trading.get("merchant_check_cap")).doubleValue();
        Map<String, Object> bank = RulesConfig.section(RulesConfig.section(economy, "wallet_and_bank"), "전장");
        this.withdrawFee = ((Number) bank.get("withdraw_fee")).doubleValue();
        this.remoteWithdrawFee = ((Number) bank.get("remote_withdraw_fee")).doubleValue();
    }

    /** 기준 물가 (문 단위). 비매품·비정형 가격(범위/경매)은 시장 밖 — 예외 */
    public int basePrice(String category, String item) {
        Object price = RulesConfig.section(priceTable, category).get(item);
        if (!(price instanceof Number)) {
            throw new IllegalArgumentException("시장 가격이 없는 품목 (비매/경매/범위): " + category + "." + item);
        }
        return ((Number) price).intValue();
    }

    /** 지역 경제 지표(0~100) → 물가 배율 — 내림차순 economy_min 첫 일치 */
    public double priceMultiplier(int economyIndex) {
        return regionValue(economyIndex, "물가");
    }

    /** 지역 경제 지표 → 의뢰 보수 배율 */
    public double rewardMultiplier(int economyIndex) {
        return regionValue(economyIndex, "의뢰_보수");
    }

    /** 지역 반영 실물가 = round(기준가 × 물가 배율) */
    public int adjustedPrice(int basePrice, int economyIndex) {
        return (int) Math.round(basePrice * priceMultiplier(economyIndex));
    }

    /** 상점 매입가 — 시세 50%, 상술 판정 성공 시 70% 캡 (장사가 기술인 이유) */
    public int npcBuyPrice(int marketPrice, boolean merchantCheckSuccess) {
        double rate = merchantCheckSuccess ? merchantCheckCap : npcBuyRate;
        return (int) Math.floor(marketPrice * rate);
    }

    /** 전장 인출 수수료 (올림) — 타지 환거래는 3배: 표행이 산업이 되는 이유 */
    public int withdrawFee(int amount, boolean remote) {
        return (int) Math.ceil(amount * (remote ? remoteWithdrawFee : withdrawFee));
    }

    /** 품목 시세 이벤트 — 델타의 선형 감쇠: 사건 직후 최대, decay 주 경과 시 소멸 */
    public int eventAdjustedPrice(int basePrice, double delta, double weeksElapsed, double decayWeeks) {
        double remaining = Math.max(0.0, 1.0 - weeksElapsed / decayWeeks);
        return (int) Math.round(basePrice * (1.0 + delta * remaining));
    }

    private double regionValue(int economyIndex, String key) {
        for (Map<String, Object> band : regionMultipliers) {
            if (economyIndex >= ((Number) band.get("economy_min")).intValue()) {
                return ((Number) band.get(key)).doubleValue();
            }
        }
        throw new IllegalArgumentException("경제 지표 밴드 없음: " + economyIndex);
    }
}
