package com.honcheon.bot;

import com.honcheon.core.rules.FactionReactionEngine;
import com.honcheon.core.rules.RulesConfig;
import com.honcheon.domain.FactionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 눈 — <b>개인 메인스토리 사슬(B-109)이 원장으로 마디를 판정하는가.</b>
 *
 * <p>임시 SQLite 에 캐릭터를 만들고 원장에 사건을 심어 잰다 (LLM·디스코드 불요):
 * <ol>
 *   <li>발단 → 사슬 선택 — ★ 시트의 밑줄→빈칸 치환을 <b>역치환</b>해 조인하는가 · 미등록 → default</li>
 *   <li>done_when 거짓→참 — 습격 ①(사냥 5): 4건은 안 닫히고 5건째 닫힌다 · <b>멱등</b>(tick 두 번 = 원장 1건)</li>
 *   <li>★ 키 정규화 — 대화 원장의 target 은 표시 이름("곽진")인데 조건은 키(gwakjin)다 · 대화_판정도 대화다</li>
 *   <li>소급 인정 — 뒤 마디(③ 벽)가 먼저 차면 앞 마디(①②)가 <b>함께</b> 닫힌다</li>
 *   <li>문턱(any_entry) — <b>어느 세력이든</b> favor≥4 (하오문으로 시험) · 단서 소지품 루트도 연다</li>
 *   <li>default 안전판 — 미등록 발단이 default 사슬로 떨어져 정상 판정된다</li>
 *   <li>등록부 소실 생존 — 등록부가 없어도 tick/heart 가 던지지 않는다 (봇은 죽지 않는다)</li>
 *   <li>수치 은닉 — 심중 문장에 "2/4" 류 진행 표기가 없다</li>
 * </ol>
 */
public final class PersonalStorySelfTest {

    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("honcheon-story");
        Path config = Path.of("config");
        PersonalStory story = new PersonalStory(config);
        if (story.disabled()) {
            System.out.println("☠ 등록부를 못 읽었다: " + story.fault());
            System.exit(1);
        }

