package com.honcheon.mvt;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>초기화의 눈 ② — 지운 것이 되살아나지 않는가</b> (마크 쪽).
 *
 * <p>사용자 보고: <i>"디스코드에서 전부 되돌렸는데 <b>마크의 데이터는 초기화되지 않았습니다</b>"</i>
 *
 * <h2>무슨 일이 있었나 (로그·파일로 확인한 사실)</h2>
 * <pre>
 *   01:43:10  봇이 world_state.json 을 굽는다 — 그 안에 05909c69 의 sheet·links 가 있다
 *   01:46:33  디스코드에서 「전부」 초기화 → 봇은 제 장부(mvt_link·characters)를 지웠다 ✔
 *   01:46:35  마크가 지운다: 원장(메모리) · ledgers.yml 의 그 절 · playerdata ✔  ← **실제로 지워졌다**
 *   01:49:42  재접속 → SkillListener.syncSheet 가 **01:43 의 낡은 스냅숏**에서 시트를 읽어 싣는다
 *             → PlayerLedger.applySheet → linked = true
 *   01:54:36  5분 타이머 saveLedgers() 가 그 메모리를 파일에 굽는다
 *             → ledgers.yml 에 05909c69 가 **linked: true 로 되살아났다**
 *             → Antechamber.onJoin 이 "강호에 든 자"로 읽어 **나루를 건너뛰고 청하현에 떨어뜨렸다**
 * </pre>
 * ★ <b>마크가 지운 것을 마크가 캐시에서 되살렸다.</b> 봇의 장부는 깨끗했다 (지금도 비어 있다).
 *
 * <h2>고침 — 묘비(墓碑)</h2>
 * 초기화는 <b>지운 시각</b>을 적어 둔다({@link WorldBridge#forget}). 그보다 <b>낡은 스냅숏</b>은 그 몸에
 * 대해 입을 다문다({@link WorldBridge.State#sheet}). 봇이 새 스냅숏을 구우면 묘비는 스스로 물러난다 —
 * <b>정본은 언제나 봇이다.</b>
 *
 * <h2>이 시험이 만지는 것 (거짓말하지 않는다)</h2>
 * <ul>
 *   <li><b>진짜 코드</b>: {@link WorldBridge#ingest} (스냅숏 판독) · {@link WorldBridge#forget} ·
 *       {@link WorldBridge.State#sheet} · {@link PlayerLedger#applySheet} · YamlConfiguration 원장 파일</li>
 *   <li><b>흉내</b>: {@code SkillListener.syncSheet} 의 <b>세 줄</b>(시트가 없으면 setLinked(false),
 *       있으면 applySheet)과 {@code HoncheonMvt.saveLedger} 의 <b>합쳐 굽기</b>. 그 둘은 Bukkit 서버를
 *       요구하므로 여기서는 <b>같은 API 로 같은 순서를</b> 되짚는다. 되살아나는 사슬이 정확히 그 둘이다.</li>
 * </ul>
 *
 * <h2>★★ 일부러 어긴다</h2>
 * 묘비를 <b>걷어 내고</b> 같은 길을 다시 걷는다. 그러면 <b>죽은 자가 걸어 나와야 한다</b> —
 * 안 나오면 이 시험은 아무것도 재고 있지 않은 것이다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name '*.jar' | grep -E 'adventure-key|adventure-api|examination-api|snakeyaml-2.2' \
 *             | grep -v 26.1.2 | tr '\n' ':')$(find run/mvt/libraries -name 'guava-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/reset-eye -cp "$CP" tools/ResetTombstoneSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/reset-eye" com.honcheon.mvt.ResetTombstoneSelfTest
 * </pre>
 */
public final class ResetTombstoneSelfTest {

    /** 사용자의 몸 — 실제로 되살아났던 그 uuid 다 */
    private static final UUID BODY = UUID.fromString("05909c69-0126-490a-9448-649a19702637");

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("honcheon-reset-eye2");
        Path graves = tmp.resolve("graves.json");
        Path ledgers = tmp.resolve("ledgers.yml");
        System.out.println("초기화의 눈 ② — 되살아남 · " + tmp + "\n");

        WorldBridge.gravesStore(graves);
        WorldBridge.razeGraves();   // 앞선 시험의 묘비를 물려받지 않는다

        // ── 01:43 — 봇이 스냅숏을 굽는다. 그 안에 이 몸이 살아 있다 ──
        long t0 = 1_783_960_990_846L;          // 실제 world_state.json 의 generated_at
        WorldBridge.ingest(snapshot(t0, true), t0);
        check("① 초기화 전 — 스냅숏에 시트가 있다", WorldBridge.state().sheet(BODY) != null, "");
        check("① 초기화 전 — 접합된 몸이다", WorldBridge.state().linkedName(BODY) != null, "");

        // 원장 파일에도 그 절이 있다 (saveLedger 가 구워 둔 상태)
        PlayerLedger before = new PlayerLedger();
        before.applySheet(WorldBridge.state().sheet(BODY));
        bake(ledgers, BODY, before);
        check("① 초기화 전 — ledgers.yml 에 그 절이 있다 (linked: true)",
                section(ledgers, BODY) != null && section(ledgers, BODY).getBoolean("linked"), "");

        // ── 01:46 — 초기화. Reset.wipe 가 하는 그대로: 묘비 → 메모리 → 파일 ──
        long wipedAt = t0 + 203_000L;          // 스냅숏보다 **3분 뒤** (실제 간격)
        WorldBridge.forget(BODY, wipedAt);     // ★ 진짜 코드
        drop(ledgers, BODY);                   // Reset.dropSection 과 같은 일 (YamlConfiguration)
        check("② 초기화 — ledgers.yml 에서 그 절이 사라졌다", section(ledgers, BODY) == null,
                "지우지도 못했다");

        // ── 01:49 — 재접속. **여기가 병이 나던 자리다** ──
        //    봇은 아직 새 스냅숏을 굽지 않았다 (실측: 01:55 까지도 01:43 파일 그대로였다)
        check("★★ ③ 재접속 — 낡은 스냅숏의 시트를 **안 싣는다**",
                WorldBridge.state().sheet(BODY) == null,
                "낡은 캐시가 지운 몸을 되살렸다 — 바로 그 병이다");
        check("★★ ③ 재접속 — 낡은 스냅숏의 접합을 **안 읽는다** (나루로 간다)",
                WorldBridge.state().linkedName(BODY) == null, "");

        PlayerLedger rejoin = syncSheet(new PlayerLedger());   // SkillListener.syncSheet 의 세 줄
        check("★★ ③ 재접속 — 원장이 linked 로 살아나지 않는다 (Antechamber 가 나루로 보낸다)",
                !rejoin.linked(), "linked=true 로 되살아났다 — 청하현에 떨어진다");
        bake(ledgers, BODY, rejoin);            // 5분 타이머(saveLedgers)가 굽는 그 자리
        check("★★ ③ 5분 뒤 — ledgers.yml 에 linked:true 가 **되살아나지 않는다**",
                section(ledgers, BODY) == null || !section(ledgers, BODY).getBoolean("linked"),
                "파일에 linked: true 가 되살아났다");

        // ── ★★ 일부러 어긴다 — 묘비를 걷어 낸다. 그러면 죽은 자가 걸어 나와야 한다 ──
        WorldBridge.razeGraves();
        check("★★ 【일부러 어긴다】 묘비를 걷으면 낡은 스냅숏이 **정말로** 되살린다",
                WorldBridge.state().sheet(BODY) != null,
                "묘비 없이도 안 되살아났다 — 이 시험은 아무것도 재고 있지 않다");
        PlayerLedger zombie = syncSheet(new PlayerLedger());
        check("★★ 【일부러 어긴다】 그때 원장은 linked=true 가 된다 (사용자가 겪은 그것)",
                zombie.linked(), "");
        bake(ledgers, BODY, zombie);
        check("★★ 【일부러 어긴다】 그리고 파일에 되살아난다 (money 도 그대로)",
                section(ledgers, BODY) != null && section(ledgers, BODY).getInt("money") == 228,
                "되살아난 값이 다르다 — 시험이 실제 사슬을 안 밟고 있다");
        drop(ledgers, BODY);

        // ── 재기동 — 묘비는 파일에 남아야 한다 (봇이 새로 굽기 전에 서버가 내려갈 수 있다) ──
        WorldBridge.razeGraves();               // 메모리를 비운다 = 서버가 꺼졌다
        WorldBridge.gravesStore(graves);        // 다시 켠다 — 파일에서 읽는다
        check("④ 재기동 — 묘비가 파일에서 살아 돌아온다",
                WorldBridge.forgotten(BODY), "재기동 한 번에 묘비가 사라졌다");
        check("④ 재기동 — 낡은 스냅숏은 여전히 못 되살린다",
                WorldBridge.state().sheet(BODY) == null, "");

        // ── 봇이 **새** 스냅숏을 굽는다 (지운 뒤에). 이제 정본은 봇이다 ──
        long t2 = wipedAt + 60_000L;
        WorldBridge.ingest(snapshot(t2, false), t2);   // 봇의 장부에서 지워졌으니 sheet 에 없다
        check("⑤ 봇의 새 스냅숏 — 그 안에 이 몸이 없다 → 여전히 시트 없음",
                WorldBridge.state().sheet(BODY) == null, "");
        check("★ ⑤ 묘비는 스스로 물러난다 (영구 추방이 아니다 — 정본은 봇이다)",
                !WorldBridge.forgotten(BODY), "묘비가 남아 새 캐릭터의 앞을 막는다");
        check("★ ⑤ 물러난 묘비는 파일에서도 지워진다",
                !Files.readString(graves, StandardCharsets.UTF_8).contains(BODY.toString()),
                "파일에 묘비가 남았다");

        // ── 같은 몸이 **새 캐릭터**로 다시 접합한다 — 막히면 안 된다 ──
        long t3 = t2 + 60_000L;
        WorldBridge.ingest(snapshot(t3, true), t3);
        check("★★ ⑥ 새 캐릭터로 다시 접합하면 시트가 실린다 (묘비가 앞을 막지 않는다)",
                WorldBridge.state().sheet(BODY) != null,
                "초기화가 그 몸을 영영 못 쓰게 만들었다 — 이것도 결함이다");

        // ── ★ 봇이 generated_at 을 안 실어 줄 때 (파일 시각으로 갈음한다) ──
        WorldBridge.razeGraves();
        long t4 = t3 + 60_000L;
        WorldBridge.forget(BODY, t4);
        WorldBridge.ingest(snapshotNoStamp(), t4 - 10_000L);   // generated_at 없음 · 파일은 더 낡았다
        check("⑦ generated_at 이 없으면 **파일 시각**으로 잰다 (그래도 안 되살아난다)",
                WorldBridge.state().sheet(BODY) == null,
                "시각을 모르면 되살렸다 — 모르면 죽은 쪽이 안전하다");
        WorldBridge.ingest(snapshotNoStamp(), t4 + 10_000L);   // 파일이 초기화보다 새것이다
        check("⑦ 파일 시각이 초기화보다 새것이면 묘비가 물러난다",
                WorldBridge.state().sheet(BODY) != null, "");

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("✔ 눈 " + passed + "개 — 전부 통과. 지운 것은 되살아나지 않는다");
        } else {
            System.out.println("✖ 실패 " + failures.size() + " / 통과 " + passed);
            failures.forEach(f -> System.out.println("  ✖ " + f));
            System.exit(1);
        }
    }

    // ══════════ 되살아나던 사슬 — SkillListener.syncSheet 의 세 줄 ══════════

    /** {@code SkillListener.syncSheet} 의 알맹이 (Player 를 걷어 낸 것) — 이 세 줄이 몸을 되살렸다 */
    private static PlayerLedger syncSheet(PlayerLedger ledger) {
        WorldBridge.Sheet sheet = WorldBridge.state().sheet(BODY);
        if (sheet == null) {
            ledger.setLinked(false);
            return ledger;
        }
        ledger.applySheet(sheet);
        return ledger;
    }

    /** {@code HoncheonMvt.saveLedger} 와 같은 일 — 파일에 <b>합쳐</b> 굽는다 (그래서 지운 줄이 되살아났다) */
    private static void bake(Path file, UUID uuid, PlayerLedger led) throws Exception {
        File f = file.toFile();
        YamlConfiguration yml = f.isFile()
                ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        led.save(yml.createSection(uuid.toString()));
        yml.save(f);
    }

    /** {@code Reset.dropSection} 과 같은 일 — 그 사람의 절만 떼어 낸다 */
    private static void drop(Path file, UUID uuid) throws Exception {
        File f = file.toFile();
        if (!f.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        yml.set(uuid.toString(), null);
        yml.save(f);
    }

    private static org.bukkit.configuration.ConfigurationSection section(Path file, UUID uuid) {
        File f = file.toFile();
        return f.isFile()
                ? YamlConfiguration.loadConfiguration(f).getConfigurationSection(uuid.toString())
                : null;
    }

    // ══════════ 스냅숏 — ★ **봇이 실제로 굽는 그 JSON** (run/bridge/world_state.json 에서 떠 왔다) ══════════

    private static String snapshot(long generatedAt, boolean withBody) {
        return "{\"world_day\":24,\"generated_at\":" + generatedAt + ","
                + "\"rumor_tags\":[\"도적\"],\"populace_reactions\":[\"도적_소문\"],"
                + "\"region\":{\"치안\":58},\"wanted\":{},\"favor\":{},"
                + "\"links\":" + (withBody
                        ? "{\"" + BODY + "\":{\"name\":\"디돈\",\"realm\":\"범인\",\"character_id\":4}}"
                        : "{}") + ","
                + "\"bounty\":{},\"sheet\":" + (withBody
                        ? "{\"" + BODY + "\":{\"realm\":\"범인\",\"money\":228,\"house\":\"상가의_자식\","
                          + "\"gender\":\"남\",\"left_house\":false,\"birth_rank\":null,"
                          + "\"start_anchor\":\"장터\",\"kin\":[],"
                          + "\"attrs\":{\"근력\":3.0,\"민첩\":3.0,\"체력\":2.0011111111111113,"
                          + "\"내공\":2.0,\"감각\":3.0,\"화술\":3.0,\"지혜\":3.0},"
                          + "\"simbeop\":null,\"naegong\":0.0,\"primary_art\":null,"
                          + "\"skill_days\":{\"흥정\":90.0},\"marks_실전\":3,\"marks_사선\":0}}"
                        : "{}") + ","
                + "\"thresholds\":{\"wanted\":8},\"discord\":{}}";
    }

    /** ★ 봇이 시각을 안 실어 줄 수도 있다 — 그때는 파일의 시각이 대타다 */
    private static String snapshotNoStamp() {
        String s = snapshot(0L, true);
        return s.replace("\"generated_at\":0,", "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✔ " + what);
        } else {
            failures.add(what + (detail.isBlank() ? "" : " — " + detail));
            System.out.println("  ✖ " + what + (detail.isBlank() ? "" : " — " + detail));
        }
    }
}
