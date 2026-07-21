package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;

/**
 * <b>BetterModel 로 가는 다리 — 리플렉션으로만 건넌다.</b>
 *
 * <p><b>왜 컴파일 의존이 아닌가.</b> BetterModel jar 은 저장소에 없다(gitignore). 그것을
 * {@code compileOnly} 로 못박으면 <b>새 체크아웃에서 빌드가 깨진다</b> — 이 저장소의 불변식
 * "HEAD 는 컴파일되어야 한다"([[head-must-compile-worktree-check]])를 어긴다. 그래서 컴파일
 * 시점에 BetterModel 을 <b>모르는 채로</b> 두고, 런타임에 <b>있으면 쓰고 없으면 판만으로 간다.</b>
 * ({@link QiGeometry} 가 EffectLib 을 대하는 것과 같은 태도 — 연출이 없는 것과 서버가 안 뜨는 것은 다르다.)
 *
 * <p><b>왜 3D 모델인가 (A·B 실측).</b> 판(ItemDisplay)은 옆에서 초승달로 읽히지만 뒤에서 사라지고
 * (85px), 점(파티클)은 어디서나 보이지만 형태가 없다(티끌). 굽은 리본은 <b>둘 다</b> 된다 —
 * 면이 굽어 뒤에서도 반대쪽 면이 보이고, 그러면서 형태가 남는다.
 *
 * <p><b>★ 값을 치르는 것.</b> BetterModel 은 표시 엔티티를 <b>패킷</b>으로 보낸다 — {@code @e} 에도,
 * 우리 {@code SkillDisplay} 예산 계수에도 <b>안 잡힌다.</b> 그래서 발행은 반드시 이 다리를 지나게 하고,
 * 스윙당 <b>하나</b>만 세운다 (예산 밖 폭주를 막는 유일한 선).
 */
final class ModelBridge {

    private final HoncheonMvt plugin;
    private boolean probed;
    private boolean available;

    // 캐시된 리플렉션 손 — 한 번만 찾는다
    private Method mModel;         // BetterModel.model(String) → Optional<ModelRenderer>
    private Method mCreate;        // ModelRenderer.create(PlatformLocation) → DummyTracker
    private Constructor<?> cLoc;   // new BukkitLocation(Location)
    private Constructor<?> cPlayer;// new BukkitPlayer(Player)
    private Constructor<?> cRot;   // new ModelRotation(pitch, yaw)
    private Method mSpawn;         // DummyTracker.spawn(PlatformPlayer)
    private Method mRotation;      // Tracker.rotation(Supplier<ModelRotation>)
    private Method mAnimate;       // Tracker.animate(String) → boolean
    private Method mLocation;      // DummyTracker.location(PlatformLocation)
    private Method mClose;         // Tracker.close()