        try (Db db = new Db(dir.resolve("t.db"), Path.of("db/schema.sql"))) {
            FactionService factions = new FactionService(
                    new FactionReactionEngine(RulesConfig.load(config.resolve("faction_reaction.yml"))), db);
            int today = db.worldDay();

            // ─── ① 발단 → 사슬 선택 (역치환·등록제) ───
            System.out.println("① 발단 → 사슬 선택");
            check("습격 → 습격", "습격".equals(story.chainKeyOf("습격")));
            check("\"가문의 몰락\"(시트 빈칸 표기) → 가문의_몰락 (역치환)",
                    "가문의_몰락".equals(story.chainKeyOf("가문의 몰락")));
            check("미등록 발단(낙뢰) → default", "default".equals(story.chainKeyOf("낙뢰")));

            // ─── ② done_when 거짓→참 + 멱등 (습격 ① 뿌리내림: 사냥 5) ───
            System.out.println("\n② done_when 판정 (습격 ①: 사냥 5)");
            long 습격이 = born(db, "d1", "습격이", "습격");
            for (int i = 0; i < 4; i++) {
                db.logEvent("사냥", "character", String.valueOf(습격이), Map.of("짐승", "늑대"));
            }
            story.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            check("사냥 4건 — 아직 안 닫힌다",
                    !db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(습격이), "습격:뿌리내림"));
            check("심중 = ① heart", heart(story, db, 습격이).contains("산이 무서운 동안은"));
            db.logEvent("사냥", "character", String.valueOf(습격이), Map.of("짐승", "늑대"));
            List<String> closed = story.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            check("사냥 5건째 — ① 이 닫힌다", closed.contains("뿌리내림")
                    && db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(습격이), "습격:뿌리내림"));
            check("심중이 ② heart 로 넘어갔다", heart(story, db, 습격이).contains("도적이 모이는 곳"));
            story.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            int marks = 0;
            for (Map<String, Object> e : db.eventsOf(PersonalStory.BEAT_EVENT, String.valueOf(습격이))) {
                if ("습격:뿌리내림".equals(String.valueOf(e.get("target_id")))) {
                    marks++;
                }
            }
            check("멱등 — tick 두 번인데 사슬_마디는 1건", marks == 1);

            // ─── ③ ★ 키 정규화 — 원장의 target 은 표시 이름("곽진"), 조건은 키(gwakjin) ───
            System.out.println("\n③ 키 정규화 (습격 ②: 사냥 15 + 대화 gwakjin)");
            for (int i = 0; i < 10; i++) {
                db.logEvent("사냥", "character", String.valueOf(습격이), Map.of("짐승", "멧돼지"));
            }
            story.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            check("사냥 15 만으로는 ② 가 안 닫힌다 (대화 gwakjin 미충족)",
                    !db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(습격이), "습격:실마리"));
            // GameListener 가 적는 그대로 — 표시 이름을 target 으로 심는다 (GameListener 대화부)
            db.logEvent("대화", "character", String.valueOf(습격이), "곽진", Map.of("말", "도적이 늘었다던데"));
            story.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            check("표시 이름 \"곽진\" 이 키 gwakjin 조건에 계수된다 — ② 닫힘",
                    db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(습격이), "습격:실마리"));
            // 판정 대화(대화_판정)도 대화다 — 박호에게 물은 것 (③ 벽의 절반)
            db.logEvent("대화_판정", "character", String.valueOf(습격이), "박호", Map.of("말", "수배장 좀"));
            story.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            check("대화_판정만으로는 ③ 이 안 닫힌다 (경지 미달 — realm_min 삼류)",
                    !db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(습격이), "습격:벽"));
            story.tick(db, factions, 습격이, today, "삼류", sheetOf(db, 습격이));
            check("삼류가 되자 ③ 이 닫힌다 (대화_판정 = 대화 계수)",
                    db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(습격이), "습격:벽"));

            // ─── ④ 소급 인정 — 뒤가 차면 앞도 닫힌다 (새 캐릭터, 사냥 0건) ───
            System.out.println("\n④ 소급 인정 (습격 ③ 만 채운다 — ①② 가 함께 닫히는가)");
            long 소급이 = born(db, "d2", "소급이", "습격");
            db.logEvent("대화", "character", String.valueOf(소급이), "박호", Map.of("말", "수배장"));
            List<String> retro = story.tick(db, factions, 소급이, today, "삼류", sheetOf(db, 소급이));
            check("③ 벽이 닫히며 ①②③ 이 한꺼번에 닫힌다 (사냥 0건인데)",
                    retro.containsAll(List.of("뿌리내림", "실마리", "벽")) && retro.size() == 3);
            check("심중 = ④ 문턱 heart", heart(story, db, 소급이).contains("천하의 소식이 모이는 곳"));

            // ─── ⑤ 문턱 — any_entry: 어느 세력이든 favor≥4 · 또는 등록 단서 소지 ───
            System.out.println("\n⑤ 문턱 any_entry (favor any ≥4 — 하오문 · item_any — 매화_목패)");
            factions.addFavor("haomun", 소급이, 3, today);
            story.tick(db, factions, 소급이, today, "삼류", sheetOf(db, 소급이));
            check("하오문 favor 3 — 문턱은 아직",
                    !db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(소급이), "습격:문턱"));
            factions.addFavor("haomun", 소급이, 1, today);
            story.tick(db, factions, 소급이, today, "삼류", sheetOf(db, 소급이));
            check("★ 하오문 favor 4 — 정파가 아니어도 문턱이 닫힌다 (faction: any)",
                    db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(소급이), "습격:문턱"));
            check("사슬 완주 — 심중 = 회향(epilogue_heart)",
                    heart(story, db, 소급이).contains("들고 갈 곳이 생겼다"));
            // 단서 소지품 루트 — 새 캐릭터가 매화_목패 하나로 문턱(과 소급으로 전 마디)을 연다
            long 목패니 = born(db, "d3", "목패니", "습격");
            Map<String, Object> sheet3 = sheetOf(db, 목패니);
            sheet3.put("소지품", List.of("매화_목패"));
            List<String> byItem = story.tick(db, factions, 목패니, today, "범인", sheet3);
            check("매화_목패 소지 — item_any 로 문턱이 닫힌다 (소급으로 4마디 전부)", byItem.size() == 4);

            // ─── ⑥ default 안전판 — 미등록 발단 ───
            System.out.println("\n⑥ default 안전판 (미등록 발단 → default 사슬)");
            long 무명이 = born(db, "d4", "무명이", "낙뢰");   // 등록부에 없는 발단
            check("심중 = default ① heart", heart(story, db, 무명이).contains("잠잘 곳과 한 끼"));
            for (int i = 0; i < 3; i++) {
                db.logEvent("의뢰_완수", "character", String.valueOf(무명이), Map.of("의뢰", "잔심부름"));
            }
            story.tick(db, factions, 무명이, today, "범인", sheetOf(db, 무명이));
            check("의뢰 3건 — default ① 이 닫힌다",
                    db.eventExists(PersonalStory.BEAT_EVENT, String.valueOf(무명이), "default:뿌리내림"));

            // ─── ⑦ 등록부 소실 생존 ───
            System.out.println("\n⑦ 등록부 소실 생존 (없는 디렉터리로 연다)");
            PersonalStory broken = new PersonalStory(dir.resolve("없는곳"));
            check("잠겼다고 말한다 (disabled)", broken.disabled() && broken.fault() != null);
            List<String> quiet = broken.tick(db, factions, 습격이, today, "범인", sheetOf(db, 습격이));
            check("tick 이 던지지 않고 빈손", quiet.isEmpty());
            check("heart 가 던지지 않고 null", broken.heart(db, 습격이, sheetOf(db, 습격이)) == null);

            // ─── ⑧ 수치 은닉 — 심중에 "2/4" 류 표기가 없다 ───
            System.out.println("\n⑧ 수치 은닉");
            java.util.regex.Pattern leak = java.util.regex.Pattern.compile("\\d+\\s*/\\s*\\d+");
            boolean clean = true;
            for (long id : new long[]{습격이, 소급이, 무명이}) {
                String h = heart(story, db, id);
                if (h == null || leak.matcher(h).find()) {
                    clean = false;
                }
            }
            check("심중 문장에 진행 수치가 새지 않는다", clean);
        }

        System.out.println(fail == 0 ? "\nPASS — 사슬이 원장으로 판정한다 (" + dir + ")"
                : "\nFAIL — " + fail + "건 (" + dir + ")");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static long born(Db db, String discordId, String name, String incident) throws Exception {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("발단", incident.replace('_', ' '));   // GameListener 탄생부와 같은 치환
        return db.createCharacter(discordId, name, sheet, 10);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sheetOf(Db db, long id) throws Exception {
        return new LinkedHashMap<>((Map<String, Object>) db.findCharacterById(id).get().get("sheet"));
    }

    private static String heart(PersonalStory story, Db db, long id) throws Exception {
        return story.heart(db, id, sheetOf(db, id));
    }

    private static void check(String what, boolean ok) {
        System.out.println("   " + (ok ? "✓ " : "☠ ") + what);
        if (!ok) {
            fail++;
        }
    }
}
