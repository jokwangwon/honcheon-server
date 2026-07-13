package com.honcheon.mvt;

import org.bukkit.World;

/**
 * 지형과 건축을 <b>가르는 문</b> — 조성기는 이 문으로만 땅을 얻는다.
 *
 * <p>사용자 판정 (헌법):
 * <blockquote>
 * "<b>땅에 맞게 건물이 올라가는 것이지 건축에 맞게 지형 생기는 게 아니다.</b>
 *  건축이 수정되어도 <b>지형이 변화하면 안 된다.</b>"
 * </blockquote>
 *
 * <p>구조가 그것을 막고 있었다 — {@code prepare → carve → digCave → build} 가 <b>한 세션</b>이라
 * <b>건물을 다시 지으려면 땅을 다시 빚는 수밖에 없었다.</b> 그리고 땅을 다시 빚으면
 * 지난번 것 위에 또 만든다 (<b>산 위에 산을 쌓던 병</b>).
 *
 * <h2>이제 두 길이다</h2>
 * <pre>
 *   땅이 없다 →  {@link #raise}  : 빚는다 → 강 → 요청 → 굴 → <b>원장에 적는다</b>
 *   땅이 있다 →  {@link #read}   : <b>측량만 한다</b>. 블록을 하나도 안 건드린다
 * </pre>
 * 조성기는 {@link #land} 하나만 부른다 — <b>어느 길인지는 원장이 안다.</b>
 *
 * <p>그래서 <b>건물만 다시 짓는 것</b>이 가능해진다. 그리고 그때 땅은 <b>한 블록도 안 바뀐다</b> —
 * {@link TerrainSeal} 이 그것을 <b>잰다</b>.
 */
final class Terraform {

    private Terraform() {
    }

    /** 땅과 굴. {@code forged} = 이번에 <b>새로 빚었는가</b> (거짓이면 원장에서 읽었다) */
    record Land(TerrainForge.SiteSpec spec, TerrainForge.CaveSpec cave, boolean forged, int requests) {
    }

    /**
     * <b>땅을 얻는다.</b> 이미 선 땅이면 다시 빚지 않는다.
     *
     * @param cx 등록 좌표에서 결정된 부지 중심 — <b>원장에 자리가 있으면 그것이 이긴다</b>
     */
    static Land land(World world, WorldMap.Place place, int cx, int cy, int cz, int radius) {
        TerrainLedger.Land known = TerrainLedger.land(place.id());
        return known != null ? read(world, place, known) : raise(world, place, cx, cy, cz, radius);
    }

    /** 왜 더하지 못했는가 — <b>조용히 넘어가지 않는다</b> */
    static final java.util.List<String> refusals = new java.util.ArrayList<>();

    /**
     * <b>땅을 갈아엎는다</b> — 원지(原地)로 되돌리고 장부를 지운다. 다음 조성이 처음부터 빚는다.
     *
     * <p>★ 구판은 <b>장부만</b> 지웠다. 그러면 다음 조성이 <b>이미 만든 산 위에 또 산을 쌓는다.</b>
     * <b>반쪽 명령은 사용자를 속인다.</b>
     *
     * <p><b>원지를 안 적어 둔 땅은 되돌릴 수 없다</b> — 그러면 {@code false} 를 돌려주고,
     * 부르는 쪽이 <b>세계 재조성을 권해야 한다</b>. 안 되는 것을 된다고 하지 않는다.
     *
     * @return 되돌린 열 수. <b>-1 이면 되돌릴 수 없다</b> (원지가 없다)
     */
    static int razeLand(World world, String placeId) {
        int n = TerrainBaseline.restore(world, placeId);
        if (n < 0) {
            return -1;   // 원지가 없다 — **장부를 지우면 안 된다** (지우면 산 위에 산을 쌓는다)
        }
        TerrainBaseline.forget(placeId);
        TerrainLedger.forget(placeId);
        RiverForge.forget(placeId);
        return n;
    }

    /**
     * 이 땅이 <b>지금</b> 요구하는 일들의 신원 — 등록부(rivers.yml · land_requests.yml)가 말하는 것.
     */
    private static java.util.List<String> wanted(WorldMap.Place place) {
        java.util.List<String> want = new java.util.ArrayList<>();
        String river = RiverForge.fingerprint(place);
        if (river != null) {
            want.add(river);
        }
        for (LandRequest.Req r : LandRequest.of(place.id())) {
            want.add(LandRequest.fingerprint(r));
        }
        return want;
    }

