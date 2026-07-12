package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 틱 예산 — <b>한 틱 폭탄의 해체 장치</b>.
 *
 * <p><b>병(病).</b> 조성기는 한 틱에 90만 번 블록을 쓴다 (청하현). 봉우리 하나가 반경 60 × 높이 40 이고,
 * 사구가 반경 40 이다. 블록 하나를 쓰는 값은 5~20µs 다 (조명 재계산 · 청크 팔레트 · 이웃 갱신 · 패킷).
 * 90만 × 10µs = <b>9초</b>. 그 9초 동안 메인 스레드는 게임을 돌리지 않는다 — 워치독이 깨어난다.
 *
 * <p><b>진단.</b> 총량은 못 깎는다. 마을을 지으려면 블록을 놓아야 한다. 깎을 수 있는 건 <b>한 틱에 얼마나
 * 하느냐</b>다 — {@code MvtCommand.SeedProbe}·{@code SiteProbe} 가 이미 쓴 처방이다 (20ms/틱 슬라이스).
 * 그러나 그 둘은 <i>자기가 짠 루프</i>였다. 조성기는 7,600줄짜리 남의 함수이고, 그 안에 재개 지점이 없다.
 *
 * <p><b>처방 — 스레드를 재개 지점으로 쓴다.</b> 조성 본문을 <b>워커 스레드</b>에서 돌리되, 그 스레드에는
 * <b>진짜 월드를 주지 않는다</b>. 대신 {@link World} 의 <b>대역(代役)</b>을 준다. 대역이 하는 일은 둘뿐이다:
 * <ul>
 *   <li><b>쓰기(void 반환)</b> — 큐에 <b>순서대로</b> 적고 즉시 돌아간다. 워커는 안 멈춘다.</li>
 *   <li><b>읽기(값 반환)</b> — 큐를 <b>비우기를 기다렸다가</b>(장벽) 메인 스레드에 왕복해 값을 받는다.</li>
 * </ul>
 * 메인 스레드는 매 틱 큐를 <b>예산(20ms)만큼만</b> 집행하고 손을 뗀다. 서버는 계속 돈다.
 *
 * <p><b>결정론.</b> 이 장치는 <b>순서를 한 톨도 바꾸지 않는다</b>. 큐는 FIFO 이고, 값을 읽는 호출은
 * 반드시 앞선 쓰기가 전부 집행된 뒤에 답을 받는다 (read-your-writes). 즉 조성기가 보는 세계는
 * 동기 실행일 때와 <b>같은 순서로 같은 상태</b>다. 좌표 해시는 손대지 않는다. 나뉜 것은 <b>시간</b>뿐이다.
 *
 * <p><b>소유·배선.</b> 이 파일은 성능 담당의 것이고, 조성기 3종({@code CheonghaBuilder} ·
 * {@code RemoteBuilder} · {@code TerrainForge})은 <b>한 줄도 고치지 않는다</b>. 배선은 호출부에서만 한다:
 * <pre>{@code
 *   // 이전 (한 틱 폭탄)
 *   Map<String, Location> anchors = CheonghaBuilder.build(world, cx, cy, cz, zones);
 *   finish(anchors);
 *
 *   // 이후 (틱을 나눠 먹는다 — 조성기는 그대로다)
 *   TickBudget.preload(plugin, world, cx, cz, 96).thenRun(() -> TickBudget.build(
 *           plugin, "조성:청하현", world,
 *           w -> CheonghaBuilder.build(w, cx, cy, cz, zones),   // w = 대역 월드
 *           anchors -> { TickBudget.rebind(anchors, world); finish(anchors); },
 *           err -> sender.sendMessage("조성 실패: " + err),
 *           sender::sendMessage));
 * }</pre>
 *
 * <p><b>예산의 정본은 {@code config/performance.yml} 이다</b> — 이 클래스에 수치를 박지 않는다.
 */
public final class TickBudget {

