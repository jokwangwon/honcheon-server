package com.honcheon.mvt;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>연무장 금고의 눈</b> — 맡긴 것이 <b>리붓을 넘어 살아남는가</b>.
 *
 * <p>연무장은 들어온 사람에게서 진짜 장부·무공 상태·짐을 <b>떼어 낸다</b>. 그것이 메모리에만 있던 동안,
 * 서버가 한 번 내려가면 <b>진짜 짐이 영영 사라졌다</b> (사용자가 겪은 "이상한 공간" = 데이터 손실).
 * 이제 {@code dojang.yml} 금고에 적는다. <b>눈을 만들었으면 시험해야 한다</b> — 그래서 이 파일이 있다.
 *
 * <p><b>서버를 켜지 않고 돈다.</b> 금고의 알맹이({@link Dojang#writeVault}·{@link Dojang#readVault})는
 * Bukkit 월드도 서버도 안 본다 — {@code ConfigurationSection} 과 숫자·문자열뿐이다 (그래서 돌아갈 자리를
 * {@code Location} 이 아니라 {@link Dojang.Spot} 으로, 짐을 Base64 한 줄로 적었다). 짐의 부호화
 * ({@code ItemStack.serializeItemsAsBytes})만 서버를 타므로, 여기서는 <b>불투명한 문자열</b>로 다룬다 —
 * 금고가 그 줄을 잃지 않는가가 이 시험의 물음이다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name '*.jar' | grep -E 'adventure-key|adventure-api|examination-api|snakeyaml-2.2' \
 *             | grep -v 26.1.2 | tr '\n' ':')$(find run/mvt/libraries -name 'guava-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/dojang-eye -cp "$CP" tools/DojangVaultSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/dojang-eye" com.honcheon.mvt.DojangVaultSelfTest
 * </pre>
 *
 * <p>(단일 파일 실행 {@code java Foo.java} 로는 안 된다 — 그 모드의 클래스로더는 <b>다른 런타임 패키지</b>라
 * 같은 패키지의 {@code Dojang.Deposit} 에 접근할 수 없다. 그래서 컴파일해서 같은 로더에 올린다.)
 */
public final class DojangVaultSelfTest {

    private static int failed;
    private static int passed;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("dojang-vault-eye").toFile();
        File vault = new File(dir, Dojang.VAULT_FILE);

        rebootKeepsWhatWasDeposited(vault);
        halfWrittenFileNeverReplacesTheGood(vault);
        theWorldLedgerIsNeverTheTestLedger();
        leavingClearsTheDepositButKeepsTheTest(vault);
        emptyHandsAndMissingOriginSurvive(vault);

        System.out.println();
        System.out.println(failed == 0
                ? "✔ 금고의 눈 — " + passed + "건 전부 통과 (맡긴 것은 리붓을 넘는다)"
                : "✘ 금고의 눈 — " + failed + "건 실패 / " + (passed + failed) + "건");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ① ★ 일부러 어긴다 — 맡긴 채로 "리붓"을 흉내 낸다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 진짜 장부·무공·짐을 맡긴 상태에서 <b>메모리를 통째로 버린다</b> (= 서버가 죽었다).
     * 디스크에서 다시 열었을 때 <b>진짜 것이 그대로 있어야 한다</b>. 이것이 이 시험의 심장이다.
     */
    private static void rebootKeepsWhatWasDeposited(File vault) throws Exception {
        UUID id = UUID.fromString("05909c69-0126-490a-9448-649a19702637");

        // ─ 세계의 나 (일류·태조장권 643일·7971문) 가 연무장에 들어선다 ─
        Dojang.Deposit before = new Dojang.Deposit();
        before.inside = true;
        before.origin = new Dojang.Spot("honcheon", 128.5, 71.0, -64.5, 90.0f, 0f);
        before.invSize = 41;
        PlayerLedger real = new PlayerLedger();
        real.earn(7971);
        real.grant("태조장권", 643.058203125);
        real.grantAttr("근력", 2.0005555555555556);
        real.setNaegong(0.3388888888888889);
        real.setSimbeop("현천토납법");
        real.setSegments("초식", 3);
        real.pendTrain(1.5);            // 아직 다리를 못 건넌 증분 — 이것까지 살아야 한다
        before.realLedger = real;
        SkillEngine.State rs = new SkillEngine.State();
        rs.realm = "일류";
        rs.naegong = 0.3388888888888889;
        rs.energy = 12;
        rs.armed = "발경";
        before.realState = rs;
        before.realItems = "REAL:칠성검,비급,은자7971";   // (진짜 서버에서는 ItemStack Base64)
        // 시험장의 것 — 화경으로 갈아 끼우고 신병을 쥐었다
        PlayerLedger test = new PlayerLedger();
        test.grant("육합검", 9999.0);
        before.testLedger = test;
        SkillEngine.State ts = new SkillEngine.State();
        ts.realm = "화경";
        ts.energy = 300;
        before.testState = ts;
        before.testItems = "TEST:신병";

        Map<UUID, Dojang.Deposit> memory = new LinkedHashMap<>();
        memory.put(id, before);
        Dojang.writeVault(vault, memory);   // ★ 맡는 순간 적는다

        // ─── ☠ 서버가 죽는다. 메모리에 있던 것은 전부 사라진다 ───
        memory = null;
        System.gc();

        Map<UUID, Dojang.Deposit> after = Dojang.readVault(vault);
        Dojang.Deposit d = after.get(id);
        eq("리붓 뒤 — 금고가 이 사람을 기억한다", true, d != null);
        if (d == null) {
            return;
        }
        eq("리붓 뒤 — 아직 맡고 있다 (inside)", true, d.inside);
        eq("★ 리붓 뒤 — 진짜 짐", "REAL:칠성검,비급,은자7971", d.realItems);
        eq("★ 리붓 뒤 — 진짜 소지금", 7971, d.realLedger.money());
        eq("★ 리붓 뒤 — 진짜 숙련 (태조장권 일치)", 643.058203125, d.realLedger.daysOf("태조장권"));
        eq("★ 리붓 뒤 — 진짜 능력치 (근력)", 2.0005555555555556, d.realLedger.attr("근력"));
        eq("★ 리붓 뒤 — 진짜 내공", 0.3388888888888889, d.realLedger.naegong());
        eq("★ 리붓 뒤 — 진짜 심법", "현천토납법", d.realLedger.simbeop());
        eq("★ 리붓 뒤 — 수련 배분 (몸의 것 — 봇이 모른다)", 3,
                d.realLedger.curriculum().getOrDefault("초식", 0));
        eq("★ 리붓 뒤 — 미결 증분 (아직 다리를 못 건넌 수련)", 1.5,
                d.realLedger.takePending().trainDays());
        eq("★ 리붓 뒤 — 진짜 경지", "일류", d.realState.realm);
        eq("★ 리붓 뒤 — 진짜 내력", 12, d.realState.energy);
        eq("★ 리붓 뒤 — 진짜 격 태세", "발경", d.realState.armed);
        eq("★ 리붓 뒤 — 돌아갈 자리 (월드)", "honcheon", d.origin.world());
        eq("★ 리붓 뒤 — 돌아갈 자리 (x)", 128.5, d.origin.x());
        eq("★ 리붓 뒤 — 돌아갈 자리 (y)", 71.0, d.origin.y());
        eq("리붓 뒤 — 시험의 장부는 시험장에 남는다", 9999.0, d.testLedger.daysOf("육합검"));
        eq("리붓 뒤 — 시험의 경지도 남는다", "화경", d.testState.realm);
        eq("리붓 뒤 — 시험의 짐도 남는다", "TEST:신병", d.testItems);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ② 원자성 — 반쯤 쓰다 죽으면 그것도 손실이다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@code yml.save(file)} 는 <b>파일을 먼저 비우고</b> 쓴다. 그 순간 죽으면 금고가 반쪽이 된다 —
     * 반쪽 금고는 없는 금고보다 나쁘다 (읽히는데 틀린다). 그래서 옆에 다 쓰고 <b>이름만 바꾼다</b>.
     *
     * <p>여기서 흉내 내는 것: 임시 파일에 <b>쓰레기가 남은 채</b> 서버가 죽었다. 그래도 진짜 금고는
     * 온전해야 하고, 다음 저장이 그 쓰레기를 치우고 원자적으로 갈아 끼워야 한다.
     */
    private static void halfWrittenFileNeverReplacesTheGood(File vault) throws Exception {
        UUID id = UUID.randomUUID();
        Dojang.Deposit d = new Dojang.Deposit();
        d.inside = true;
        d.realItems = "REAL:온전한금고";
        d.realLedger = new PlayerLedger();
        d.realLedger.earn(100);
        Map<UUID, Dojang.Deposit> one = new LinkedHashMap<>();
        one.put(id, d);
        Dojang.writeVault(vault, one);

        File tmp = new File(vault.getParentFile(), Dojang.VAULT_FILE + ".tmp");
        Files.writeString(tmp.toPath(), "이것은: [반쯤 쓰다 죽은 쓰레기\n  깨진: yaml");   // ☠ 죽는 순간

        eq("★ 반쪽 임시 파일이 있어도 — 금고는 온전하다", "REAL:온전한금고",
                Dojang.readVault(vault).get(id).realItems);

        d.realItems = "REAL:새금고";
        Dojang.writeVault(vault, one);
        eq("★ 다음 저장이 원자적으로 갈아 끼운다", "REAL:새금고",
                Dojang.readVault(vault).get(id).realItems);
        eq("★ 임시 파일은 남지 않는다 (옮겨졌다 — 복사가 아니다)", false, tmp.isFile());

        // ★ 금고 파일 자체가 깨졌다면 — **빈 금고인 척하면 안 된다.**
        //   (Bukkit 의 loadConfiguration 은 깨진 파일에 빈 설정을 준다. 그것을 믿으면 "맡긴 것이 없다"고
        //    판단하고 **다음 저장이 증거까지 덮는다** — 사람의 짐이 그렇게 사라진다. 그래서 던진다.)
        File broken = new File(vault.getParentFile(), "broken.yml");
        Files.writeString(broken.toPath(), "\t깨진: [yaml\n  들여쓰기: 엉망");
        boolean threw = false;
        try {
            Dojang.readVault(broken);
        } catch (Exception e) {
            threw = true;
        }
        eq("★ 깨진 금고 — 빈 금고인 척하지 않고 터뜨린다 (덮어쓰기를 막는다)", true, threw);
        eq("없는 금고 파일 — 조용히 빈 금고 (첫 기동)", 0,
                Dojang.readVault(new File(vault.getParentFile(), "없다.yml")).size());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ③ ledgers.yml 오염 — 리붓이 없어도 손실이었다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@code HoncheonMvt.saveLedgers} 는 5분마다 {@code ledgers} 맵을 굽는다. 연무장 안에서는 그 맵에
     * <b>시험용 장부</b>가 들어 있었다 — 즉 <b>5분마다 디스크의 진짜 장부가 빈 장부로 덮였다</b>.
     * 이제 굽기 직전에 금고에 묻는다. 그 판단을 여기서 잰다.
     */
    private static void theWorldLedgerIsNeverTheTestLedger() {
        PlayerLedger real = new PlayerLedger();
        real.earn(7971);
        PlayerLedger test = new PlayerLedger();
        test.earn(0);

        Dojang.Deposit inside = new Dojang.Deposit();
        inside.inside = true;
        inside.realLedger = real;
        inside.testLedger = test;
        eq("★ 연무장 안 — ledgers.yml 에는 **진짜** 장부를 굽는다", 7971,
                Dojang.worldLedger(inside, test).money());

        Dojang.Deposit left = new Dojang.Deposit();   // 나갔다 (시험 기록만 남았다)
        left.inside = false;
        left.testLedger = test;
        eq("연무장 밖 — 살아 있는 장부를 그대로 굽는다", 7971,
                Dojang.worldLedger(left, real).money());
        eq("금고에 없는 사람 — 살아 있는 장부를 그대로 굽는다", 7971,
                Dojang.worldLedger((Dojang.Deposit) null, real).money());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ④ 돌려준 뒤 — 두 번 돌려주면 짐이 복제된다
    // ══════════════════════════════════════════════════════════════════════

    private static void leavingClearsTheDepositButKeepsTheTest(File vault) throws Exception {
        UUID id = UUID.randomUUID();
        Dojang.Deposit d = new Dojang.Deposit();
        d.inside = false;               // 돌려줬다 (Dojang.giveBack 이 이렇게 만든다)
        d.realLedger = null;
        d.realState = null;
        d.realItems = null;
        d.origin = null;
        d.testLedger = new PlayerLedger();
        d.testLedger.grant("육합검", 30.0);
        d.testItems = "TEST:신병";
        Map<UUID, Dojang.Deposit> v = new LinkedHashMap<>();
        v.put(id, d);
        Dojang.writeVault(vault, v);

        Dojang.Deposit back = Dojang.readVault(vault).get(id);
        eq("★ 돌려준 뒤 — 맡은 것이 없다 (두 번 돌려줄 수 없다 = 짐 복제 불가)", false, back.inside);
        eq("★ 돌려준 뒤 — 맡긴 짐이 금고에 남아 있지 않다", null, back.realItems);
        eq("돌려준 뒤 — 시험의 짐·장부는 남는다 (다시 들어오면 이어진다)", "TEST:신병", back.testItems);
        eq("돌려준 뒤 — 시험의 수련도 남는다", 30.0, back.testLedger.daysOf("육합검"));
        eq("돌려준 뒤 — 맡은 것 없음", false, back.holdsAnything());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ⑤ 가장자리 — 빈손 · 돌아갈 자리 없음 (지어내지 않는다)
    // ══════════════════════════════════════════════════════════════════════

    private static void emptyHandsAndMissingOriginSurvive(File vault) throws Exception {
        UUID id = UUID.randomUUID();
        Dojang.Deposit d = new Dojang.Deposit();
        d.inside = true;
        d.invSize = 36;
        d.realLedger = new PlayerLedger();
        d.realState = new SkillEngine.State();   // 경지 null — 코드가 경지를 지어내지 않는다
        d.realItems = null;                      // 빈손으로 들어왔다
        d.origin = null;                         // 돌아갈 자리를 모른다 (금고가 옛 형식이었다)
        Map<UUID, Dojang.Deposit> v = new LinkedHashMap<>();
        v.put(id, d);
        Dojang.writeVault(vault, v);

        Dojang.Deposit back = Dojang.readVault(vault).get(id);
        eq("빈손 — 짐 없음이 그대로 읽힌다 (빈 문자열이 아니다)", null, back.realItems);
        eq("빈손 — 짐 칸 수는 기억한다 (되돌릴 때 쓴다)", 36, back.invSize);
        eq("돌아갈 자리 없음 — 지어내지 않는다 (부르는 쪽이 장터를 찾는다)", null, back.origin);
        eq("경지 없음 — null 그대로 (하드코딩 '이류'가 서 있던 자리)", null, back.realState.realm);
        eq("맡은 것이 있다 — 장부·상태가 있으므로", true, back.holdsAnything());

        // 시험용: yaml 이 사람이 읽을 수 있는가 (관리자가 금고를 손으로 열 수 있어야 한다)
        String text = YamlConfiguration.loadConfiguration(vault).saveToString();
        eq("금고는 사람이 읽는다 (uuid 가 키)", true, text.contains(id.toString()));
    }

    // ─── 자(尺) ───

    private static void eq(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) {
            passed++;
            System.out.println("  ✔ " + what);
        } else {
            failed++;
            System.out.println("  ✘ " + what + "  — 기대 [" + want + "] 실제 [" + got + "]");
        }
    }
}
