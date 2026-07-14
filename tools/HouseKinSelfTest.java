package com.honcheon.bot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 눈 — <b>형제는 「같은 집」끼리만 잡히는가.</b>
 *
 * <p><b>【이 눈이 왜 생겼나 — 담당자가 만든 병이다】</b>
 * 2026-07-13 에 형제 축을 짜면서 <b>같은 집안 「유형」의 산 자를 전부 남매로 묶었다.</b>
 * 그래서 <b>농가의 아이 둘이 남매가 됐다</b> — 서로 다른 농가인데.
 * 사용자의 말은 <i>"<b>같은 세가에</b> 같이 태어나게 되었다면"</i> — <b>같은 집</b>이었다.
 *
 * <p>이제 남매는 {@code house_id} 로만 잡는다 (유형이 아니라 <b>한 채의 집</b>).
 * 이 눈은 그것을 <b>실제 DB 로</b> 잰다 — config 검사로는 증명할 수 없는 것이다.
 *
 * <p><b>세 가지를 시험한다:</b>
 * <ol>
 *   <li><b>같은 집</b>의 두 아이 → <b>남매다</b></li>
 *   <li><b>다른 집</b>의 두 아이 → <b>남매가 아니다</b> ★ (옛 코드가 틀렸던 자리)</li>
 *   <li><b>일부러 어긴다</b>: 다른 집 아이를 같은 house_id 에 묶어 놓고 — 눈이 그것을 <b>본다</b></li>
 * </ol>
 */
public final class HouseKinSelfTest {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("honcheon-house");
        Path db = dir.resolve("t.db");
        Path schema = Path.of("db/schema.sql");

