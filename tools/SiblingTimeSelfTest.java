package com.honcheon.bot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 눈 — <b>시간의 비대칭.</b> 사용자가 짚었다:
 * <i>"형이 먼저 태어남 (<b>동생이 있는지는 모름</b>), 동생이 태어남 (<b>형이 있는 줄 앎</b>)."</i>
 *
 * <p><b>【무엇이 위험한가】</b> 서장은 <b>미리 쓴다.</b> 형의 서장은 <b>동생이 태어나기 전에</b> 굳는다.
 * 나중에 동생이 나도 그 글은 영원히 "나는 혼자였다"고 말한다 — <b>그리고 그것이 옳다.</b>
 * 그때는 <b>정말로</b> 혼자였다. 소급해서 고치면 「탄생 시점에 고정」이라는 사용자 결정을 깨는 것이다.
 *
 * <p><b>【그러나 침묵하면 안 된다】</b> 형은 <b>어느 날 형이 되는 것</b>을 겪어야 한다 —
 * <b>아우가 나면 형에게 소식이 간다.</b>
 *
 * <p>세 가지를 <b>실제 DB</b>로 잰다 (config 검사로는 증명 못 한다):
 * <ol>
 *   <li>형의 <b>탄생 스냅숏</b>이 비어 있다 — 그리고 <b>동생이 나도 그대로다</b></li>
 *   <li>동생의 스냅숏에는 <b>형이 든다</b></li>
 *   <li>형의 <b>지금</b> 형제(kin)에는 동생이 <b>보인다</b> — 서장은 과거, 시트는 현재</li>
 * </ol>
 */
public final class SiblingTimeSelfTest {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("honcheon-sibtime");
        Path db = dir.resolve("t.db");

