package com.honcheon.mvt;

/**
 * 월아산 자동 연발의 눈 — <b>좌클릭 한 번 = 공격 한 번, 홀드는 병기의 박자를 넘지 못한다.</b>
 *
 * <p>실사용 발견 (2026-07-17): "월아산으로 좌클릭 시 자동으로 계속 공격이 나간다."
 * 기전 — 좌클릭 홀드로 캐는 동안 클라이언트가 매 틱 스윙 패킷을 보내고, Paper 는 레이트레이스가
 * 빗나간 <b>모든</b> 스윙을 LEFT_CLICK_AIR 로 합성한다 (paper-1.21.11
 * {@code ServerGamePacketListenerImpl.handleAnimate} 바이트코드 실측). 블록이 깨진 틱의 스윙은
 * 갓 뚫린 구멍을 지나 빗나가고, 월아산(유일한 삽 병기)은 땅을 2~4틱에 깨서 그 합성이 초당 여러 번
 * 왔다 — 플러그인은 그것을 전부 공격 입력으로 읽었다. 수리 — {@link AttackRhythm} 못 두 개:
 * ① 채굴의 그림자(깬 직후의 허공 스윙은 공격이 아니다) ② 병기의 박자(기본 초식의 획은 계열
 * 공속을 넘지 못한다).
 *
 * <p>이 눈은 <b>실전과 같은 함수를 부른다</b> — {@code AttackRhythm.digShadow / basicCooldownUntil}
 * 을 그대로 시험한다 (사본 산수를 만들면 시험이 실전을 대변하지 못한다). 이벤트 폭풍은
 * 실측 시퀀스(깨짐 틱 + 스윙 합성 틱)를 그대로 재연한다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다 — AttackRhythm 은 Bukkit 을 모른다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:compileJava -q
 *   CP=server-mvt/build/classes/java/main
 *   $JAVA_HOME/bin/javac -d /tmp/attack-rhythm-eye -cp "$CP" tools/AttackRhythmSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/attack-rhythm-eye" com.honcheon.mvt.AttackRhythmSelfTest
 * </pre>
 */
public final class AttackRhythmSelfTest {

    private static int eyes;
    private static int failed;

    private AttackRhythmSelfTest() {
    }

    public static void main(String[] args) {
        // ══════════ ① 채굴의 그림자 — 깬 직후의 허공 스윙은 공격이 아니다 ══════════
        eye("같은 틱의 스윙 합성(깨짐→스윙 패킷 순서) — 그림자다",
                AttackRhythm.digShadow(100, 100));
        eye("틱 경계에 걸친 스윙(+1틱) — 그림자다", AttackRhythm.digShadow(101, 100));
        eye("그림자의 끝(+2틱) — 아직 그림자다", AttackRhythm.digShadow(102, 100));
        eye("사람의 손(+3틱, 놓았다 다시 클릭) — 공격이다", !AttackRhythm.digShadow(103, 100));
        eye("한 번도 안 캔 몸(기본값 MIN_VALUE/2) — 공격이다 (오버플로로 그림자가 되면 안 된다)",
                !AttackRhythm.digShadow(0, Long.MIN_VALUE / 2));
        eye("틱 0 에 깨고 틱 0 에 스윙 — 기동 직후에도 그림자가 선다",
                AttackRhythm.digShadow(0, 0));

        // ══════════ ② 실측 폭풍의 재연 — 월아산으로 땅을 향해 홀드 (흙 = 2~4틱 + 지연 5틱) ══════════
        //   깨짐 틱: 3, 12, 21, 30 … (캐기 3틱 + destroyDelay 5 + 재조준 1). 각 깨짐의 같은 틱에
        //   빗나간 스윙이 LEFT_CLICK_AIR 로 합성된다. 옛 문(그림자 없음)은 전부 공격으로 읽었다.
        long[] breaks = {3, 12, 21, 30, 39, 48};
        int attacksOld = 0;
        int attacksNew = 0;
        long neverDug = Long.MIN_VALUE / 2;
        long lastDig = neverDug;
        for (long breakTick : breaks) {
            lastDig = breakTick;              // BlockBreakEvent (MONITOR) 가 먼저 적는다
            attacksOld++;                     // 옛 문 — 합성 이벤트를 무조건 공격으로 읽었다
            if (!AttackRhythm.digShadow(breakTick, lastDig)) {
                attacksNew++;
            }
        }
        eye("옛 문 — 홀드 2.4초에 공격 " + attacksOld + "번 (자동 연발의 재연)", attacksOld == 6);
        eye("새 문 — 캐는 손의 합성은 전부 걸러진다 (공격 0번)", attacksNew == 0);
        eye("캐기를 멈추고 진짜로 휘두른다 (마지막 깨짐 +20틱) — 그 한 번은 나간다",
                !AttackRhythm.digShadow(breaks[breaks.length - 1] + 20, lastDig));

        // ══════════ ③ 병기의 박자 — 기본 초식의 획은 계열 공속을 넘지 못한다 ══════════
        //   월아산 공속 1.0/s → 스윙 간격 20틱 (Weapons.Series 월아산 speedMod -3.0 · 20/1.0).
        //   등록부 연출 간격은 4틱 (config/skill_motion.yml basic_strike.cooldown_ticks).
        eye("월아산(간격 20틱) — 박자가 등록부 간격(4틱)을 이긴다: 다음 획은 +20틱",
                AttackRhythm.basicCooldownUntil(1000, 4, 20) == 1020);
        eye("검(공속 1.6/s → 간격 13틱) — 다음 획은 +13틱",
                AttackRhythm.basicCooldownUntil(1000, 4, Math.round(20.0 / 1.6)) == 1013);
        eye("혼천 병기가 아닌 손(간격 0) — 옛 동작 그대로 +4틱 (등록부 간격)",
                AttackRhythm.basicCooldownUntil(1000, 4, 0) == 1004);
        eye("홀드 1초의 연발 상한 — 월아산(간격 20틱)은 한 획을 넘지 못한다",
                strokesInWindow(20, 20) == 1);
        eye("옛 못(계열 공속 없이 등록부 4틱만)은 1초에 다섯 획을 그렸다 — 병의 재연",
                strokesInWindow(20, 0) == 5);

        System.out.println();
        if (failed > 0) {
            System.out.println("✖ 눈 " + eyes + "개 중 " + failed + "개가 어긋났다");
            System.exit(1);
        }
        System.out.println("✔ 눈 " + eyes + "개 전부 통과 — 좌클릭 한 번 = 공격 한 번, "
                + "홀드는 병기의 박자를 넘지 못한다");
    }

    /**
     * windowTicks 동안 매 틱 공격 입력이 와도(폭풍) 실제로 서는 획의 수 — 실전과 같은 못을 쓴다.
     * {@code swingInterval 0} = 혼천 병기가 아닌 손 = 옛 동작(등록부 4틱만)의 재연이기도 하다.
     */
    private static int strokesInWindow(int windowTicks, long swingInterval) {
        int strokes = 0;
        long readyAt = Long.MIN_VALUE / 2;
        for (long t = 0; t < windowTicks; t++) {
            if (t < readyAt) {
                continue;                     // state.onCooldown(CD_BASIC) 과 같은 문
            }
            strokes++;
            readyAt = AttackRhythm.basicCooldownUntil(t, 4, swingInterval);
        }
        return strokes;
    }

    private static void eye(String what, boolean ok) {
        eyes++;
        if (!ok) {
            failed++;
        }
        System.out.println((ok ? "  ✔ " : "  ✖ ") + what);
    }
}