    ModelBridge(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /** BetterModel 이 이 서버에 있고 손이 다 잡혔는가. 한 번만 캔다. */
    boolean available() {
        if (probed) {
            return available;
        }
        probed = true;
        try {
            ClassLoader cl = plugin.getServer().getPluginManager().getPlugin("BetterModel")
                    == null ? null : plugin.getServer().getPluginManager()
                    .getPlugin("BetterModel").getClass().getClassLoader();
            if (cl == null) {
                plugin.getLogger().info("[모델] BetterModel 이 없다 — 검기는 판·점으로 간다");
                return false;
            }
            Class<?> betterModel = Class.forName("kr.toxicity.model.api.BetterModel", true, cl);
            Class<?> renderer = Class.forName(
                    "kr.toxicity.model.api.data.renderer.ModelRenderer", true, cl);
            Class<?> platformLoc = Class.forName(
                    "kr.toxicity.model.api.platform.PlatformLocation", true, cl);
            Class<?> platformPlayer = Class.forName(
                    "kr.toxicity.model.api.platform.PlatformPlayer", true, cl);
            Class<?> bukkitLoc = Class.forName(
                    "kr.toxicity.model.api.bukkit.platform.BukkitLocation", true, cl);
            Class<?> bukkitPlayer = Class.forName(
                    "kr.toxicity.model.api.bukkit.platform.BukkitPlayer", true, cl);
            Class<?> dummy = Class.forName(
                    "kr.toxicity.model.api.tracker.DummyTracker", true, cl);
            Class<?> tracker = Class.forName(
                    "kr.toxicity.model.api.tracker.Tracker", true, cl);
            Class<?> rotation = Class.forName(
                    "kr.toxicity.model.api.tracker.ModelRotation", true, cl);

            mModel = betterModel.getMethod("model", String.class);
            mCreate = renderer.getMethod("create", platformLoc);
            cLoc = bukkitLoc.getConstructor(Location.class);
            cPlayer = bukkitPlayer.getConstructor(Player.class);
            cRot = rotation.getConstructor(float.class, float.class);
            mSpawn = dummy.getMethod("spawn", platformPlayer);
            mRotation = tracker.getMethod("rotation", java.util.function.Supplier.class);
            mAnimate = tracker.getMethod("animate", String.class);
            mLocation = dummy.getMethod("location", platformLoc);
            mClose = tracker.getMethod("close");
            available = true;
            plugin.getLogger().info("[모델] BetterModel 다리 연결됨 — 검기 리본을 쓸 수 있다");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // ★ 조용히 없는 척하지 않는다 — 어느 손이 안 잡혔는지 말한다 (판 갱신 때 시그니처가 바뀔 수 있다)
            plugin.getLogger().warning("[모델] BetterModel 다리를 못 걸었다 — 검기는 판·점으로 간다: " + e);
            available = false;
        }
        return available;
    }

    /**
     * <b>리본 하나를 세운다.</b> 스윙당 하나 — 예산 밖 폭주를 막는 선이다.
     *
     * @param at     가슴께 (판·점과 같은 중심)
     * @param model  {@code .bbmodel} 이름 (예: {@code kigi_arc})
     * @param anim   재생할 애니메이션 이름 (예: {@code grow}) — 없으면 {@code null}
     * @param pitch  리본의 기울기(도) — 판의 {@code tilt_deg} 와 같은 값을 준다
     * @param yaw    바라보는 방향(도)
     * @param lifeTicks 이 틱 뒤 스스로 닫힌다 (판의 draw+fade 와 맞춘다)
     * @param viewers  이 사람들에게만 보인다 (관람자별 — 팩 게이트가 산다). {@code null} 이면 전원
     * @return 세웠으면 true. 못 세우면 false (부르는 쪽이 판·점으로 메운다)
     */
    boolean spawnArc(Location at, String model, String anim, float pitch, float yaw,
                     int lifeTicks, Collection<Player> viewers) {
        if (!available() || at == null || at.getWorld() == null) {
            return false;
        }
        try {
            Object opt = mModel.invoke(null, model);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) {
                plugin.getLogger().warning("[모델] 모델을 못 찾았다: " + model
                        + " (BetterModel/models/" + model + ".bbmodel · 팩 재빌드했나?)");
                return false;
            }
            Object renderer = o.get();
            Object loc = cLoc.newInstance(at);
            Object dummy = mCreate.invoke(renderer, loc);

            // 기울기·방향 — 판(kigiPose)의 tilt_deg 와 같은 평면에 세운다
            Object rot = cRot.newInstance(pitch, yaw);
            mRotation.invoke(dummy, (java.util.function.Supplier<Object>) () -> rot);

            // 관람자별 — 팩 없는 눈에는 안 보낸다 (우리 팩 게이트를 그대로 지킨다)
            java.util.Collection<Player> whom = viewers != null ? viewers
                    : at.getWorld().getPlayers();
            for (Player p : whom) {
                mSpawn.invoke(dummy, cPlayer.newInstance(p));
            }
            if (anim != null && !anim.isBlank()) {
                mAnimate.invoke(dummy, anim);
            }
            // 스스로 닫힌다 — 연출은 판·점처럼 수명이 정해져 있다 (남으면 유령이 된다)
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    mClose.invoke(dummy);
                } catch (ReflectiveOperationException ignored) {
                    // 닫기 실패는 다음 재적재가 정리한다 — 여기서 서버를 흔들지 않는다
                }
            }, Math.max(1, lifeTicks));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("[모델] 리본을 못 세웠다 (" + model + "): " + e);
            return false;
        }
    }
}
