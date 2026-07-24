package com.honcheon.mvt;

import com.honcheon.core.rules.ProgressionEngine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 플레이어 화후 원장 (MVT) — <b>몸에 실린 시트.</b>
 * "판정은 정수, 자원은 실수" — 기술은 수련 일치(실수)로 쌓이고 레벨은 환산표로 파생된다.
 *
 * <p><b>2026-07 성장 축 패스</b> — 이 원장은 <b>숙련만</b> 갖고 있었다. 능력치는 아예 없었고,
 * 그래서 {@code Vitality} 는 체력을 경지에서 유도했으며({@code defaultChe} = 캡 −1)
 * {@code SkillListener} 는 공격 판정에서 능력치 항을 통째로 뺐다
 * (<i>"MVT 근사: 능력치 시트가 없다"</i>). <b>같은 경지의 두 사람이 완전히 같은 사람이었다.</b>
 *
 * <p><b>2026-07 시트 접합 패스 — 정본은 봇이다.</b> 위 패스가 원장에 시트의 <i>모양</i>을 냈지만
 * 그것을 <b>채우는 손이 없었다</b> ({@code setAttr}·{@code setNaegong}·{@code setSimbeop}·
 * {@code setPrimaryArt} 는 호출자가 0이었다). 갓 접속한 몸은 능력치가 전부 0.0 이었고,
 * 같은 경지 NPC 는 {@code realmAttr} 로 표준치를 받았다 — <b>플레이어가 제 급의 산적보다 약했다.</b>
 *
 * <p>이제 이 원장은 두 종류의 값을 갖는다. 이 구분이 이 클래스의 전부다:
 * <ul>
 *   <li><b>거울 (봇이 정본)</b> — {@code realm}·{@code attrDays}·{@code naegong}·{@code simbeop}·
 *       {@code primaryArt}·{@code skillDays}·마크·소지금. {@link #applySheet} 가 스냅숏으로 <b>덮어쓴다</b>.
 *       내려오는 것은 <b>절대값</b>이지 증분이 아니다 — 그래서 마크가 낙관적으로 먼저 더해 놔도
 *       <b>영구히 갈라질 수 없다</b> (다음 스냅숏이 진실로 되돌린다).</li>
 *   <li><b>몸의 것 (마크가 정본)</b> — {@code curriculum}(수련 배분) · 일일 카운터 · 반복 감쇠.
 *       봇은 이것을 모른다. 이것만이 {@code ledgers.yml} 에서 <b>진짜로</b> 되살아나야 하는 값이다.</li>
 * </ul>
 *
 * <p>그리고 <b>미결(未決)</b>: {@link Pending} — 아직 다리를 건너지 못한 증분. 마크에서 쌓인 것은
 * 여기 모였다가 {@code cultivation_logged} 로 봇에 올라가고, 봇이 시트에 적은 뒤 스냅숏으로 되내려온다.
 */
public final class PlayerLedger {

    private final Map<String, Double> skillDays = new HashMap<>();
    private final Map<String, Integer> repetitionByType = new HashMap<>();
    private double grantedToday;
    private long lastGameDay = -1;
    private int money;
    private int marks실전;
    private int marks사선;

    /**
     * 경지 — <b>봇의 확정값</b>. 접합 전에는 등록부의 첫 단(범인)이다.
     * 여기가 하드코딩 {@code "이류"} 가 서 있던 자리다 ({@code SkillEngine.State.realm}).
     */
    private String realm;
    /** 접합된 몸인가 — 스냅숏의 links 에 이 몸이 있는가 (없으면 강호에 없는 사람이다) */
    private boolean linked;
    /** 시트를 한 번이라도 받았는가 — 봇이 꺼져 있으면 거울은 파일에서 되살린 마지막 상(像)이다 */
    private boolean sheetSeen;

    // ─── 성장 축 (2026-07) — 능력치 화후 · 수련 배분 · 단전 ───

    /** 능력치 화후 (실수 적립) — 판정은 정수부, 파생치(내구·내력)는 실수치 */
    private final Map<String, Double> attrDays = new LinkedHashMap<>();
    /** 수련 배분 — 과목 → 구간 수 (합 ≤ 5). <b>이것이 플레이어의 선택이다</b> */
    private final Map<String, Integer> curriculum = new LinkedHashMap<>();
    /** 내공 실수치 — 내력 풀 = round(내공 × 3) */
    private double naegong;
    /** 익힌 심법 (simbeop.yml id) — null = 개화 전 (내공 과목 봉쇄 · 결 없음) */
    private String simbeop;
    /** 주력 무공 — 초식 과목의 구간이 쌓이는 곳 (승급 요건의 '주력 무공 숙련') */
    private String primaryArt = "육합검";
    /** 오늘 캡에 막혀 버려진 일치 — HUD 가 "몸이 더는 받지 않는다"를 띄울 근거 */
    private double wastedToday;

    /**
     * 날을 넘긴다 — 일일 상한·반복 감쇠의 시계.
     *
     * <p><b>★ 시계는 봇의 세계일이다</b> ({@link WorldBridge.State#day()} — 현실 하루 = 세계 1일,
     * 자정 스케줄러가 굴린다). 예전에는 {@code getFullTime()/24000} 이었다. 그것이 시계의 분열이었다:
     * <b>마크의 하루는 현실 20분</b>이고 봇의 하루는 현실 24시간인데, {@code Growth.attrCostDays}
     * (능력치 +1 = 360일)는 <b>같은 {@code days} 단위</b>를 쓴다. 같은 숫자가 <b>72배 다른 두 시계</b>
     * 위에서 돌았다 — 마크에 5일 접속해 앉아 있으면 봇에서 1년 걸릴 능력치가 올랐다.
     *
     * <p>이제 달력은 하나다: <b>봇이 굴리고 마크가 읽는다.</b> 마크의 20분 낮밤은 조명과 밤(隱)의
     * 배경으로만 남는다 — 그것으로 화후를 캐낼 수는 없다.
     *
     * <p>{@code worldDay <= 0} 이면(스냅숏이 아직 없다 = 봇이 꺼져 있다) 호출자가 아예 부르지 않는다.
     * 장부가 봇의 것이므로 <b>봇이 죽어 있는 동안 장부는 움직이지 않는다.</b> 그것이 옳은 실패다.
     */
    public void rollDay(long worldDay) {
        if (worldDay != lastGameDay) {
            lastGameDay = worldDay;
            grantedToday = 0;
            wastedToday = 0;
            repetitionByType.clear();
        }
    }

    /** 날이 바뀌었는가 — 하루치 수련 정산(Growth.train)의 계기. 인자는 <b>봇의 세계일</b> */
    public boolean isNewDay(long worldDay) {
        return worldDay != lastGameDay;
    }

    public long lastGameDay() {
        return lastGameDay;
    }

    // ─── 능력치 화후 ───

    /** 능력치 실수치 — 판정은 정수부만 본다 (3.9와 4.0은 세계가 다르다) */
    public double attr(String name) {
        return attrDays.getOrDefault(name, 0.0);
    }

    /** 능력치 화후 적립 (단위: 능력치 점수 — 일치가 아니라 이미 환산된 값) */
    public void grantAttr(String name, double points) {
        attrDays.merge(name, points, Double::sum);
    }

    /** 생성 시 능력치 세팅 (player_creation 프리셋 전개 · 연무장 시험) */
    public void setAttr(String name, double value) {
        attrDays.put(name, value);
    }

    public Map<String, Double> allAttrs() {
        return attrDays;
    }

    // ─── 단전 ───

    public double naegong() {
        return naegong;
    }

    public void grantNaegong(double points) {
        naegong += points;
    }

    public void setNaegong(double value) {
        naegong = value;
    }

    /** 익힌 심법 — null 이면 개화 전 (축기 불가 · 결 없음) */
    public String simbeop() {
        return simbeop;
    }

    /** 개화 — 심법을 얻는다 (취걸개 기연 · 문파 전수) */
    public void setSimbeop(String id) {
        simbeop = id;
    }

    // ─── 수련 배분 — 플레이어의 선택 ───

    /** 과목 → 구간 수. 비어 있으면 아무것도 수련하지 않는다 (사냥·의뢰에 하루를 다 쓴 것) */
    public Map<String, Integer> curriculum() {
        return curriculum;
    }

    /** 배분을 세운다 — 구간 합이 상한을 넘으면 호출자가 막는다 (Growth.segmentsPerDay) */
    public void setSegments(String subject, int segments) {
        if (segments <= 0) {
            curriculum.remove(subject);
        } else {
            curriculum.put(subject, segments);
        }
    }

    public void clearCurriculum() {
        curriculum.clear();
    }

    /** 구간이 가장 많은 과목 — 사선 마크의 '관련 능력치'가 여기로 간다 (cultivation.yml) */
    public String primarySubject() {
        return curriculum.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 주력 무공 — 초식 과목·실전 적립이 쌓이는 원장 */
    public String primaryArt() {
        return primaryArt;
    }

    public void setPrimaryArt(String art) {
        primaryArt = art;
    }

    /** 오늘 천장에 막혀 버려진 일치 — "몸이 더는 받지 않는다" */
    public double wastedToday() {
        return wastedToday;
    }

    public void addWasted(double days) {
        wastedToday += days;
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

    /** 다음 숙련까지의 진행도 0.0~1.0 — 화후 게이지의 입력 (환산표 상한 도달 시 1.0) */
    public double progressToNext(String skill, ProgressionEngine progression) {
        double remaining = skillDays.getOrDefault(skill, 0.0);
        int level = 0;
        int cost;
        while ((cost = progression.skillLevelUpDays(level)) > 0 && remaining >= cost) {
            remaining -= cost;
            level++;
        }
        return cost <= 0 ? 1.0 : remaining / cost;
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

    // ═══════════════ 시트 — 봇이 정본이다 ═══════════════
    //
    // 왜 봇인가: **경지·화후·승급은 강호가 인정하는 것**이다. 봇의 SQLite 에는 이미 완성된 시트가
    // 있고(경지·능력치·심법·내공·소지금·문파·혈채) `promoteIfDue` 가 범인→삼류→이류→일류를 굴린다.
    // 마크에 두 번째 시트를 세우면 이 프로젝트가 반복해서 데인 병이 된다 — **정본이 둘이고 코드가 이긴다.**
    //
    // 마크는 **몸**이다: 수련 배분을 고르고, 실전으로 손을 익히고, 어디에 서 있는가를 안다.
    // 몸에서 쌓인 것은 다리를 타고 올라가 시트를 바꾸고, 바뀐 시트가 스냅숏으로 되내려온다.

    /** 지금 이 몸의 경지 — 봇의 확정값 (접합 전에는 등록부의 첫 단) */
    public String realm(String fallback) {
        return realm == null || realm.isBlank() ? fallback : realm;
    }

    public boolean linked() {
        return linked;
    }

    /** 시트가 한 번이라도 내려왔는가 — 아니면 이 몸은 아직 강호에 없다 (또는 봇이 꺼져 있다) */
    public boolean sheetSeen() {
        return sheetSeen;
    }

    /** 접합이 끊겼다 — 거울은 그대로 두되 '강호에 없음'을 기록한다 (몸은 남고 이름이 사라진다) */
    public void setLinked(boolean value) {
        linked = value;
    }

    /**
     * <b>봇의 시트를 몸에 싣는다.</b> 이것이 {@code setAttr}·{@code setNaegong}·{@code setSimbeop}·
     * {@code setPrimaryArt} 가 여태 기다리던 손이다 (호출자 0이었다).
     *
     * <p><b>덮어쓴다 — 더하지 않는다.</b> 내려오는 값은 절대값이므로 몇 번을 적용해도 같다(멱등).
     * 마크가 낙관적으로 먼저 더해 둔 증분이 있어도 여기서 진실로 되돌아온다. <b>갈라질 수 없는 구조</b>다.
     *
     * @param sheet 봇의 확정 시트 (null 필드는 건드리지 않는다 — 봇이 모르는 값은 몸의 것이다)
     */
    public void applySheet(WorldBridge.Sheet sheet) {
        if (sheet == null) {
            return;
        }
        linked = true;
        sheetSeen = true;
        if (sheet.realm() != null && !sheet.realm().isBlank()) {
            realm = sheet.realm();
        }
        if (sheet.attrs() != null && !sheet.attrs().isEmpty()) {
            attrDays.clear();
            sheet.attrs().forEach(this::setAttr);
        }
        if (sheet.naegong() >= 0) {
            setNaegong(sheet.naegong());
        }
        setSimbeop(sheet.simbeop());   // null 도 진실이다 — 개화 전이면 개화 전인 것이다
        // 주무공도 null 이 진실이다 — **익히지 않은 무공은 수련할 수 없다** (무공 백지 = 기연 자격).
        // 여기 "육합검"이 기본값으로 박혀 있었다. 아무도 배운 적 없는 무공이 모두의 손에 들려 있었다.
        setPrimaryArt(sheet.primaryArt() == null || sheet.primaryArt().isBlank()
                ? null : sheet.primaryArt());
        if (sheet.skillDays() != null && !sheet.skillDays().isEmpty()) {
            skillDays.clear();
            skillDays.putAll(sheet.skillDays());
        }
        if (sheet.marks실전() >= 0) {
            marks실전 = sheet.marks실전();
        }
        if (sheet.marks사선() >= 0) {
            marks사선 = sheet.marks사선();
        }
        if (sheet.money() >= 0) {
            money = sheet.money();
        }
        // ★성장 v3 레벨 거울 (단계 5) — 봇이 정본, 여기는 표시용 사본이다 (연출·XP바·명패가 읽는다).
        //   영속하지 않는다: 재기동 뒤 첫 스냅숏이 다시 채우고, 그 첫 적용은 델타가 아니라 도착이다.
        if (sheet.level() >= 1) {
            level = sheet.level();
        }
        if (sheet.xp() >= 0) {
            xp = sheet.xp();
        }
        if (sheet.xpNeed() > 0) {
            xpNeed = sheet.xpNeed();
        }
        if (sheet.points() >= 0) {
            points = sheet.points();
        }
    }

    // ─── 성장 v3 레벨 거울 (단계 5) — 봇의 시트가 채운다. -1 = 아직 모른다 ───

    private int level = -1;
    private double xp = -1;
    private double xpNeed = -1;
    private int points = -1;

    /** 성장 v3 레벨 (봇 거울) — -1 = 스냅숏 전이거나 levels off */
    public int level() {
        return level;
    }

    /** 현재 레벨의 경험치 잔여 (봇 거울) */
    public double xp() {
        return xp;
    }

    /** 다음 레벨 필요치 need(L) (봇 거울) */
    public double xpNeed() {
        return xpNeed;
    }

    /** 미사용 스탯 포인트 (봇 거울) — 배분의 손은 디스코드 시트다 */
    public int points() {
        return points;
    }

    // ─── 미결(未決) — 아직 다리를 못 건넌 증분 ───
    //
    // 마크에서 쌓인 것은 여기 모였다가 `cultivation_logged` 로 봇에 올라간다. 올려 보내고 비운다.
    // 다리는 append-only 디스크 큐이므로 유실되지 않고, 봇의 bridge_inbox(PK=event_id)가 중복을 막는다 —
    // **한 번 실은 것은 정확히 한 번 적용된다.** 그래서 보낸 뒤 비우는 것으로 충분하다.

    private final Map<String, Double> pendingAttr = new LinkedHashMap<>();
    private final Map<String, Double> pendingSkill = new LinkedHashMap<>();
    private double pendingNaegong;
    private double pendingTrain;
    private int pending실전;
    private int pending사선;
    private int pendingXp;   // 성장 v3 XP (레벨 축) — 시트의 경험치·레벨은 봇이 적는다

    /**
     * 그날 실제로 몸에 들어간 수련 일치 — 봇의 {@code 화후_원장} 으로 간다.
     * 이것이 <b>범인 → 삼류</b>의 관문이다 ("기초 단련 3개월" = 90일치). 과목이 무엇이든 몸은 단련된다.
     */
    public void pendTrain(double days) {
        if (days > 0) {
            pendingTrain += days;
        }
    }

    public void pendAttr(String attr, double delta) {
        if (delta > 0) {
            pendingAttr.merge(attr, delta, Double::sum);
        }
    }

    public void pendSkill(String art, double days) {
        if (days > 0) {
            pendingSkill.merge(art, days, Double::sum);
        }
    }

    public void pendNaegong(double delta) {
        if (delta > 0) {
            pendingNaegong += delta;
        }
    }

    public void pend실전() {
        pending실전++;
    }

    public void pend사선() {
        pending사선++;
    }

    /** 성장 v3 XP (B-135 단계 4) — 처치·의뢰가 낸 경험. 레벨업·포인트 계산은 봇의 몫이다 */
    public void pendXp(int xp) {
        if (xp > 0) {
            pendingXp += xp;
        }
    }

    public boolean hasPending() {
        return !pendingAttr.isEmpty() || !pendingSkill.isEmpty() || pendingNaegong > 0
                || pendingTrain > 0 || pending실전 > 0 || pending사선 > 0 || pendingXp > 0;
    }

    /** 미결을 걷어 간다 — 다리에 실은 뒤 비운다 (실렸으면 봇이 정확히 한 번 적용한다) */
    public Pending takePending() {
        Pending p = new Pending(new LinkedHashMap<>(pendingAttr), new LinkedHashMap<>(pendingSkill),
                pendingNaegong, pendingTrain, pending실전, pending사선, pendingXp);
        pendingAttr.clear();
        pendingSkill.clear();
        pendingNaegong = 0;
        pendingTrain = 0;
        pending실전 = 0;
        pending사선 = 0;
        pendingXp = 0;
        return p;
    }

    /** 몸에서 쌓여 장부로 갈 증분 — {@code cultivation_logged} 의 페이로드 */
    public record Pending(Map<String, Double> attrs, Map<String, Double> skills, double naegong,
                          double trainDays, int marks실전, int marks사선, int xp) {
    }

    // ═══════════════ 영속 — 재기동을 넘어 살아남는다 ═══════════════
    //
    // 이 원장에는 **저장·적재 코드가 없었다** (`HoncheonMvt.ledgers` = 순수 HashMap).
    // 재기동 = 능력치·내공·숙련·소지금·수련 배분 전부 소멸. 문법은 저장소의 것을 따른다 —
    // `anchors.yml`·`zones.yml`·`regions.yml` 과 같은 YamlConfiguration (플러그인 데이터 폴더).
    //
    // ★ 거울(봇의 시트)도 함께 적는다. 봇이 꺼진 채 마크가 뜨면 **마지막으로 본 상(像)** 으로 서 있다가
    //   봇이 켜지는 순간 진실로 갈아 끼워진다. 빈 몸으로 서는 것보다 낫고, 갈라질 위험은 0이다
    //   (내려오는 값은 절대값이므로 거울은 언제나 진실에 진다).

    public void save(org.bukkit.configuration.ConfigurationSection to) {
        to.set("realm", realm);
        to.set("linked", linked);
        to.set("naegong", naegong);
        to.set("simbeop", simbeop);
        to.set("primary_art", primaryArt);
        to.set("money", money);
        to.set("marks_실전", marks실전);
        to.set("marks_사선", marks사선);
        to.set("last_world_day", lastGameDay);
        to.set("granted_today", grantedToday);
        to.set("wasted_today", wastedToday);
        attrDays.forEach((k, v) -> to.set("attr." + k, v));
        skillDays.forEach((k, v) -> to.set("skill." + k, v));
        curriculum.forEach((k, v) -> to.set("curriculum." + k, v));
        repetitionByType.forEach((k, v) -> to.set("repetition." + k, v));
        pendingAttr.forEach((k, v) -> to.set("pending.attr." + k, v));
        pendingSkill.forEach((k, v) -> to.set("pending.skill." + k, v));
        to.set("pending.naegong", pendingNaegong);
        to.set("pending.train", pendingTrain);
        to.set("pending.marks_실전", pending실전);
        to.set("pending.marks_사선", pending사선);
        to.set("pending.xp", pendingXp);   // ★미영속이면 재기동이 XP 를 지운다 (2026-07-24 실측)
    }

    public static PlayerLedger load(org.bukkit.configuration.ConfigurationSection from) {
        PlayerLedger led = new PlayerLedger();
        led.realm = from.getString("realm");
        led.linked = from.getBoolean("linked");
        led.sheetSeen = led.realm != null;
        led.naegong = from.getDouble("naegong");
        led.simbeop = from.getString("simbeop");
        led.primaryArt = from.getString("primary_art", led.primaryArt);
        led.money = from.getInt("money");
        led.marks실전 = from.getInt("marks_실전");
        led.marks사선 = from.getInt("marks_사선");
        led.lastGameDay = from.getLong("last_world_day", -1);
        led.grantedToday = from.getDouble("granted_today");
        led.wastedToday = from.getDouble("wasted_today");
        copyDouble(from, "attr", led.attrDays);
        copyDouble(from, "skill", led.skillDays);
        copyInt(from, "curriculum", led.curriculum);
        copyInt(from, "repetition", led.repetitionByType);
        copyDouble(from, "pending.attr", led.pendingAttr);
        copyDouble(from, "pending.skill", led.pendingSkill);
        led.pendingNaegong = from.getDouble("pending.naegong");
        led.pendingTrain = from.getDouble("pending.train");
        led.pending실전 = from.getInt("pending.marks_실전");
        led.pending사선 = from.getInt("pending.marks_사선");
        led.pendingXp = from.getInt("pending.xp");
        return led;
    }

    private static void copyDouble(org.bukkit.configuration.ConfigurationSection from, String path,
                                   Map<String, Double> into) {
        org.bukkit.configuration.ConfigurationSection sec = from.getConfigurationSection(path);
        if (sec != null) {
            sec.getKeys(false).forEach(k -> into.put(k, sec.getDouble(k)));
        }
    }

    private static void copyInt(org.bukkit.configuration.ConfigurationSection from, String path,
                                Map<String, Integer> into) {
        org.bukkit.configuration.ConfigurationSection sec = from.getConfigurationSection(path);
        if (sec != null) {
            sec.getKeys(false).forEach(k -> into.put(k, sec.getInt(k)));
        }
    }
}
