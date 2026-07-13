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

    /**
     * <b>땅을 읽는다</b> — 이미 빚어진 땅. <b>블록을 하나도 안 건드린다.</b>
     *
     * <p>좌표·기준면·봉우리를 <b>원장에서</b> 가져온다. 세계에서 다시 재면 안 된다 —
     * 우리가 판 강과 건축이 놓은 포석이 {@code naturalGround} 의 답을 바꾸어
     * <b>기준면이 조성 때마다 한 칸씩 내려앉는다</b> (그러면 마당이 재조성마다 깊어진다).
     */
    static Land read(World world, WorldMap.Place place, TerrainLedger.Land l) {
        TerrainForge.SiteSpec spec = TerrainForge.surveyAt(world, place,
                l.cx(), l.groundY(), l.cz(), l.radius(), l.peakX(), l.peakZ(), l.peakY());
        spec = RiverForge.describe(spec, place);   // 수변 방위는 강이 안다 (사분면표가 아니라)
        return new Land(spec, TerrainLedger.cave(l, world.getName()), false, 0);
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
        TerrainForge.SiteSpec spec = TerrainForge.prepare(world, place, cx, cy, cz, radius);
        spec = RiverForge.carve(world, place, spec);

        int done = LandRequest.apply(world, spec);
        if (done > 0) {
            // 요청이 땅을 바꿨다 — 마스크·수변을 다시 잰다 (안 그러면 건축이 개울 위에 집을 짓는다)
            spec = TerrainForge.surveyAt(world, place, spec.cx(), spec.groundY(), spec.cz(),
                    spec.radius(), spec.peakX(), spec.peakZ(), spec.peakY());
            spec = RiverForge.describe(spec, place);
        }

        TerrainForge.CaveKind kind = TerrainForge.caveKind(place);
        TerrainForge.CaveSpec cave = kind == null ? null : TerrainForge.digCave(world, spec, kind);

        TerrainLedger.remember(spec, cave);   // ★ 이 줄이 적히는 순간 이 땅은 굳는다
        return new Land(spec, cave, true, done);
    }
}
