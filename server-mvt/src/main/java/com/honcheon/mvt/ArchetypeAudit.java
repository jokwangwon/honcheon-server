package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>원형 대조 — 「그 집이 그 집이 아닌가」를 재는 눈.</b>
 *
 * <h2>왜 이 눈이 필요한가 — 기존 검수는 오늘의 병을 못 잡는다</h2>
 * {@link RegionAudit} 는 <b>한 집씩</b> 본다: "이 집에 매화가 20장 있는가". 그래서
 * <b>소림에 매화 20장이 있어도 통과시켰다</b> — 계약이 「도관」이었으니 계약대로 서 있었기 때문이다.
 * <b>위반 0건이 찍혔고, 여덟 문파는 같은 집이었다.</b>
 *
 * <p>병은 <b>한 집 안</b>에 없었다. <b>집들 사이</b>에 있었다.
 * 그러므로 눈도 <b>집들 사이</b>를 봐야 한다 — 그것이 이 파일이다.
 *
 * <h2>세 가지를 잰다</h2>
 * <ol>
 *   <li><b>계약의 구별</b>({@link #distinctness}) — 월드가 없어도 돈다.
 *       두 원형의 계약 자재 집합이 <b>같으면 그 둘은 같은 집이다.</b>
 *       그리고 원형마다 <b>저만 가진 자재</b>(고유 자재)가 하나는 있어야 한다 —
 *       없으면 검수가 그 집을 <b>지목할 수 없다</b></li>
 *   <li><b>금지 자재</b>({@link #forbidden}) — ★ <b>이것이 오늘의 병을 직접 겨눈다.</b>
 *       "소림에 <b>매화가 0장</b>인가" · "곤륜에 <b>회벽이 0칸</b>인가" ·
 *       "★ <b>산채에 석축이 0칸</b>인가"(녹림 석채의 예외가 번졌는가) ·
 *       "아미에 <b>분홍이 0칸</b>인가"(사용자가 금한 것)</li>
 *   <li><b>실루엣</b>({@link #silhouette}) — 지은 것의 <b>높이와 부피</b>.
 *       탑이 선 집과 안 선 집은 <b>수가 다르다</b></li>
 * </ol>
 *
 * <h2>★ 눈을 시험한다</h2>
 * {@link #selfTest} 가 <b>일부러 어긴다</b>: 「소림인데 도관으로 지어진 집」의 자재 명세를 만들어
 * 눈에 먹인다. <b>눈이 짖지 않으면 눈이 없는 것이다.</b>
 */
final class ArchetypeAudit {

    private ArchetypeAudit() {
    }

    private static final String OK = "§a✅ ";
    private static final String BAD = "§c❌ ";
    private static final String HEAD = "§6";
    private static final String INFO = "§7";

    /**
     * <b>금지 자재</b> — 그 집에 <b>있으면 안 되는 것</b>.
     *
     * <p>계약(있어야 하는 것)만으로는 오늘의 병을 못 잡는다. 「도관」의 계약을 소림에 적용해도
     * 계약은 <b>충족된다</b> — 매화 20장이 실제로 심겼으니까. 병을 잡는 것은 <b>부정</b>이다:
     * <b>소림에 매화가 있으면 그것은 소림이 아니다.</b>
     *
     * <p>여기 적힌 것은 전부 <b>등록부와 설계 문서의 말</b>이다. 지어내지 않았다.
     */
    static List<Need> forbidden(RemoteBuilder.Archetype kind) {
        if (kind == null) {
            return List.of();
        }
        return switch (kind) {
            // ★ 산채 — **석축 예외가 번졌는가**. noklim_seokchae.md 의 마지막 검수 기준이 이것이다:
            //   "★ 녹림 총채 이외의 산채에 석축 예외가 전파되지 않는가"
            case 산채 -> List.of(
                    new Need(Material.STONE_BRICKS, 20, "★ 석축 예외의 전파 — 산채는 **목책**이다 (noklim_seokchae.md)"),
                    new Need(Material.DEEPSLATE_TILES, 20, "기와 — 도적은 기와를 얹지 않는다"));
            case 도관 -> List.of(
                    new Need(Material.BRICKS, 20, "벽돌 — 그것은 소림의 전탑이다"),
                    new Need(Material.GOLD_BLOCK, 1, "금 — 그것은 무당의 금정이다"));
            // ★★ 사찰 — **오늘의 병을 직접 겨눈다.** 숭산 대찰에 매화 20장이 심겨 있었다
            case 사찰 -> List.of(
                    new Need(Material.CHERRY_LEAVES, 1, "★★ **매화** — 여기는 숭산이다. 불문의 나무는 **보리수**다"),
                    new Need(Material.CHERRY_LOG, 1, "★★ 매화나무 줄기"),
                    new Need(Material.GOLD_BLOCK, 1, "금 — 그것은 무당의 것이다"));
            case 전각 -> List.of(
                    new Need(Material.CHERRY_LEAVES, 1, "매화 — 그것은 화산의 것이다"),
                    new Need(Material.BRICKS, 20, "벽돌 — 그것은 소림의 전탑이다"));
            // ★ 암자 — **가난하다.** 기와도 등롱도 없다 (등록부: "화산보다 가난하고 조용하다")
            case 암자 -> List.of(
                    new Need(Material.DEEPSLATE_TILES, 20, "★ 기와 — 암자는 **초가**다 (가난하다)"),
                    new Need(Material.LANTERN, 1, "★ 등롱 — 암자에는 **횃불뿐**이다"),
                    new Need(Material.CHERRY_LEAVES, 1, "매화 — 그것은 화산의 것이다"),
                    new Need(Material.WHITE_TERRACOTTA, 10, "회벽 — 흩어진 암자는 회를 바르지 않는다"));
            // ★★ 석조도관 — **설선 위에 회벽을 발랐다**. 그것이 오늘 잡힌 두 번째 거짓말이다
            case 석조도관 -> List.of(
                    new Need(Material.WHITE_TERRACOTTA, 10, "★★ **회벽** — 설선 위다. 눈보라에 회를 바르지 않는다"),
                    new Need(Material.CHERRY_LEAVES, 1, "★★ **매화** — terrain.yml: 설원 · snowline 120. **눈 위에 매화는 안 핀다**"),
                    new Need(Material.DEEPSLATE_TILES, 20, "★ 검은 기와 — 곤륜의 지붕은 **돌**이다"));
            // ★ 목조검문 — **담이 없다.** 산문도 천 계단도 없다 (§16)
            case 목조검문 -> List.of(
                    new Need(Material.STONE_BRICKS, 30, "★ 돌벽 — 해남에는 **담이 없다**"),
                    new Need(Material.WHITE_TERRACOTTA, 10, "회벽 — 소금바람이 회를 먹는다"),
                    new Need(Material.CHERRY_LEAVES, 1, "매화 — 그것은 화산의 것이다"));
            // ★★ 비구니원 — 사용자가 **직접 금한** 것들 (ami_architecture.md §조성 원칙)
            case 비구니원 -> List.of(
                    new Need(Material.CHERRY_LEAVES, 1, "★★ **꽃문양·분홍** — 사용자가 금했다: '여성적 장식을 붙이지 않는다'"),
                    new Need(Material.CHERRY_LOG, 1, "★★ 벚나무"),
                    new Need(Material.PINK_WOOL, 1, "★★ **분홍** — 사용자가 금했다"),
                    new Need(Material.PINK_TERRACOTTA, 1, "★★ 분홍"),
                    new Need(Material.GOLD_BLOCK, 1, "금색 — ami_architecture.md: '최소화: 금색·선명한 주홍'"));
            case 폐쇄대저택 -> List.of(
                    new Need(Material.STONE_BRICK_WALL, 30, "★ 담장 블록 — 당가의 담은 **막힌 벽**이다 (너머가 안 보인다)"));
            case 군사저택 -> List.of(
                    new Need(Material.FARMLAND, 20, "★ 약재밭 — 그것은 당가의 마당이다. 팽가의 마당은 **연무장**이다"));
            case 정원저택 -> List.of(
                    new Need(Material.BRICKS, 20, "벽돌 — 그것은 소림의 전탑이다"));
            case 북방저택 -> List.of(
                    new Need(Material.FARMLAND, 20, "약재밭 — 그것은 당가의 것이다"));
            case 관아 -> List.of(
                    new Need(Material.CHERRY_LEAVES, 1, "매화 — 관아는 문파가 아니다"));
            // ★ 관문 — **저잣거리가 없다.** 성문과 갈리는 지점이다 (여기는 지나가는 곳이다)
            case 관문 -> List.of(
                    new Need(Material.RED_WOOL, 4, "★ 저잣거리의 차양 — 관문에서 장사하는 자는 없다 (그것이 **성문**이다)"),
                    new Need(Material.BAMBOO_PLANKS, 10, "★ 노점의 좌판 — 여기는 **지나가는 곳**이다"));
            // ★ 녹림석채 — 석축이되 **목책이 아니다** (산채와 즉시 구별되어야 한다)
            case 녹림석채 -> List.of(
                    new Need(Material.SPRUCE_LOG, 120, "★ **목책** — 석채는 목책을 두르지 않는다 (그것이 예외의 뜻이다)"));
            // ★ 흑성 — **중원 건축이 아니다** (사용자)
            case 흑성 -> List.of(
                    new Need(Material.DEEPSLATE_TILES, 10, "★ 검은 **기와** — 중원의 지붕이다. 흑성은 **평지붕**이다"),
                    new Need(Material.WHITE_TERRACOTTA, 10, "★ 회벽 — 중원의 벽이다"),
                    new Need(Material.LANTERN, 1, "★ 등롱 — 흑성의 불은 **냉색**이다 (영혼 등불)"),
                    new Need(Material.CHERRY_LEAVES, 1, "매화"));
            // ★★ 천막 — **담도 성벽도 없다** (사용자가 별표로 못박았다)
            case 천막 -> List.of(
                    new Need(Material.STONE_BRICKS, 20, "★★ **성벽** — 사용자: '성이 없다. 담도 성벽도 없다'"),
                    new Need(Material.COBBLESTONE_WALL, 8, "★★ 담"),
                    new Need(Material.DEEPSLATE_TILES, 10, "기와 — 유목민은 기와를 지고 다니지 않는다"),
                    new Need(Material.LANTERN, 1, "등롱 — 들고 다닐 수 없다"));
            // ★★ 유배지 — **담이 없다. 그리고 가두지 않는다** (사용자: "감옥이 아니라 유배다")
            case 유배지 -> List.of(
                    new Need(Material.IRON_BARS, 1, "★★ **쇠창살** — 사용자: '감옥이 아니라 유배다'. 가두지 않는다"),
                    new Need(Material.STONE_BRICKS, 20, "★★ **담** — '담이 없다. 도망칠 데가 없으므로'"),
                    new Need(Material.DEEPSLATE_TILES, 10, "기와 — 유배지에 기와를 얹지 않는다"));
            default -> List.of();
        };
    }

    /** 금지 항목 — {@code max} 개 <b>이상</b>이면 위반이다 (0 이 아니라 상한이다: 흘린 한두 칸은 봐준다) */
    record Need(Material material, int max, String why) {
    }

    // ══════════════════════════════════════════════════════════════════
    //  ① 계약의 구별 — 월드가 없어도 돈다. **표 자체가 거짓말인가**를 본다
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>원형끼리 구별되는가</b> — 계약 자재의 집합을 견준다.
     *
     * <p>둘의 계약 자재 집합이 <b>같으면 그 둘은 같은 집이다</b> (오늘의 병이 정확히 그것이었다:
     * 여덟이 「도관」의 계약 하나를 공유했다). 그리고 원형마다 <b>고유 자재</b>가 하나는 있어야 한다 —
     * 없으면 검수가 그 집을 <b>지목할 수 없고</b>, 지목 못 하는 집은 다른 집과 섞여도 아무도 모른다.
     */
    static List<String> distinctness() {
        List<String> out = new ArrayList<>();
        RemoteBuilder.Archetype[] all = RemoteBuilder.Archetype.values();
        out.add(HEAD + "══ 원형 대조 — 집들이 서로 구별되는가 (원형 " + all.length + "종) ══");

        Map<RemoteBuilder.Archetype, Set<Material>> sig = new EnumMap<>(RemoteBuilder.Archetype.class);
        for (RemoteBuilder.Archetype k : all) {
            Set<Material> s = new LinkedHashSet<>();
            for (RemoteBuilder.Need n : RemoteBuilder.contract(k)) {
                s.add(n.material());
            }
            sig.put(k, s);
        }

        int violations = 0;
        // ─ ① 두 원형의 계약이 같은가 (= 같은 집인가)
        out.add(HEAD + "① 계약 충돌 — 두 집이 같은 자재로 서 있는가");
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                Set<Material> a = sig.get(all[i]);
                Set<Material> b = sig.get(all[j]);
                if (a.isEmpty() || b.isEmpty()) {
                    continue;
                }
                Set<Material> both = new LinkedHashSet<>(a);
                both.retainAll(b);
                Set<Material> union = new LinkedHashSet<>(a);
                union.addAll(b);
                double jaccard = (double) both.size() / union.size();
                if (jaccard >= 0.8) {
                    out.add(BAD + "  " + all[i] + " ≡ " + all[j] + " — 계약이 "
                            + Math.round(jaccard * 100) + "% 같다. **두 집이 한 집이다**");
                    violations++;
                } else if (jaccard >= 0.6) {
                    // 위반은 아니다 — 그러나 **감추지 않는다.** 관(官)의 집들은 실제로 같은 돌을 쌓는다
                    out.add(INFO + "  ⚠ " + all[i] + " ~ " + all[j] + " — 계약이 "
                            + Math.round(jaccard * 100) + "% 겹친다 (자재가 아니라 **기하**가 가른다)");
                }
            }
        }
        if (violations == 0) {
            out.add(OK + "  충돌 없음 — 어느 두 원형도 계약이 80% 이상 겹치지 않는다");
        }

        // ─ ② 지목 가능성 — <b>집은 있는 것으로 말하거나 없는 것으로 말한다</b>
        //     둘 다 안 하면 그 집은 이름이 없고, 이름 없는 집은 다른 집과 섞여도 아무도 모른다.
        //     ★ 은신처·암자·천막·유배지는 **없는 것으로 자기를 말하는 집**이다 (담이 없다·등롱이 없다·기와가 없다).
        //       그런 집에 "고유 자재"를 요구하면 그 집의 정의를 부수게 된다 — 그래서 금지 목록도 표식으로 센다.
        out.add(HEAD + "② 지목 가능성 — 검수가 그 집을 **지목할 수 있는가** (있는 것 또는 없는 것으로)");
        for (RemoteBuilder.Archetype k : all) {
            List<Material> only = new ArrayList<>();
            for (Material m : sig.get(k)) {
                boolean unique = true;
                for (RemoteBuilder.Archetype other : all) {
                    if (other != k && sig.get(other).contains(m)) {
                        unique = false;
                        break;
                    }
                }
                if (unique) {
                    only.add(m);
                }
            }
            List<Need> no = forbidden(k);
            if (!only.isEmpty()) {
                out.add(INFO + "  " + k + " — 있는 것: " + only);
            } else if (!no.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Need n : no) {
                    names.add(n.material().name());
                }
                out.add(INFO + "  " + k + " — 없는 것: " + names + " (부정으로 자기를 말한다)");
            } else {
                out.add(BAD + "  " + k + " — ★ **표식이 없다.** 있는 것도 없는 것도 이 집을 지목하지 못한다");
                violations++;
            }
        }

        // ─ ③ 금지 자재 — 부정으로 자기를 말하는가
        out.add(HEAD + "③ 금지 자재 — **있으면 그 집이 아닌 것**");
        for (RemoteBuilder.Archetype k : all) {
            List<Need> no = forbidden(k);
            if (no.isEmpty()) {
                out.add(INFO + "  " + k + " — (금지 없음)");
                continue;
            }
            List<String> names = new ArrayList<>();
            for (Need n : no) {
                names.add(n.material().name());
            }
            out.add(INFO + "  " + k + " — " + names);
        }

        out.add(HEAD + "── 총평 ──");
        out.add(violations == 0
                ? OK + "위반 0건 — **원형들이 서로 다른 집이다**"
                : BAD + "위반 " + violations + "건 — ★ **오늘의 병이 재발했다** (같은 집이 여럿 있다)");
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ② 실물 대조 — 지어 놓은 집이 정말 그 집인가
    // ══════════════════════════════════════════════════════════════════

    /**
     * 실제로 선 집을 본다 — <b>금지 자재가 섞였는가</b>.
     * ({@link RegionAudit} 의 ②가 "있어야 할 것"을 세고, 여기서 "있으면 안 되는 것"을 센다.)
     */
    static List<String> audit(World world, WorldMap.Place place, Zone zone) {
        List<String> out = new ArrayList<>();
        RemoteBuilder.Archetype kind = RemoteBuilder.archetype(place);
        out.add(HEAD + "══ 원형 대조 — " + place.name() + " (원형: " + kind + ") ══");
        if (kind == null) {
            out.add(INFO + "  원형이 없다 — 대조할 것이 없다");
            return out;
        }
        Map<Material, Integer> census = census(world, zone);
        out.addAll(check(kind, census));
        out.add(INFO + "  실루엣 — " + silhouette(world, zone));
        return out;
    }

    /** 자재 명세를 받아 금지 항목을 검산한다 — <b>월드 없이도 돈다</b> (그래서 시험할 수 있다) */
    static List<String> check(RemoteBuilder.Archetype kind, Map<Material, Integer> census) {
        List<String> out = new ArrayList<>();
        int bad = 0;
        for (Need n : forbidden(kind)) {
            int have = census.getOrDefault(n.material(), 0);
            if (have >= n.max()) {
                out.add(BAD + "  " + n.why() + " — " + n.material().name() + " " + have
                        + "개 (상한 " + (n.max() - 1) + ")");
                bad++;
            }
        }
        // 계약도 함께 본다 — "있어야 할 것"이 없으면 그것도 그 집이 아니다
        for (RemoteBuilder.Need n : RemoteBuilder.contract(kind)) {
            int have = census.getOrDefault(n.material(), 0);
            if (have < n.min()) {
                out.add(BAD + "  " + n.what() + " 없음 — " + n.material().name() + " " + have
                        + "개 < " + n.min());
                bad++;
            }
        }
        out.add(bad == 0
                ? OK + "  이 집은 「" + kind + "」다 (금지 자재 0 · 계약 충족)"
                : BAD + "  ★ 이 집은 「" + kind + "」가 **아니다** — 위반 " + bad + "건");
        return out;
    }

    /** 실루엣 — 높이와 부피. 탑이 선 집과 안 선 집은 <b>수가 다르다</b> */
    static String silhouette(World world, Zone zone) {
        int top = zone.y1();
        long solid = 0;
        for (int x = zone.x1(); x <= zone.x2(); x += 2) {
            for (int z = zone.z1(); z <= zone.z2(); z += 2) {
                for (int y = zone.y2(); y >= zone.y1(); y--) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    solid++;
                    if (y > top) {
                        top = y;
                    }
                }
            }
        }
        return "최고점 y" + top + " · 고체 표본 " + solid + "칸 (표본 간격 2)";
    }

    private static Map<Material, Integer> census(World world, Zone zone) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (int x = zone.x1(); x <= zone.x2(); x++) {
            for (int z = zone.z1(); z <= zone.z2(); z++) {
                for (int y = zone.y1(); y <= zone.y2(); y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (!m.isAir()) {
                        out.merge(m, 1, Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★★ 눈을 시험한다 — <b>일부러 어긴다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>눈이 정말 보는가.</b> 이 프로젝트의 규약: <i>"눈을 만들면 눈을 시험하라 —
     * 일부러 어겨서 잡는지 확인하고 보고"</i>.
     *
     * <p>세 가지 거짓말을 <b>지어내서</b> 눈에 먹인다. 셋 다 <b>실제로 있었던 일</b>이다:
     * <ol>
     *   <li><b>소림을 도관으로 지었다</b> — 숭산 대찰에 매화 20장, 탑이 없다 (오늘 잡힌 그것)</li>
     *   <li><b>곤륜에 회벽을 발랐다</b> — 설선 위에 흰 회벽 200칸 (오늘 잡힌 그것)</li>
     *   <li><b>녹림 석채의 석축이 소채로 번졌다</b> — 산채에 돌벽돌 400칸
     *       (사용자가 명시적으로 금한 것: noklim_seokchae.md 최종 검수 기준)</li>
     * </ol>
     * 그리고 <b>대조군</b>: 제대로 지은 소림. 이것은 <b>통과해야</b> 한다 —
     * 무엇이든 짖는 눈은 눈이 아니라 소음이다.
     */
    static List<String> selfTest() {
        List<String> out = new ArrayList<>();
        out.add(HEAD + "══ ★ 눈 시험 — 일부러 어겨서 잡는지 본다 ══");
        int caught = 0;
        int expected = 3;

        // ① 소림을 도관으로 지었다 (오늘의 병 그 자체)
        Map<Material, Integer> asCloister = new EnumMap<>(Material.class);
        asCloister.put(Material.DEEPSLATE_TILES, 240);
        asCloister.put(Material.WHITE_TERRACOTTA, 180);
        asCloister.put(Material.POLISHED_ANDESITE, 900);
        asCloister.put(Material.CHERRY_LEAVES, 24);      // ★ 숭산에 매화
        asCloister.put(Material.LANTERN, 12);
        asCloister.put(Material.STONE_BRICKS, 400);
        caught += barked(out, "① 소림을 「도관」으로 지었다 (매화 24 · 탑 없음)",
                RemoteBuilder.Archetype.사찰, asCloister);

        // ② 곤륜에 회벽 (설선 위의 회벽 — 오늘 잡힌 두 번째)
        Map<Material, Integer> plasteredKunlun = new EnumMap<>(Material.class);
        plasteredKunlun.put(Material.STONE_BRICKS, 600);
        plasteredKunlun.put(Material.POLISHED_DEEPSLATE, 200);
        plasteredKunlun.put(Material.STONE_BRICK_STAIRS, 80);
        plasteredKunlun.put(Material.CHISELED_STONE_BRICKS, 6);
        plasteredKunlun.put(Material.LANTERN, 6);
        plasteredKunlun.put(Material.WHITE_TERRACOTTA, 200);   // ★ 설선 위의 회벽
        plasteredKunlun.put(Material.CHERRY_LEAVES, 20);       // ★ 눈 위의 매화
        caught += barked(out, "② 곤륜에 회벽 200칸 · 눈 위에 매화 20장",
                RemoteBuilder.Archetype.석조도관, plasteredKunlun);

        // ③ ★ 석축 예외가 소채로 번졌다 (사용자가 명시적으로 금한 것)
        Map<Material, Integer> stonyStockade = new EnumMap<>(Material.class);
        stonyStockade.put(Material.SPRUCE_LOG, 260);
        stonyStockade.put(Material.CAMPFIRE, 2);
        stonyStockade.put(Material.HAY_BLOCK, 30);
        stonyStockade.put(Material.TORCH, 4);
        stonyStockade.put(Material.STONE_BRICKS, 400);   // ★ 석채의 예외가 번졌다
        caught += barked(out, "③ 녹림 소채에 석축 400칸 (예외의 전파 — noklim_seokchae.md 가 금했다)",
                RemoteBuilder.Archetype.산채, stonyStockade);

        // ④ 대조군 — 제대로 지은 소림. **짖으면 안 된다**
        Map<Material, Integer> proper = new EnumMap<>(Material.class);
        proper.put(Material.BRICKS, 420);              // 전탑
        proper.put(Material.OAK_LEAVES, 90);           // 보리수
        proper.put(Material.STONE_BRICKS, 700);        // 월대
        proper.put(Material.DEEPSLATE_TILES, 380);
        proper.put(Material.LANTERN, 14);
        List<String> quiet = check(RemoteBuilder.Archetype.사찰, proper);
        boolean barked = quiet.stream().anyMatch(s -> s.startsWith(BAD));
        out.add(HEAD + "④ 대조군 — 제대로 지은 소림 (짖으면 눈이 고장난 것이다)");
        out.add(barked ? BAD + "  ★ 눈이 멀쩡한 집에 짖었다 — **눈이 고장났다**"
                : OK + "  조용하다 — 눈이 아무거나 짖지 않는다");

        out.add(HEAD + "── 총평 ──");
        out.add(caught == expected && !barked
                ? OK + "★ 눈이 산다 — 거짓말 " + expected + "개를 전부 잡았고, 참말에는 짖지 않았다"
                : BAD + "★ **눈이 죽었다** — 잡은 것 " + caught + "/" + expected
                  + (barked ? " · 게다가 참말에 짖었다" : ""));
        return out;
    }

    /** 거짓말 하나를 먹이고 짖는지 본다 */
    private static int barked(List<String> out, String lie, RemoteBuilder.Archetype kind,
                              Map<Material, Integer> census) {
        List<String> said = check(kind, census);
        boolean bark = said.stream().anyMatch(s -> s.startsWith(BAD));
        out.add(HEAD + lie);
        for (String s : said) {
            if (s.startsWith(BAD)) {
                out.add(INFO + "    ↳ " + s.substring(2));
            }
        }
        out.add(bark ? OK + "  ★ 눈이 짖었다 — 잡았다" : BAD + "  ★ **눈이 못 봤다** — 이 눈은 없는 눈이다");
        return bark ? 1 : 0;
    }
}