        try (Db d = new Db(db, Path.of("db/schema.sql"))) {
            apply(db, Path.of("db/migrations/008_가문.sql"));
            Rules rules = new Rules(Path.of("config"));
            boolean ok = true;

            long 이가 = house(db, "무가의_자식", "청하현 이가(家)");

            // ─── ① 형이 태어난다. 그때 그는 **정말로 혼자다** ───
            long 형 = born(d, db, "이대형", 이가, "남");
            List<String> 형의스냅숏 = snapshot(d, rules, 형);
            System.out.println("① 형이 태어난다");
            System.out.println("   형의 탄생 스냅숏: " + 형의스냅숏 + "   ← 비어야 한다 (그때는 혼자였다)");
            if (!형의스냅숏.isEmpty()) {
                System.out.println("   ☠ 없는 동생이 형의 서장에 들었다");
                ok = false;
            }

            // ─── ② 시간이 흐른다. 동생이 태어난다 ───
            long 동생 = born(d, db, "이막내", 이가, "남");
            List<String> 동생의스냅숏 = snapshot(d, rules, 동생);
            System.out.println("\n② 동생이 태어난다");
            System.out.println("   동생의 탄생 스냅숏: " + 동생의스냅숏 + "   ← 형이 들어야 한다");
            if (동생의스냅숏.isEmpty()) {
                System.out.println("   ☠ 형이 있는데 동생의 서장이 그것을 모른다");
                ok = false;
            }
            boolean 형이듦 = 동생의스냅숏.stream().anyMatch(k -> k.contains("이대형"));
            if (!형이듦) {
                System.out.println("   ☠ 동생의 스냅숏에 형(이대형)이 없다");
                ok = false;
            }

            // ★ 일부러 어긴다 — 동생이 난 뒤 형의 스냅숏을 **산 형제로 덮어쓴다** (소급 수정)
            if (System.getenv("BREAK_RETRO") != null) {
                var r0 = d.findCharacterById(형).orElseThrow();
                @SuppressWarnings("unchecked")
                Map<String, Object> sh0 = new java.util.LinkedHashMap<>(
                        (Map<String, Object>) r0.get("sheet"));
                java.util.List<String> live = new java.util.ArrayList<>();
                for (Map<String, Object> k : d.houseMembers(이가)) {
                    if (((Number) k.get("id")).longValue() != 형) {
                        live.add("아우 " + k.get("name"));
                    }
                }
                sh0.put(Seojang.SHEET_KIN, live);
                d.updateCharacter(형, sh0, 0, "범인", "서장", "서장");
            }

            // ─── ③ ★★ 형의 서장은 **그대로인가** (소급해서 고쳐지지 않았는가) ───
            List<String> 형의스냅숏_뒤 = frozen(d, 형);
            System.out.println("\n③ ★★ 동생이 난 뒤, **형의 서장 스냅숏**: " + 형의스냅숏_뒤);
            System.out.println("   ← 여전히 비어야 한다. **소급해서 고치지 않는다** —");
            System.out.println("     \"나는 혼자였다\" 는 거짓말이 아니다. 그때는 정말 혼자였다.");
            if (!형의스냅숏_뒤.isEmpty()) {
                System.out.println("   ☠ 형의 서장이 소급해서 고쳐졌다 — 「탄생 시점에 고정」이 깨졌다");
                ok = false;
            }

            // ─── ④ 그러나 **지금**의 형제에는 동생이 보인다 (서장은 과거, 시트는 현재) ───
            List<Map<String, Object>> 지금 = d.houseMembers(이가);
            boolean 동생보임 = 지금.stream()
                    .anyMatch(m -> ((Number) m.get("id")).longValue() == 동생);
            System.out.println("\n④ 그러나 **지금**의 집 식구: " + 지금.size() + "명 · 동생이 보이는가: "
                    + (동생보임 ? "✓" : "☠"));
            System.out.println("   ★ **서장은 과거고, 시트는 현재다.** 둘은 어긋난 것이 아니라 **다른 시간**이다.");
            if (!동생보임 || 지금.size() != 2) {
                ok = false;
            }

            // ─── ⑤ 아우가 났는데 형이 **모르면 위반** (소식이 가는가) ───
            System.out.println("\n⑤ 아우가 났다 — 형이 아는가 (등록부)");
            Map<String, Object> news = rules.siblingNews();
            boolean 알림 = Boolean.TRUE.equals(news.get("enabled"));
            System.out.println("   소식: " + news.get("channel") + " · 켜짐=" + 알림
                    + " · 호칭=" + rules.kinTitle(false, "남"));
            if (!알림) {
                System.out.println("   ☠ 아우가 나도 형이 모른다");
                ok = false;
            }
            if (!"아우".equals(rules.kinTitle(false, "남"))) {
                System.out.println("   ☠ 손아래 사내를 '아우' 라 부르지 않는다");
                ok = false;
            }

            System.out.println(ok
                    ? "\n눈이 조용하다 — 형은 **어느 날 형이 된다**. 그의 서장은 그때의 진실 그대로다."
                    : "\n눈이 운다.");
            System.exit(ok ? 0 : 1);
        }
    }

    /** 코드가 하는 그대로: 태어난 순간의 형제를 뽑는다 (GameListener.snapshotKinAtBirth 와 동형) */
    private static List<String> snapshot(Db d, Rules rules, long me) throws Exception {
        Long houseId = d.houseOfCharacter(me);
        if (houseId == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        boolean before = true;
        for (Map<String, Object> k : d.houseMembers(houseId)) {
            long id = ((Number) k.get("id")).longValue();
            if (id == me) {
                before = false;
                continue;
            }
            if (!before) {
                continue;   // ★ 나보다 **나중에** 난 자는 탄생 순간에 없었다
            }
            String g = k.get("성별") == null ? null : String.valueOf(k.get("성별"));
            out.add(rules.kinTitle(true, g) + " " + k.get("name"));
        }
        // ★ 시트에 박제 (탄생 순간에 한 번)
        return out;
    }

    /** 시트에 **이미 박제된** 스냅숏 (소급 수정이 없었는지 본다) */
    @SuppressWarnings("unchecked")
    private static List<String> frozen(Db d, long id) throws Exception {
        var row = d.findCharacterById(id);
        if (row.isEmpty()) {
            return List.of();
        }
        Object v = ((Map<String, Object>) row.get().get("sheet")).get(Seojang.SHEET_KIN);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static long house(Path db, String family, String name) throws Exception {
        exec(db, "INSERT INTO houses(family, name, region, state, created_day) VALUES('"
                + family + "','" + name + "','cheongha_hyeon','흥',1)");
        try (Connection c = open(db); Statement st = c.createStatement()) {
            var rs = st.executeQuery("SELECT id FROM houses WHERE name='" + name + "'");
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 아이 하나 — 그리고 **탄생 순간의 형제를 시트에 박제한다** (코드가 하는 그대로) */
    private static long born(Db d, Path db, String name, long houseId, String gender)
            throws Exception {
        Map<String, Object> sheet = new java.util.LinkedHashMap<>();
        sheet.put("성별", gender);
        sheet.put("집안", "무가의 자식");
        sheet.put("능력치", Map.of("근력", 1));
        long id = d.createCharacter("test:" + name, name, sheet, 0);
        exec(db, "UPDATE characters SET house_id = " + houseId + " WHERE id = " + id);
        // ★ 이 순간의 형제를 박제한다 (나중에 난 아우는 여기 없다)
        Rules rules = new Rules(Path.of("config"));
        sheet.put(Seojang.SHEET_KIN, snapshot(d, rules, id));
        d.updateCharacter(id, sheet, 0, "범인", "서장", "서장");
        return id;
    }

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
