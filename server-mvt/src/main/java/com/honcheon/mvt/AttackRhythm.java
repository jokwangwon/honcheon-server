package com.honcheon.mvt;

/**
 * 공격 입력의 리듬 — <b>좌클릭 한 번 = 공격 한 번, 홀드는 병기의 박자를 넘지 못한다.</b>
 *
 * <p><b>【월아산 자동 연발 · 2026-07-17】</b> 사용자 실측: "월아산으로 좌클릭 시 자동으로 계속
 * 공격이 나간다." 기전은 플러그인의 스케줄러가 아니라 <b>패킷 층</b>에 있었다:
 *
 * <ol>
 *   <li>바닐라 클라이언트는 좌클릭을 <b>누르고 있는 동안</b> 조준선의 블록을 캔다
 *       ({@code Minecraft.continueAttack}) — 그리고 캐는 동안 <b>매 틱 스윙 패킷</b>을 보낸다.
 *       (허공을 향한 홀드는 스윙을 반복하지 않는다 — 반복은 캘 블록이 있을 때만 온다.)</li>
 *   <li>Paper 는 <b>모든 스윙 패킷</b>에 대해 눈에서 레이트레이스를 쏘고, 아무것도 안 맞으면
 *       {@code LEFT_CLICK_AIR} 상호작용 이벤트를 <b>합성한다</b>
 *       (실측: paper-1.21.11 {@code ServerGamePacketListenerImpl.handleAnimate} 바이트코드 —
 *       패킷마다 {@code CraftEventFactory.callPlayerInteractEvent(LEFT_CLICK_AIR)}).</li>
 *   <li>블록이 <b>깨진 그 틱</b>의 스윙은 갓 뚫린 구멍을 지나 빗나간다 → ②의 합성이 온다.
 *       월아산은 <b>유일한 삽 병기</b>다 (Weapons.Base.SHOVEL) — 흙·잔디 블록(어디에나 있는 땅)을
 *       2~4틱에 깨고, 풀포기는 즉시 깬다. 땅·풀숲을 향해 좌클릭을 홀드하면 "깨짐 → 빗나간 스윙 →
 *       LEFT_CLICK_AIR" 가 초당 여러 번 돌고, {@code SkillListener.onInteract} 는 그것을 전부
 *       공격 입력(획·전진·무공 시전)으로 읽었다. <b>자동 연속 공격의 정체다.</b>
 *       (검은 흙을 15틱+에 캐서 폭풍이 안 보였을 뿐 — 같은 병이 부(도끼/원목)·구(곡괭이/돌)·
 *       겸(괭이/잎)에도 잠복해 있다.)</li>
 * </ol>
 *
 * <p><b>못 두 개</b> — 판정(피해)은 건드리지 않는다. 입력의 문만 세운다:
 * <ul>
 *   <li>{@link #digShadow} — <b>채굴의 그림자</b>: 블록을 깬 직후의 허공 스윙은 캐는 손이지
 *       공격의 손이 아니다. 같은 마우스 버튼이므로 "깬 직후 2틱 안에 진짜 공격 클릭"은 사람 손으로
 *       불가능하다 — 오탐이 원리적으로 없다. 몹 타격(EntityDamageByEntityEvent)은 이 문을
 *       지나지 않으므로 전투 회귀가 없다.</li>
 *   <li>{@link #basicCooldownUntil} — <b>병기의 박자</b>: 기본 초식의 연출·체술(획·전진·자세)은
 *       계열 공속(월아산 1.0/s = 20틱)을 넘지 못한다. 옛 못은 등록부의 연출 간격
 *       ({@code basic_strike.cooldown_ticks} 4틱)뿐이라 홀드·연타가 초당 5획을 그렸다 —
 *       무공 경로는 이미 같은 못이 있다 ({@code busyUntil = max(frames, swingInterval)},
 *       "공속이 거짓말하지 않게"). 기본 초식만 구멍이었다.</li>
 * </ul>
 *
 * <p>Bukkit 을 모른다 — {@code tools/AttackRhythmSelfTest.java} 가 서버 없이 이 눈을 시험한다.
 */
final class AttackRhythm {

    /**
     * 채굴의 그림자가 드리우는 폭(틱). 깨짐(액션 패킷)과 스윙 패킷은 같은 틱에 오는 것이 보통이고,
     * 틱 경계에 걸치면 1틱 늦는다 — 2틱이면 전부 덮고, 사람의 "놓았다 다시 클릭"(>2틱)은 안 덮는다.
     */
    static final long DIG_SHADOW_TICKS = 2;

    private AttackRhythm() {
    }

    /**
     * 이 허공 좌클릭이 <b>채굴의 그림자</b>인가 — 블록을 깬 직후({@value #DIG_SHADOW_TICKS}틱 안)의
     * 스윙 패킷 합성이면 참. 공격 입력으로 읽으면 안 된다.
     *
     * @param now           지금 틱
     * @param lastBreakTick 이 몸이 마지막으로 블록을 깬 틱 (없으면 {@code Long.MIN_VALUE / 2} —
     *                      /2 는 뺄셈 오버플로 방지. {@code strokeCycle} 과 같은 규약)
     */
    static boolean digShadow(long now, long lastBreakTick) {
        return now - lastBreakTick <= DIG_SHADOW_TICKS;
    }

    /**
     * 기본 초식(무공 없는 손)의 다음 획이 서는 틱 — <b>등록부 연출 간격과 계열 공속 중 긴 쪽</b>.
     * 혼천 병기가 아니면 {@code swingIntervalTicks} 가 0 이라 옛 동작(등록부 간격 4틱) 그대로다.
     *
     * @param basicCooldownTicks 등록부의 연출 최소 간격 ({@code basic_strike.cooldown_ticks})
     * @param swingIntervalTicks 계열 공속의 스윙 간격 ({@code 20 / attackSpeed} — 월아산 20틱)
     */
    static long basicCooldownUntil(long now, int basicCooldownTicks, long swingIntervalTicks) {
        return now + Math.max(basicCooldownTicks, swingIntervalTicks);
    }
}