    // ─── 예산 (config/performance.yml tick_slicing — 정본) ───
    /** 한 틱에 집행할 시간 (ns). 기본 20ms = 50ms 틱의 40%. */
    private static long budgetNs = 20_000_000L;
    /** 워커가 메인보다 앞서 달릴 수 있는 연산 수 (백프레셔). */
    private static int queueCapacity = 65_536;
    /** 조성 한 건의 최대 벽시계 시간 (초). 넘으면 중단하고 경고한다. */
    private static long maxSeconds = 300L;
    /** 진행 보고 주기 (틱). */
    private static long progressTicks = 100L;
    /** 블록 쓰기에 물리(이웃 갱신)를 적용할 것인가. false = 빠르지만 검수 재실행 필요. */
    private static boolean applyPhysics = true;

    /** 한 번에 조성 하나 — 두 조성이 같은 큐를 나눠 쓰면 순서가 섞인다 (결정론 위반). */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private TickBudget() {
    }

    /** config/performance.yml 의 tick_slicing 절을 읽는다 (없으면 기본값 — 서버는 그래도 돈다). */
    @SuppressWarnings("unchecked")
    public static void load(Path configDir) {
        Path file = configDir.resolve("performance.yml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(file);
        Object node = root.get("tick_slicing");
        if (!(node instanceof Map)) {
            return;
        }
        Map<String, Object> s = (Map<String, Object>) node;
        budgetNs = (long) (num(s.get("budget_ms"), 20.0) * 1_000_000.0);
        queueCapacity = (int) num(s.get("queue_capacity"), queueCapacity);
        maxSeconds = (long) num(s.get("max_seconds"), maxSeconds);
        progressTicks = (long) num(s.get("progress_every_ticks"), progressTicks);
        Object phys = s.get("apply_physics");
        applyPhysics = !(phys instanceof Boolean b) || b;
    }

    private static double num(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    /** 지금 조성이 돌고 있는가 — 호출부의 중복 실행 방지용. */
    public static boolean busy() {
        return BUSY.get();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  1. 범용 슬라이서 — 내가 짠 루프를 나눠 먹인다 (SeedProbe 와 같은 처방)
    // ══════════════════════════════════════════════════════════════════════

    /** 한 걸음. {@code false} 를 돌려주면 일이 끝난 것이다. */
    @FunctionalInterface
    public interface Step {
        boolean step();
    }

    /**
     * 반복 작업을 틱마다 예산만큼만 굴린다.
     *
     * <p>쓰는 자리: 검수기의 대량 스캔(TownAudit·TerrainAudit·RegionAudit), 대량 엔티티 정리 등
     * <b>내가 루프를 소유한</b> 곳. 조성기처럼 남의 함수는 {@link #build} 를 쓴다.
     */
    public static BukkitTask slice(Plugin plugin, String name, Step step, Runnable onDone) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                long t0 = System.nanoTime();
                try {
                    while (System.nanoTime() - t0 < budgetNs) {
                        if (!step.step()) {
                            cancel();
                            if (onDone != null) {
                                onDone.run();
                            }
                            return;
                        }
                    }
                } catch (Throwable t) {
                    cancel();
                    plugin.getLogger().severe("[틱예산] " + name + " 중단 — " + t);
                } finally {
                    Metrics.record("slice:" + name, System.nanoTime() - t0);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  2. 조성 슬라이서 — 남의 함수(조성기)를 통째로 나눠 먹인다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 조성 본문을 워커 스레드에서 돌리고, 그것이 세계에 내는 모든 호출을 메인 스레드가
     * <b>틱 예산만큼씩</b> 집행한다. 조성기는 한 줄도 고치지 않는다.
     *
     * @param world  진짜 월드 (본문에는 대역이 건네진다 — 본문은 이 객체를 절대 보지 못한다)
     * @param body   조성 본문. 인자로 받은 <b>대역 월드만</b> 써야 한다 (진짜 월드를 캡처하면 안 된다).
     * @param onDone 성공 — 메인 스레드에서 불린다. 반환값 안의 {@link Location} 은 대역 월드를 물고
     *               있으므로 {@link #rebind} 로 진짜 월드에 다시 묶어야 한다.
     * @param onError 실패 — 메인 스레드에서 불린다.
     * @param log    진행 보고 (없어도 된다 — null 허용)
     * @return 조성을 시작했으면 true. 이미 다른 조성이 돌고 있으면 false (아무것도 하지 않는다).
     */
    public static <T> boolean build(Plugin plugin, String name, World world,
                                    Function<World, T> body, Consumer<T> onDone,
                                    Consumer<Throwable> onError, Consumer<String> log) {
        if (!BUSY.compareAndSet(false, true)) {
            return false;
        }
        new Session<>(plugin, name, world, body, onDone, onError, log).start();
        return true;
    }

    /**
     * 조성 전에 땅을 <b>비동기로</b> 실어 온다 — 그리고 조성이 끝날 때까지 <b>붙잡아 둔다</b>.
     *
     * <p>슬라이스 조성은 수십 초 걸린다. 그 사이 청크가 언로드되면 다음 슬라이스가 그 청크를
     * <b>동기 재생성</b>하며 다시 서버를 세운다 (해체한 폭탄이 되살아난다). 플러그인 티켓으로 못 박는다.
     * 티켓은 {@link #build} 가 끝날 때 {@link Session} 이 뗀다.
     */
    public static CompletableFuture<Void> preload(Plugin plugin, World world, int cx, int cz, int radius) {
        List<CompletableFuture<Chunk>> loading = new ArrayList<>();
        for (int chunkX = (cx - radius) >> 4; chunkX <= (cx + radius) >> 4; chunkX++) {
            for (int chunkZ = (cz - radius) >> 4; chunkZ <= (cz + radius) >> 4; chunkZ++) {
                world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                loading.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        return CompletableFuture.allOf(loading.toArray(new CompletableFuture[0]));
    }

    /** 조성이 끝난 뒤 티켓을 뗀다 — 붙잡아 둔 땅을 세계에 돌려준다. */
    public static void release(Plugin plugin, World world) {
        world.removePluginChunkTickets(plugin);
    }

    /**
     * 대역 월드를 물고 있는 {@link Location} 을 진짜 월드에 다시 묶는다.
     *
     * <p>조성기는 {@code new Location(world, ...)} 으로 앵커를 만든다 — 그 {@code world} 는 대역이다.
     * 대역은 조성이 끝나면 죽은 객체이므로, 앵커를 저장하기 전에 반드시 갈아 끼워야 한다.
     * ({@code Zone} 은 월드 <b>이름(String)</b>만 담으므로 손댈 것이 없다.)
     */
    public static Location rebind(Location l, World real) {
        return l == null ? null : new Location(real, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** 앵커 맵을 통째로 다시 묶는다 (조성기의 반환값 그대로). */
    public static void rebind(Map<String, Location> anchors, World real) {
        if (anchors != null) {
            anchors.replaceAll((k, v) -> rebind(v, real));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  구현 — 대역 월드와 큐
    // ══════════════════════════════════════════════════════════════════════

    /** 큐에 실리는 한 건. {@code sync} 면 워커가 답을 기다린다 (그 전에 큐가 비워진다 = 장벽). */
    private static final class Call {
        static final int GENERIC = 0;
        static final int SET_TYPE = 1;          // Block.setType(Material)
        static final int SET_TYPE_PHYS = 2;     // Block.setType(Material, boolean)
        static final int SET_DATA = 3;          // Block.setBlockData(BlockData)
        static final int SET_DATA_PHYS = 4;     // Block.setBlockData(BlockData, boolean)

        final Object target;        // BlockRef | 실제 Bukkit 객체
        final Method method;
        final Object[] args;
        final boolean sync;
        final int fast;

        Call(Object target, Method method, Object[] args, boolean sync, int fast) {
            this.target = target;
            this.method = method;
            this.args = args;
            this.sync = sync;
            this.fast = fast;
        }
    }

    /** 아직 실체화하지 않은 블록 좌표 — 대역이 건네는 블록은 이것뿐이다 (왕복 없음). */
    private record BlockRef(int x, int y, int z) {
    }

    /** 본문이 끝났다는 표시 — 큐의 맨 끝에 실린다 (앞선 쓰기가 모두 집행된 뒤에야 읽힌다). */
    private record Finished(Object result, Throwable error) {
    }

    private static final class Session<T> implements InvocationHandler {

        private final Plugin plugin;
        private final String name;
        private final World real;
        private final Function<World, T> body;
        private final Consumer<T> onDone;
        private final Consumer<Throwable> onError;
        private final Consumer<String> log;

        private final ArrayBlockingQueue<Object> queue;
        private final SynchronousQueue<Object[]> reply = new SynchronousQueue<>();

        private final World proxyWorld;
        private final String worldName;
        private final int minY;
        private final int maxY;

        private BukkitTask task;
        private Thread worker;
        private long startedAt;
        private long ops;
        private long lastReport;

        Session(Plugin plugin, String name, World real, Function<World, T> body,
                Consumer<T> onDone, Consumer<Throwable> onError, Consumer<String> log) {
            this.plugin = plugin;
            this.name = name;
            this.real = real;
            this.body = body;
            this.onDone = onDone;
            this.onError = onError;
            this.log = log;
            this.queue = new ArrayBlockingQueue<>(Math.max(1024, queueCapacity));
            // 월드의 불변값은 미리 떠 둔다 — 이것 때문에 왕복하면 안 된다 (조성기가 자주 묻는다)
            this.worldName = real.getName();
            this.minY = real.getMinHeight();
            this.maxY = real.getMaxHeight();
            this.proxyWorld = (World) Proxy.newProxyInstance(
                    World.class.getClassLoader(), new Class<?>[]{World.class}, this);
        }

        void start() {
            startedAt = System.nanoTime();
            worker = new Thread(() -> {
                Object result = null;
                Throwable error = null;
                try {
                    result = body.apply(proxyWorld);
                } catch (Throwable t) {
                    error = t;
                }
                try {
                    queue.put(new Finished(result, error));   // 맨 끝 — 앞선 쓰기가 다 나간 뒤에 읽힌다
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "혼천-조성-" + name);
            worker.setDaemon(true);
            worker.start();
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
            if (log != null) {
                log.accept("§7" + name + " — 틱을 나눠 먹는다 (틱당 "
                        + (budgetNs / 1_000_000) + "ms · 서버는 계속 돈다)");
            }
        }

        // ─── 메인 스레드: 예산만큼 집행한다 ───

        private void drain() {
            long t0 = System.nanoTime();
            try {
                while (System.nanoTime() - t0 < budgetNs) {
                    Object o = queue.poll();
                    if (o == null) {
                        // 워커가 순수 계산 중이다 (노이즈·해시·경로). 잠깐만 기다려 본다 —
                        // 예산이 남았는데 손을 놓으면 조성이 공연히 길어진다.
                        long left = budgetNs - (System.nanoTime() - t0);
                        if (left <= 0) {
                            break;
                        }
                        o = queue.poll(Math.min(left, 1_000_000L), TimeUnit.NANOSECONDS);
                        if (o == null) {
                            break;   // 다음 틱에 다시 온다
                        }
                    }
                    if (o instanceof Finished f) {
                        finish(f);
                        return;
                    }
                    Call c = (Call) o;
                    ops++;
                    Object value = null;
                    Throwable err = null;
                    try {
                        value = apply(c);
                    } catch (InvocationTargetException e) {
                        err = e.getCause();
                    } catch (Throwable e) {
                        err = e;
                    }
                    if (c.sync) {
                        // 워커는 지금 reply.take() 에서 기다린다 — 바로 건네진다
                        if (!reply.offer(new Object[]{value, err}, 10, TimeUnit.SECONDS)) {
                            abort(new IllegalStateException("워커가 답을 받아가지 않는다 (교착?)"));
                            return;
                        }
                    } else if (err != null) {
                        abort(err);   // 미룬 쓰기가 터졌다 — 원래 자리로 예외를 돌려줄 수 없으니 여기서 끊는다
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Metrics.record("build:" + name, System.nanoTime() - t0);
            }

            long elapsed = System.nanoTime() - startedAt;
            if (log != null && progressTicks > 0 && ops - lastReport >= 20_000) {
                lastReport = ops;
                log.accept("§8" + name + " — " + (ops / 1000) + "k 연산 · "
                        + (elapsed / 1_000_000_000L) + "초");
            }
            if (elapsed > maxSeconds * 1_000_000_000L) {
                abort(new IllegalStateException("조성이 " + maxSeconds + "초를 넘겼다 — 중단한다"));
            }
        }

        /** 진짜 호출 — 오직 메인 스레드에서만 실행된다. */
        private Object apply(Call c) throws Throwable {
            Object t = c.target;
            // 인자에 실린 좌표표(BlockRef)를 여기서 진짜 Block 으로 편다 — 워커는 못 하는 일이다
            Object[] args = c.args;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof BlockRef r) {
                        if (args == c.args) {
                            args = c.args.clone();
                        }
                        args[i] = real.getBlockAt(r.x(), r.y(), r.z());
                    }
                }
            }
            if (t instanceof BlockRef ref) {
                Block b = real.getBlockAt(ref.x(), ref.y(), ref.z());
                // 뜨거운 경로 — 90만 번 도는 자리다. 리플렉션을 태우지 않는다.
                switch (c.fast) {
                    case Call.SET_TYPE -> {
                        b.setType((Material) args[0], applyPhysics);
                        return null;
                    }
                    case Call.SET_TYPE_PHYS -> {
                        b.setType((Material) args[0], applyPhysics && (Boolean) args[1]);
                        return null;
                    }
                    case Call.SET_DATA -> {
                        b.setBlockData((BlockData) args[0], applyPhysics);
                        return null;
                    }
                    case Call.SET_DATA_PHYS -> {
                        b.setBlockData((BlockData) args[0], applyPhysics && (Boolean) args[1]);
                        return null;
                    }
                    default -> {
                        return c.method.invoke(b, args);
                    }
                }
            }
            return c.method.invoke(t, args);
        }

        private void finish(Finished f) {
            stop();
            long secs = (System.nanoTime() - startedAt) / 1_000_000_000L;
            if (f.error() != null) {
                plugin.getLogger().severe("[틱예산] " + name + " 실패 — " + f.error());
                f.error().printStackTrace();
                if (onError != null) {
                    onError.accept(f.error());
                }
                return;
            }
            if (log != null) {
                log.accept("§7" + name + " 끝 — 연산 " + ops + "건 · " + secs + "초 (서버는 안 멈췄다)");
            }
            plugin.getLogger().info("[틱예산] " + name + " — 연산 " + ops + "건 · " + secs + "초");
            if (onDone != null) {
                @SuppressWarnings("unchecked")
                T result = (T) f.result();
                onDone.accept(result);
            }
        }

        private void abort(Throwable t) {
            stop();
            if (worker != null) {
                worker.interrupt();
            }
            plugin.getLogger().severe("[틱예산] " + name + " 중단 — " + t);
            if (onError != null) {
                onError.accept(t);
            }
        }

        private void stop() {
            if (task != null) {
                task.cancel();
            }
            release(plugin, real);   // 붙잡아 둔 청크를 돌려준다
            BUSY.set(false);
        }

        // ─── 워커 스레드: 대역이 받는 모든 호출은 여기로 온다 ───

        /**
         * 쓰기(void)는 큐에 적고 즉시 돌아간다 — 워커는 안 멈춘다.
         * 읽기(값 반환)는 큐가 비워지기를 기다렸다가 값을 받는다 — <b>이것이 장벽이다</b>.
         * 큐가 FIFO 이므로, 값을 읽는 시점에 세계는 앞선 쓰기를 전부 반영한 상태다 (read-your-writes).
         */
        private Object call(Object target, Method m, Object[] args) throws Throwable {
            boolean sync = m.getReturnType() != void.class;
            int fast = Call.GENERIC;
            if (target instanceof BlockRef) {
                String n = m.getName();
                int argc = args == null ? 0 : args.length;
                if (n.equals("setType")) {
                    fast = argc == 1 ? Call.SET_TYPE : Call.SET_TYPE_PHYS;
                } else if (n.equals("setBlockData")) {
                    fast = argc == 1 ? Call.SET_DATA : Call.SET_DATA_PHYS;
                }
            }
            queue.put(new Call(target, m, args, sync, fast));   // 큐가 차면 여기서 막힌다 = 백프레셔
            if (!sync) {
                return null;
            }
            Object[] r = reply.take();
            if (r[1] != null) {
                throw (Throwable) r[1];
            }
            return r[0];
        }

        /** 대역 월드 */
        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            int argc = args == null ? 0 : args.length;
            // 블록 접근은 절대 왕복하지 않는다 — 좌표만 받아 대역 블록을 만든다 (90만 번 도는 자리)
            if (n.equals("getBlockAt")) {
                if (argc == 3) {
                    return blockProxy((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                }
                if (argc == 1 && args[0] instanceof Location l) {
                    return blockProxy(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                }
            }
            // 월드의 불변값 — 미리 떠 뒀다
            switch (n) {
                case "getName" -> {
                    return worldName;
                }
                case "getMinHeight" -> {
                    return minY;
                }
                case "getMaxHeight" -> {
                    return maxY;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return worldName.hashCode();
                }
                case "toString" -> {
                    return "SlicedWorld[" + worldName + "]";
                }
                default -> { }
            }
            return wrap(call(real, m, unwrap(args)));
        }

        private Block blockProxy(int x, int y, int z) {
            return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                    new Class<?>[]{Block.class}, new BlockHandler(x, y, z));
        }

        /** 대역 블록 — 좌표만 안다. 세계를 만지는 건 큐가 한다. */
        private final class BlockHandler implements InvocationHandler {
            private final int x;
            private final int y;
            private final int z;

            BlockHandler(int x, int y, int z) {
                this.x = x;
                this.y = y;
                this.z = z;
            }

            BlockRef ref() {
                return new BlockRef(x, y, z);
            }

            @Override
            public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                String n = m.getName();
                int argc = args == null ? 0 : args.length;
                // 좌표에서 곧바로 답이 나오는 것 — 왕복하지 않는다
                switch (n) {
                    case "getX" -> {
                        return x;
                    }
                    case "getY" -> {
                        return y;
                    }
                    case "getZ" -> {
                        return z;
                    }
                    case "getWorld" -> {
                        return proxyWorld;
                    }
                    case "getLocation" -> {
                        if (argc == 0) {
                            return new Location(proxyWorld, x, y, z);
                        }
                    }
                    case "getRelative" -> {
                        if (argc == 1 && args[0] instanceof BlockFace f) {
                            return blockProxy(x + f.getModX(), y + f.getModY(), z + f.getModZ());
                        }
                        if (argc == 2 && args[0] instanceof BlockFace f && args[1] instanceof Integer d) {
                            return blockProxy(x + f.getModX() * d, y + f.getModY() * d, z + f.getModZ() * d);
                        }
                        if (argc == 3) {
                            return blockProxy(x + (Integer) args[0], y + (Integer) args[1],
                                    z + (Integer) args[2]);
                        }
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return (x * 31 + y) * 31 + z;
                    }
                    case "toString" -> {
                        return "SlicedBlock[" + x + "," + y + "," + z + "]";
                    }
                    default -> { }
                }
                return wrap(call(new BlockRef(x, y, z), m, unwrap(args)));
            }
        }

        /** 메인 스레드가 돌려준 살아 있는 Bukkit 객체 — 워커가 직접 만지면 안 되므로 대역으로 싼다. */
        private final class TargetHandler implements InvocationHandler {
            private final Object target;

            TargetHandler(Object target) {
                this.target = target;
            }

            Object target() {
                return target;
            }

            @Override
            public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                switch (m.getName()) {
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(target);
                    }
                    case "toString" -> {
                        return "Sliced[" + target.getClass().getSimpleName() + "]";
                    }
                    default -> {
                        return wrap(call(target, m, unwrap(args)));
                    }
                }
            }
        }

        /**
         * 값을 싸는 규칙 — <b>세계를 만질 수 있는 것만</b> 싼다.
         * {@link BlockState}(간판·선반) · {@link Entity}(NPC) · {@link Chunk} · {@link Block} 이 그것이다.
         * {@link BlockData} · {@link Material} · {@link Location} 같은 값 객체는 순수하므로 그대로 준다
         * (싸면 오히려 진짜 API 에 되먹일 때 캐스팅이 깨진다).
         */
        private Object wrap(Object o) {
            if (o == null) {
                return null;
            }
            if (o instanceof World) {
                return proxyWorld;
            }
            // 컬렉션도 편다 — world.getNearbyEntities(box) 는 **살아 있는 엔티티**를 담아 돌려준다.
            // 안 싸면 조성기가 워커 스레드에서 e.remove() 를 부른다 (clearNpcs — F29 재조성 정리).
            // 그건 메인 스레드만 할 수 있는 일이다.
            if (o instanceof java.util.Collection<?> col) {
                List<Object> out = new ArrayList<>(col.size());
                boolean changed = false;
                for (Object e : col) {
                    Object w = wrap(e);
                    changed |= w != e;
                    out.add(w);
                }
                return changed ? out : o;
            }
            if (o instanceof BlockState || o instanceof Entity || o instanceof Chunk || o instanceof Block) {
                Class<?>[] ifaces = interfaces(o.getClass());
                if (ifaces.length == 0) {
                    return o;
                }
                return Proxy.newProxyInstance(o.getClass().getClassLoader(), ifaces,
                        new TargetHandler(o));
            }
            return o;
        }

        /** 대역을 진짜로 되돌린다 — 진짜 API 에 대역을 넘기면 CraftBukkit 이 캐스팅에서 죽는다. */
        private Object[] unwrap(Object[] args) {
            if (args == null) {
                return null;
            }
            Object[] out = null;
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                Object u = a;
                if (a != null && Proxy.isProxyClass(a.getClass())) {
                    InvocationHandler h = Proxy.getInvocationHandler(a);
                    if (h == this) {
                        u = real;                       // 대역 월드 → 진짜 월드
                    } else if (h instanceof Session<?>.TargetHandler th) {
                        u = th.target();
                    } else if (h instanceof Session<?>.BlockHandler bh) {
                        // 진짜 Block 은 메인 스레드만 집을 수 있다 — 좌표만 실어 보내고
                        // apply() 가 메인에서 실체화한다 (워커는 세계를 만지지 않는다).
                        u = ((BlockHandler) bh).ref();
                    }
                } else if (a instanceof Location l && l.getWorld() != null
                        && Proxy.isProxyClass(l.getWorld().getClass())) {
                    u = rebind(l, real);                // 대역 월드를 문 Location → 진짜 월드
                }
                if (u != a) {
                    if (out == null) {
                        out = args.clone();
                    }
                    out[i] = u;
                }
            }
            return out == null ? args : out;
        }
    }

    /** 클래스가 구현한 모든 Bukkit 인터페이스 (상위까지 훑는다 — Sign 은 TileState 를 통해 온다). */
    private static Class<?>[] interfaces(Class<?> c) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            collect(k, out);
        }
        return out.toArray(new Class<?>[0]);
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (i.getName().startsWith("org.bukkit.") && out.add(i)) {
                collect(i, out);
            }
        }
    }
}