        // 봇과 **같은 경로**로 연다 (스키마 + 마이그레이션 자동 적용)
        try (Db d = new Db(db, schema)) {
            // 008 을 손으로 얹는다 — 이 시험은 **마이그레이션이 돌아간 뒤의 세계**를 잰다
            apply(db, Path.of("db/migrations/008_가문.sql"));

            long 이가 = house(db, "무가의_자식", "청하 이가(李家)");
            long 최가 = house(db, "무가의_자식", "청하 최가(崔家)");   // ★ **다른 집**. 같은 유형이다

            long 형 = born(d, db, "이대형", 이가, "남");
            long 누나 = born(d, db, "이소저", 이가, "여");
            long 나 = born(d, db, "이막내", 이가, "남");
            long 남 = born(d, db, "최도령", 최가, "남");   // 같은 **유형**, 다른 **집**

            boolean ok = true;

            // ─── ① 같은 집 → 남매다 (태어난 순서대로) ───
            List<Map<String, Object>> mine = d.houseMembers(이가);
            System.out.println("① 이가(李家)의 아이들 (태어난 순):");
            for (Map<String, Object> m : mine) {
                System.out.println("     " + m.get("id") + " " + m.get("name") + " (" + m.get("성별") + ")");
            }
            if (mine.size() != 3) {
                System.out.println("   ☠ 이가의 아이가 3이 아니다: " + mine.size());
                ok = false;
            }

            // ─── ② ★ 다른 집 → 남매가 **아니다** (옛 코드가 틀렸던 자리) ───
            boolean leaked = mine.stream().anyMatch(m -> ((Number) m.get("id")).longValue() == 남);
            System.out.println("\n② 최가(崔家)의 아이가 이가의 남매로 새어 들어왔는가: "
                    + (leaked ? "☠ 그렇다 — **옛 병이 살아 있다**" : "✓ 아니다"));
            if (leaked) {
                ok = false;
            }
            System.out.println("   (옛 코드는 **집안 유형**으로 묶었다 — 둘 다 '무가의_자식' 이므로"
                    + " **남매가 됐을 것이다.** 그것이 병이었다)");

            // ─── ③ ★★ 일부러 어긴다 — 최가의 아이를 이가의 호적에 끼워 넣는다 ───
            System.out.println("\n③ ★ 일부러 어긴다 — 최가의 아이를 이가의 house_id 에 끼워 넣는다");
            exec(db, "UPDATE characters SET house_id = " + 이가 + " WHERE id = " + 남);
            List<Map<String, Object>> tainted = d.houseMembers(이가);
            boolean caught = tainted.stream().anyMatch(m -> ((Number) m.get("id")).longValue() == 남);
            System.out.println("   눈이 그것을 보는가: "
                    + (caught ? "✓ 본다 (이가의 아이가 4명이 됐다 — 호적을 고치면 형제가 바뀐다)"
                    : "☠ 못 본다"));
            if (!caught) {
                ok = false;
            }
            System.out.println("   ★ 즉 **형제는 house_id 하나에만 달려 있다** —"
                    + " 유형도, 이름도, 성씨도 아니다. 그것이 이 설계의 전부다.");
            exec(db, "UPDATE characters SET house_id = " + 최가 + " WHERE id = " + 남);   // 되돌린다

            // ─── ④ 집이 없는 아이 (마이그레이션 전에 태어났다) ───
            long 무적 = born(d, db, "떠돌이", null, "남");
            System.out.println("\n④ 집 없는 아이의 house_id: " + d.houseOfCharacter(무적)
                    + "  ← null 이어야 한다 (그때 형제는 **비어 있다**)");
            if (d.houseOfCharacter(무적) != null) {
                ok = false;
            }

            // ─── ⑤ ★ 가문의 이름·지역·형태 — 등록부대로 나오는가 ───
            System.out.println("\n⑤ 가문이 등록부대로 서는가 (Rules)");
            Rules rules = new Rules(Path.of("config"));
            java.util.Random dice = new java.util.Random(7);
            System.out.println("   설 수 있는 고을: " + rules.playableRegions());
            for (String f : List.of("무가의_자식", "몰락_무가의_자식", "농가의_자식", "객잔집_자식")) {
                boolean martial = rules.isMartialHouse(f);
                String state = rules.houseState(f, dice);
                String anchor = rules.startAnchor("cheongha_hyeon", f);
                System.out.printf("   %-18s 무가계열=%-5s 형태=%-4s 첫자리=%s%n",
                        f, martial, String.valueOf(state), anchor);
                // ★ 성은 무가 계열에만 (이 세계의 NPC 32명은 전원 성이 없다)
                if (!martial && state != null) {
                    System.out.println("     ☠ 무가가 아닌데 형태가 있다");
                    ok = false;
                }
                if (anchor == null) {
                    System.out.println("     ☠ 설 자리가 없다 — 사람이 허공에 떨어진다");
                    ok = false;
                }
            }
            // ★ 몰락무가는 **굴리지 않고 받는다** (집안이 이미 '멸' 이라고 말했다)
            for (int i = 0; i < 20; i++) {
                if (!"멸".equals(rules.houseState("몰락_무가의_자식", dice))) {
                    System.out.println("   ☠ 몰락무가가 '멸' 이 아니다 — 굴리고 있다");
                    ok = false;
                    break;
                }
            }
            System.out.println("   ✓ 몰락무가는 언제나 「멸」 (20회 굴려도 — 집안이 이미 말했다)");

            // ─── ⑥ ★★ 집을 나온 아이는 **제 형과 남남이 되지 않는가** ───
            //   사용자: "호적에서 지워도 **형은 형이다.**"
            //   그 아이의 집안은 `가출한_무가의_자식` 이지만, 그는 **무가의 집에서 태어났다.**
            System.out.println("\n⑥ ★ 집을 나온 아이 — 제 형과 남남이 되지 않는가");
            String bornIn = rules.birthFamilyOf("가출한_무가의_자식");
            System.out.println("   가출한_무가의_자식 이 **태어난** 집안: " + bornIn);
            if (!"무가의_자식".equals(bornIn)) {
                System.out.println("   ☠ 제 집안으로 새 집을 세운다 — **제 형과 남남이 된다**");
                ok = false;
            } else {
                // 실제로 이가(李家)에 앉혀 본다 — 형과 같은 house_id 가 되는가
                long 나온아이 = born(d, db, "이가출", 이가, "남");
                List<Map<String, Object>> kin = d.houseMembers(이가);
                boolean withBrother = kin.stream()
                        .anyMatch(m -> ((Number) m.get("id")).longValue() == 형);
                boolean meThere = kin.stream()
                        .anyMatch(m -> ((Number) m.get("id")).longValue() == 나온아이);
                System.out.println("   집을 나온 아이가 이가의 호적에 있는가: " + (meThere ? "✓" : "☠"));
                System.out.println("   그리고 제 형(이대형)이 거기 있는가: " + (withBrother ? "✓" : "☠"));
                System.out.println("   ★ 절연은 관계를 끊지 않는다 — **관계를 무겁게** 만들 뿐이다");
                if (!meThere || !withBrother) {
                    ok = false;
                }
            }

            System.out.println(ok
                    ? "\n눈이 조용하다 — 형제는 **같은 집**끼리만 잡힌다 (유형이 아니라 한 채의 집)."
                    : "\n눈이 운다.");
            System.exit(ok ? 0 : 1);
        }
    }

    private static long house(Path db, String family, String name) throws Exception {
        exec(db, "INSERT INTO houses(family, name, region, created_day) VALUES('"
                + family + "','" + name + "','청하현',1)");
        try (Connection c = open(db); Statement st = c.createStatement()) {
            var rs = st.executeQuery("SELECT id FROM houses WHERE name='" + name + "'");
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 아이 하나를 그 집에 앉힌다 (배정 규칙은 **아직 없다** — 이 시험은 손으로 앉힌다) */
    private static long born(Db d, Path db, String name, Long houseId, String gender) throws Exception {
        long id = d.createCharacter("test:" + name, name,
                new java.util.LinkedHashMap<>(Map.of("성별", gender, "집안", "무가의 자식",
                        "능력치", Map.of("근력", 1))), 0);
        if (houseId != null) {
            exec(db, "UPDATE characters SET house_id = " + houseId + " WHERE id = " + id);
        }
        return id;
    }

    /**
     * ★ 주석 줄을 <b>먼저 걷어낸다.</b> 안 그러면 머리말 주석이 첫 {@code CREATE TABLE} 에 들러붙어
     * "이 조각은 주석이다" 로 오인되고 — <b>표가 조용히 안 만들어진다.</b>
     * (담당자가 실제로 여기서 한 번 데였다: 삼킨 예외 + 잘못된 필터 = 침묵하는 실패)
     */
    private static void apply(Path db, Path sql) throws Exception {
        StringBuilder clean = new StringBuilder();
        for (String line : Files.readString(sql).split("\n")) {
            if (!line.strip().startsWith("--")) {
                clean.append(line).append('\n');
            }
        }
        try (Connection c = open(db); Statement st = c.createStatement()) {
            for (String stmt : clean.toString().split(";")) {
                if (stmt.isBlank()) {
                    continue;
                }
                try {
                    st.execute(stmt);
                } catch (Exception e) {
                    // 이미 있는 열/표는 무해하다 (마이그레이션은 두 번 돌아도 된다).
                    // ★ 그러나 **조용히 넘기지 않는다** — 침묵하는 실패가 이 시험을 거짓말로 만든다
                    String m = String.valueOf(e.getMessage());
                    if (!m.contains("duplicate column") && !m.contains("already exists")) {
                        throw e;
                    }
                }
            }
        }
    }

    private static void exec(Path db, String sql) throws Exception {
        try (Connection c = open(db); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static Connection open(Path db) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }
}
