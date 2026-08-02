package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * ★B-194 체험형 서장 — 무대의 생명 (넋등 자리 점등 · 행동 감지 · 발단별 연출 · 격리).
 *
 * <p>정본: docs/design/seojang_experiential_v1.md · config/seojang.yml enactment.
 * <b>선택 = 자리 + 그 자리의 간단한 행동</b>: ① 이부자리 뒤에서 웅크리기 ② 담장 틈 앞에서
 * 틈을 바라보기 ③ 식구 우클릭. 넋등은 실물이 아니라 <b>파티클 빛점</b>이고, 전부
 * <b>그 사람에게만</b> 보인다 (격리 — 무대는 하나, 사람은 서로에게 없다).
 *
 * <p><b>봇이 저자, 마크는 서책</b> — 이 클래스는 문장을 짓지 않는다. 내레이션은 등록부
 * (seojang.yml prose)의 문장을 그대로 보여 줄 뿐이고, 행동은 「어느 패를 눌렀는가」로
 * 번역되어 호출자(onChoice)에게 간다. 시험 체험(/혼천 서장무대 체험)은 호출자가 없다 —
 * 선택을 화면에 보여 주고 끝낸다 (판정·시트 무접촉).
 *
 * <p>폴백(갇힘 금지): 60초 무행동이면 안내를 내고 시험 체험은 끝낸다 — 실배선에서는
 * 글자 패(기존 책)가 그 자리를 받는다.
 */
final class SeojangStagePlay implements Listener {

    /** 자리 이름 (enactment.spots 순서 = scenes.기본[0].choices 순서 — 등록부 계약) */
    private static final List<String> SPOT_NAMES = List.of("이부자리_뒤", "담장_틈", "식구_머리맡");
    private static final int FALLBACK_TICKS = 20 * 60;   // 【제안】 60초 — 무행동이면 강등
    private static final int MIDNIGHT = 18000;            // 그날 밤 — 자정 (등록부 시간: 자정)

    private final HoncheonMvt plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private StageLoader.Stage stage;                      // 게으른 적재 — 첫 체험 때 도면을 읽는다
    private List<Map<String, Object>> spotSpecs;          // enactment.spots (속삭임·행동)
    private String lastQuestion;                          // enactment.마지막_물음 — 뼈대의 그 문장
    private String entrySubtitle;                         // enactment.입장_자막 — 어둠은 연출이다
    private String wanderWhisper;                         // enactment.배회_속삭임 — 길을 잃은 몸에게
    private Map<String, Object> openings;                 // prose.incident_opening (내레이션 — 등록부 문장)

    private static final class Session {
        String incident;
        Location[] spots = new Location[3];
        Location back;                                    // 끝나면 돌아갈 자리
        UUID npc;                                         // 식구 — 이 사람에게만 보인다
        int hold;                                         // 행동 유지 틱 (웅크림·응시)
        int holdSpot = -1;
        int age;                                          // 폴백 시계
        boolean done;
        IntConsumer onChoice;                             // null = 시험 체험
        BukkitTask ticker;
    }