    /**
     * <b>땅을 읽는다</b> — 이미 빚어진 땅. <b>블록을 하나도 안 건드린다.</b>
     *
     * <p>좌표·기준면·봉우리를 <b>원장에서</b> 가져온다. 세계에서 다시 재면 안 된다 —
     * 우리가 판 강과 건축이 놓은 포석이 {@code naturalGround} 의 답을 바꾸어
     * <b>기준면이 조성 때마다 한 칸씩 내려앉는다</b> (그러면 마당이 재조성마다 깊어진다).
     */
    static Land read(World world, WorldMap.Place place, TerrainLedger.Land l) {
        refusals.clear();
        TerrainForge.SiteSpec spec = TerrainForge.surveyAt(world, place,
                l.cx(), l.groundY(), l.cz(), l.radius(), l.peakX(), l.peakZ(), l.peakY());
        spec = RiverForge.describe(spec, place);   // 수변 방위는 강이 안다 (사분면표가 아니라)

        // ─── ★ 더하기 — 등록부에는 있는데 장부에 없는 일만 집행한다 ────────────────
        //   사용자: "이미 완성된 조성에 **강 하나 더 만들어줘, 산 하나 더 세워줘**"
        //   → 그 하나만 선다. **나머지 땅은 한 블록도 안 움직인다** (TerrainSeal 이 증명한다).
        java.util.Set<String> done = l.done();
        java.util.List<String> added = new java.util.ArrayList<>();

        // 강 — 등록부에 물길이 생겼는데 장부에 없으면 **판다**
        String river = RiverForge.fingerprint(place);
        if (river != null && !done.contains(river)) {
            boolean renamed = done.stream().anyMatch(w -> w.startsWith("강:"));
            if (renamed) {
                // 이미 강이 있는 땅이다. 등록부가 바뀌었다고 **또 팔 수는 없다** — 땅은 못 되돌린다
                refusals.add(place.name() + " — 이 땅엔 이미 강이 있는데 rivers.yml 이 바뀌었다. "
                        + "땅은 되돌릴 수 없다: /혼천 땅갈아엎기 로 처음부터 빚어라");
            } else {
                spec = RiverForge.carve(world, place, spec);
                if (RiverForge.lastRefusal == null) {
                    added.add(river);
                }
            }
        }

        // 요청 — 차집합만
        java.util.List<String> newReqs = LandRequest.applyPending(world, spec, done);
        refusals.addAll(LandRequest.refusals);
        added.addAll(newReqs);

        if (!added.isEmpty()) {
            // 땅이 바뀌었다 — 마스크를 다시 잰다 (안 그러면 건축이 새 개울 위에 집을 짓는다).
            // ★ 기준면·봉우리는 **원장의 것을 그대로 쓴다** — 그것이 정본이고, 그래서 마을이 안 묻힌다
            spec = TerrainForge.surveyAt(world, place, l.cx(), l.groundY(), l.cz(),
                    l.radius(), l.peakX(), l.peakZ(), l.peakY());
            spec = RiverForge.describe(spec, place);
            TerrainLedger.append(place.id(), added);
        }
        return new Land(spec, TerrainLedger.cave(l, world.getName()), false, added.size());
    }

    /**
     * <b>땅을 빚는다</b> — 처음 서는 땅. 순서가 계약이다.
     *
     * <p>{@code prepare}(빚는다) → {@code RiverForge}(강을 판다) → {@link LandRequest}(요청을 집행한다)
     * → {@code digCave}(굴을 판다) → <b>원장에 적는다</b>.
     *
     * <p><b>요청이 강 다음인 이유</b>: 마을의 개울은 강가에 날 수 있고, 강은 요청보다 크다.
     * <b>굴이 마지막인 이유</b>: 굴 입구는 다 빚어진 비탈에 뚫려야 한다.
     */
    static Land raise(World world, WorldMap.Place place, int cx, int cy, int cz, int radius) {
        refusals.clear();
        // ★ 처음 빚기 전에 **원래의 땅을 적어 둔다** — 그래야 되돌릴 수 있다 (TerrainBaseline)
        TerrainBaseline.capture(world, place.id(), cx, cz, radius + TerrainForge.FEATHER_WIDTH, cy);

        TerrainForge.SiteSpec spec = TerrainForge.prepare(world, place, cx, cy, cz, radius);
        java.util.List<String> works = new java.util.ArrayList<>();

        spec = RiverForge.carve(world, place, spec);
        String river = RiverForge.fingerprint(place);
        if (river != null && RiverForge.lastRefusal == null) {
            works.add(river);
        }

        java.util.List<String> reqs = LandRequest.applyPending(world, spec, java.util.Set.of());
        refusals.addAll(LandRequest.refusals);
        works.addAll(reqs);
        int done = reqs.size();
        if (done > 0) {
            // 요청이 땅을 바꿨다 — 마스크·수변을 다시 잰다 (안 그러면 건축이 개울 위에 집을 짓는다)
            spec = TerrainForge.surveyAt(world, place, spec.cx(), spec.groundY(), spec.cz(),
                    spec.radius(), spec.peakX(), spec.peakZ(), spec.peakY());
            spec = RiverForge.describe(spec, place);
        }

        TerrainForge.CaveKind kind = TerrainForge.caveKind(place);
        TerrainForge.CaveSpec cave = kind == null ? null : TerrainForge.digCave(world, spec, kind);

        TerrainLedger.remember(spec, cave, works);   // ★ 이 줄이 적히는 순간 이 땅은 굳는다
        return new Land(spec, cave, true, done);
    }
}
