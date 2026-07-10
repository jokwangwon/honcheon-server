package com.honcheon.core.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 소문 엔진 — config/rumor.yml 의 자바 구현.
 * 생성(강도), 전파(망별 속도/왜곡), 감쇠, 세력의 조직적 인지를 굴린다.
 * 참조 구현: tools/simulate_world_reaction.py
 */
public final class RumorEngine {

    public record Network(String id, String name, int speedDays, int distortion, Set<String> interests) {
    }

    public record Arrival(int day, int accuracy) {
    }

    public record AwarenessEvent(String faction, String rumorId, int day, int accuracy) {
    }

    /** 소문 개체 — 강도가 0이 되면 소멸하고 NPC 기억 태그로만 잔존한다 */
    public static final class Rumor {
        private final String id;
        private final int createdDay;
        private final Set<String> tags;
        private final String directParty;
        private final Map<String, Arrival> arrivals = new LinkedHashMap<>();
        private int intensity;
        private boolean dead;

        private Rumor(String id, int createdDay, int intensity, Set<String> tags, String directParty) {
            this.id = id;
            this.createdDay = createdDay;
            this.intensity = intensity;
            this.tags = Set.copyOf(tags);
            this.directParty = directParty;
        }

        public String id() {
            return id;
        }

        public int intensity() {
            return intensity;
        }

        public boolean isDead() {
            return dead;
        }

        public Map<String, Arrival> arrivals() {
            return Collections.unmodifiableMap(arrivals);
        }
    }

    private final Map<String, Network> networks;
    private final Map<String, List<String>> factionAccess;
    private final int decayEveryDays;
    private final int decayAmount;
    private final List<Map.Entry<String, Integer>> accuracyBandsDesc;

    @SuppressWarnings("unchecked")
    public RumorEngine(Map<String, Object> config) {
        Map<String, Object> rawNetworks = RulesConfig.section(config, "networks");
        Map<String, Network> parsedNetworks = new LinkedHashMap<>();
        rawNetworks.forEach((id, raw) -> {
            Map<String, Object> net = (Map<String, Object>) raw;
            parsedNetworks.put(id, new Network(id, (String) net.get("name"),
                    RulesConfig.intValue(net.get("speed_days")),
                    RulesConfig.intValue(net.get("distortion")),
                    new LinkedHashSet<>((List<String>) net.get("interests"))));
        });
        this.networks = Collections.unmodifiableMap(parsedNetworks);

        Map<String, Object> awareness = RulesConfig.section(config, "faction_awareness");
        Map<String, List<String>> parsedAccess = new LinkedHashMap<>();
        ((Map<String, Object>) awareness.get("network_access"))
                .forEach((faction, nets) -> parsedAccess.put(faction, List.copyOf((List<String>) nets)));
        this.factionAccess = Collections.unmodifiableMap(parsedAccess);

        Map<String, Object> decay = (Map<String, Object>)
                RulesConfig.section(config, "propagation").get("decay");
        this.decayEveryDays = RulesConfig.intValue(decay.get("every_days"));
        this.decayAmount = RulesConfig.intValue(decay.get("amount"));

        Map<String, Object> bands = RulesConfig.section(config, "accuracy_bands");
        List<Map.Entry<String, Integer>> parsedBands = new ArrayList<>();
        bands.forEach((name, raw) -> parsedBands.add(Map.entry(name,
                RulesConfig.intValue(((Map<String, Object>) raw).get("min")))));
        parsedBands.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        this.accuracyBandsDesc = List.copyOf(parsedBands);
    }

    /**
     * 소문 생성 — 도달 스케줄 계산.
     * 발원망은 즉시, 강도 2 이상이면 관심 일치 망으로 전파 (speed_days 후, distortion 적용).
     */
    public Rumor create(String id, int day, String originNet, int intensity, int accuracy,
                        Set<String> tags, String directParty) {
        Rumor rumor = new Rumor(id, day, intensity, tags, directParty);
        rumor.arrivals.put(originNet, new Arrival(day, accuracy));
        if (intensity >= 2) {
            for (Network network : networks.values()) {
                if (!network.id().equals(originNet)
                        && !Collections.disjoint(network.interests(), tags)) {
                    rumor.arrivals.put(network.id(),
                            new Arrival(day + network.speedDays(), accuracy - network.distortion()));
                }
            }
        }
        return rumor;
    }

    /**
     * 해당 일자의 세력 인지 이벤트.
     * 조직 채널 원칙(자기 채널 도달 시에만) + 중복 가산 금지(세력당 소문 1회, 당사자 제외).
     * scoredKeys: "소문ID|세력" — 호출자가 가산 처리 후 추가한다.
     */
    public List<AwarenessEvent> awarenessOn(int day, Collection<Rumor> rumors, Set<String> scoredKeys) {
        List<AwarenessEvent> events = new ArrayList<>();
        for (Rumor rumor : rumors) {
            if (rumor.dead) {
                continue;
            }
            for (Map.Entry<String, List<String>> access : factionAccess.entrySet()) {
                String faction = access.getKey();
                if (faction.equals(rumor.directParty)
                        || scoredKeys.contains(rumor.id() + "|" + faction)) {
                    continue;
                }
                Arrival earliest = null;
                for (String net : access.getValue()) {
                    Arrival arrival = rumor.arrivals.get(net);
                    if (arrival != null && arrival.day() == day
                            && (earliest == null || arrival.accuracy() < earliest.accuracy())) {
                        earliest = arrival;
                    }
                }
                if (earliest != null) {
                    events.add(new AwarenessEvent(faction, rumor.id(), day, earliest.accuracy()));
                }
            }
        }
        return events;
    }

    /** 감쇠 — 발생 후 every_days 마다 강도 감소, 0이면 소멸 */
    public void decayOn(int day, Rumor rumor) {
        if (rumor.dead || day <= rumor.createdDay || (day - rumor.createdDay) % decayEveryDays != 0) {
            return;
        }
        rumor.intensity += decayAmount;
        if (rumor.intensity <= 0) {
            rumor.intensity = 0;
            rumor.dead = true;
        }
    }

    /** 정확도 구간 — 같은 소문이 어떻게 들리는가 (사실적/과장/오해/괴담) */
    public String accuracyBand(int accuracy) {
        for (Map.Entry<String, Integer> band : accuracyBandsDesc) {
            if (accuracy >= band.getValue()) {
                return band.getKey();
            }
        }
        return accuracyBandsDesc.get(accuracyBandsDesc.size() - 1).getKey();
    }
}