    SeojangStagePlay(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    // ─── 진입 ───

    @SuppressWarnings("unchecked")
    void begin(Player p, String incident, IntConsumer onChoice) {
        if (sessions.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.GRAY + "이미 기억 속에 있다.");
            return;
        }
        World w = plugin.antechamber().voyage().sea();
        if (w == null) {
            p.sendMessage(ChatColor.RED + "서장 월드를 못 열었다.");
            return;
        }
        try {
            if (stage == null) {
                stage = StageLoader.load(plugin.configPath(), "geunal_bam");
                Map<String, Object> seojang = RulesConfig.load(plugin.configPath().resolve("seojang.yml"));
                Map<String, Object> enact = (Map<String, Object>) seojang.getOrDefault("enactment", Map.of());
                List<Map<String, Object>> scenes = (List<Map<String, Object>>) enact.getOrDefault("기본", List.of());
                spotSpecs = scenes.isEmpty() ? List.of()
                        : (List<Map<String, Object>>) scenes.get(0).getOrDefault("spots", List.of());
                if (!scenes.isEmpty()) {
                    Object q = scenes.get(0).get("마지막_물음");
                    lastQuestion = q == null ? null : String.valueOf(q);
                    Object es = scenes.get(0).get("입장_자막");
                    entrySubtitle = es == null ? null : String.valueOf(es);
                    Object ww = scenes.get(0).get("배회_속삭임");
                    wanderWhisper = ww == null ? null : String.valueOf(ww);
                }
                openings = (Map<String, Object>) ((Map<String, Object>) seojang
                        .getOrDefault("prose", Map.of())).getOrDefault("incident_opening", Map.of());
            }
            int oy = StageLoader.originY(stage, w, plugin.antechamber().voyage());
            StageLoader.build(stage, w, oy);              // 멱등 — 무대는 언제나 도면대로

            Session s = new Session();
            s.incident = incident;
            s.back = p.getLocation();
            s.onChoice = onChoice;
            for (int i = 0; i < 3; i++) {
                s.spots[i] = StageLoader.spot(stage, w, oy, SPOT_NAMES.get(i));
            }
            sessions.put(p.getUniqueId(), s);

            veil(p, w);
            // ★빨간펜 2호 — 인과의 순서: 어둠(3.5초) 속에서 **소리가 먼저** 온다. 그래서 깬다.
            //   글은 그다음이다 — 첫머리가 방금 들은 그 소리를 말하게 (글이 소리를 앞서면 거짓말)
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, false, false));
            if (entrySubtitle != null) {
                p.sendTitle(" ", ChatColor.DARK_GRAY + entrySubtitle, 10, 60, 20);
            }
            Location wake = StageLoader.spot(stage, w, oy, "깨어남");
            wake.setDirection(StageLoader.spot(stage, w, oy, "식구_숨소리").toVector()
                    .subtract(wake.toVector()));           // 눈을 뜨면 첫 시야 = 잠든 식구
            p.teleport(wake);
            p.setPlayerTime(MIDNIGHT, false);             // 그 사람에게만 자정 — 밤바다는 그대로
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                    20 * 600, 0, true, false));           // 밤눈 — 어둠의 결은 두되 몸은 본다 (빨간펜 1호)
            spawnFamily(p, w, oy, s);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!s.done && p.isOnline()) {
                    sceneAmbience(p, s);                  // ① 어둠 속의 소리 — 깨는 이유
                }
            }, 15L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!s.done && p.isOnline()) {
                    narrate(p, s, incident);              // ② 눈을 뜨자, 방금 들은 것이 글이 된다
                    s.ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p, s), 5L, 5L);
                }
            }, 80L);
        } catch (Exception e) {
            sessions.remove(p.getUniqueId());
            p.sendMessage(ChatColor.RED + "기억이 열리지 않았다: " + e.getMessage());
        }
    }

    /** 격리 — 무대는 하나, 사람은 서로에게 없다 (Voyage.veil 과 같은 문법) */
    private void veil(Player p, World sea) {
        p.setCollidable(false);
        for (Player other : sea.getPlayers()) {
            if (!other.getUniqueId().equals(p.getUniqueId())) {
                p.hidePlayer(plugin, other);
                other.hidePlayer(plugin, p);
            }
        }
    }

    /**
     * ★빨간펜 (2026-08-02 「식구가 안 보이고, 뭔지도 모르겠다」) — 엔티티를 버렸다.
     * 서 있는 주민은 잠든 가족으로 안 읽힌다. 이제 식구는 **이불 속의 형태**(도면의 눈 둔덕)이고,
     * 이 손은 지난 판들이 남긴 보이지 않는 주민 고아만 걷는다.
     */
    private void spawnFamily(Player p, World w, int oy, Session s) {
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            boolean ours = e.getPersistentDataContainer().has(
                    new org.bukkit.NamespacedKey("honcheon", "stage_family"));
            boolean named = e.getCustomName() != null
                    && "식구".equals(ChatColor.stripColor(e.getCustomName()));
            if (ours || (named && e instanceof Villager)) {
                e.remove();
            }
        }
    }

    /**
     * 내레이션 — 등록부의 문장 그대로, ★한 문장씩 시간차로 (빨간펜 3호 「채팅이라 못 읽어」).
     * 생각이 흐르는 그 순간 **그 자리가 밝아진다** — 글과 세계가 같은 박자를 탄다
     * (「공간이 생각이 안 든다」의 수리: 공간이 문장에 응답한다).
     * 마지막 물음은 화면 한가운데 타이틀 — 못 놓친다.
     */
    private void narrate(Player p, Session s, String incident) {
        p.sendTitle(ChatColor.GOLD + "第一章", ChatColor.GRAY + "그날 밤", 10, 50, 20);
        long t = 30;
        Object opening = openings.get(incident);
        if (opening != null) {
            sched(p, s, t, () -> {
                p.sendMessage("");
                p.sendMessage(ChatColor.GRAY + String.valueOf(opening));
            });
            t += 70;
        }
        for (int i = 0; i < spotSpecs.size() && i < 3; i++) {
            final int idx = i;
            sched(p, s, t, () -> {
                Object thought = spotSpecs.get(idx).get("생각");
                if (thought != null) {
                    p.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.ITALIC + String.valueOf(thought));
                }
                // ★생각이 자리를 비춘다 — 같은 순간에 (글 따로 공간 따로면 생각이 안 든다)
                p.spawnParticle(Particle.SOUL_FIRE_FLAME,
                        s.spots[idx].clone().add(0, 1.0, 0), 25, 0.2, 0.5, 0.2, 0.01);
                p.playSound(s.spots[idx], Sound.BLOCK_AMETHYST_CLUSTER_STEP, 0.9f, 0.7f);
            });
            t += 55;
        }
        sched(p, s, t, () -> {
            if (lastQuestion != null) {
                p.sendTitle(" ", ChatColor.WHITE + lastQuestion, 10, 70, 25);
                p.sendMessage("");
                p.sendMessage(ChatColor.WHITE + lastQuestion);
            }
        });
    }

    private void sched(Player p, Session s, long delay, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!s.done && p.isOnline()) {
                r.run();
            }
        }, delay);
    }

    /**
     * 발단별 연출 — 같은 무대, 다른 그날 밤 (등록부 enactment.연출_by_incident 의 결).
     * ★소리는 바닐라 근사 【제안】 — 리소스팩 ogg(말발굽·곡소리·언성)가 디자인 사다리 ③에서 갈아탄다.
     */
    private void sceneAmbience(Player p, Session s) {
        switch (s.incident) {
            case "습격" -> {
                // 개 짖는 소리가 뚝 끊긴다 → 말발굽이 반복해서 **다가온다** (조용히 → 크게)
                p.playSound(s.spots[1], Sound.ENTITY_WOLF_GROWL, 0.7f, 0.8f);
                for (int i = 0; i < 6; i++) {
                    float vol = 0.3f + i * 0.14f;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!s.done && p.isOnline()) {
                            p.playSound(s.spots[1], Sound.ENTITY_HORSE_GALLOP, vol, 0.7f);
                        }
                    }, 50L + i * 45L);
                }
            }
            case "역병" -> p.playSound(s.spots[1], Sound.ENTITY_GHAST_AMBIENT, 0.25f, 0.5f);
            case "가문의_몰락" -> p.playSound(s.spots[1], Sound.ENTITY_VILLAGER_NO, 0.5f, 0.6f);
            case "목격" -> p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.4f, 0.9f);
            default -> p.playSound(p.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 0.4f, 0.8f);
        }
    }

    // ─── 심장 — 5틱마다: 넋등 점등 · 접근 속삭임 · 행동 감지 · 폴백 ───

    private void tick(Player p, Session s) {
        if (s.done || !p.isOnline()) {
            return;
        }
        s.age += 5;
        Location eye = p.getLocation();
        int near = -1;
        double best = 6.5;
        for (int i = 0; i < 3; i++) {
            double d = s.spots[i].distanceSquared(eye);
            if (d < best) {
                best = d;
                near = i;
            }
            // 넋등 — 파티클 빛점 (그 사람에게만). 가까우면 짙어진다
            int count = d < 2.5 ? 12 : 5;
            p.spawnParticle(Particle.SOUL_FIRE_FLAME, s.spots[i].clone().add(0, 0.9, 0),
                    count, 0.15, 0.35, 0.15, 0.005);
            if (d >= 2.5) {
                p.spawnParticle(Particle.END_ROD, s.spots[i].clone().add(0, 1.3, 0),
                        1, 0.05, 0.3, 0.05, 0.002);       // 먼 눈에도 걸리는 흰 점 하나
            }
        }
        if ((near < 0 || best >= 12.0) && wanderWhisper != null && s.age % 60 == 0) {
            p.sendActionBar(ChatColor.DARK_GRAY + wanderWhisper);   // 길 잃은 몸에게 — 배회 속삭임
        }
        if (near >= 0 && near < spotSpecs.size() && best < 12.0) {
            Object whisper = spotSpecs.get(near).get("속삭임");
            if (whisper != null) {
                p.sendActionBar(ChatColor.GRAY + String.valueOf(whisper));
            }
        }
        // 행동 감지 — 자리 기반이라 정직하다 (시안 §2)
        if (near == 0 && best < 2.0 && p.isSneaking()) {
            holdToward(p, s, 0, 60);                      // 웅크린 채 3초
        } else if (near == 1 && best < 2.0 && facingEast(p)) {
            holdToward(p, s, 1, 40);                      // 틈을 2초 바라본다
        } else if (s.holdSpot >= 0) {
            s.hold = 0;
            s.holdSpot = -1;
        }
        if (s.age % 70 == 0 && !s.done) {
            p.playSound(s.spots[2], Sound.ENTITY_FOX_SLEEP, 0.7f, 0.85f);   // 잠든 식구의 숨
        }
        if (s.age >= FALLBACK_TICKS && !s.done) {
            p.sendMessage(ChatColor.GRAY + "…기억이 흐려진다. (행동이 없으면 이야기가 글로 돌아간다 — 갇히지 않는다)");
            finish(p, s, -1);
        }
    }

    /** 틈은 동쪽 담에 있다 — 동쪽을 바라보는 시선이 곧 「살핀다」 (자리+방향, 허공 벡터 아님) */
    private boolean facingEast(Player p) {
        return p.getLocation().getDirection().getX() > 0.6;
    }

    private void holdToward(Player p, Session s, int spot, int need) {
        if (s.holdSpot != spot) {
            s.holdSpot = spot;
            s.hold = 0;
        }
        s.hold += 5;
        if (s.hold >= need) {
            choose(p, s, spot);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        interactAt(event.getPlayer());
    }

    /** 머리맡 우클릭 = 흔들어 깨운다 — 자리 기반 (엔티티 가시성의 덫을 통째로 비켜간다) */
    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction().name().startsWith("RIGHT_CLICK")) {
            interactAt(event.getPlayer());
        }
    }

    private void interactAt(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && !s.done
                && s.spots[2].distanceSquared(p.getLocation()) < 4.0) {
            choose(p, s, 2);
        }
    }

    // ─── 선택 확정 — 나머지 두 불이 꺼지고, 장면이 응답한다 ───

    private void choose(Player p, Session s, int spot) {
        if (s.done) {
            return;
        }
        s.done = true;
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.6f);
        p.spawnParticle(Particle.SOUL, s.spots[spot].clone().add(0, 1.0, 0), 20, 0.2, 0.4, 0.2, 0.01);
        Object label = spot < spotSpecs.size() ? spotSpecs.get(spot).get("행동") : null;
        p.sendActionBar(ChatColor.GOLD + (label == null ? "그리 하였다." : String.valueOf(label) + " — 그리 하였다."));
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(p, s, spot), 50L);
    }

    /** 끝 — 암전, 몸과 시간을 되돌리고, 선택을 호출자에게 넘긴다 (시험 체험은 화면에만) */
    private void finish(Player p, Session s, int chosen) {
        s.done = true;
        if (s.ticker != null) {
            s.ticker.cancel();
        }
        if (p.isOnline()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false));
            p.resetPlayerTime();
            p.teleport(s.back);
            p.setCollidable(true);
            if (!Voyage.isSea(p.getWorld())) {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.getUniqueId().equals(p.getUniqueId())) {
                        p.showPlayer(plugin, other);
                        other.showPlayer(plugin, p);
                    }
                }
            }
            if (chosen >= 0) {
                if (s.onChoice != null) {
                    s.onChoice.accept(chosen);
                } else {
                    p.sendMessage(ChatColor.GOLD + "체험 끝 — 선택 " + (chosen + 1) + "번이 판정으로 갔을 자리다 "
                            + ChatColor.GRAY + "(시험 체험이라 판정·시트는 안 건드린다)");
                }
            }
        }
        sessions.remove(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null && s.ticker != null) {
            s.ticker.cancel();
        }
    }
}
