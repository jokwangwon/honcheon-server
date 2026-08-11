package com.honcheon.mvt.forge;

/**
 * <b>한 층</b>이 도면에 대해 답할 수 있는 것 — 평면·처방·깊이·공포.
 *
 * <p>하층은 {@link Blueprint} 자신이 이 노릇을 하고, 상층은 {@link UpperLevel} 이 한다.
 * 조성({@code BlueprintBuilder})은 <b>둘을 구분하지 않는다</b> — 그것이
 * 「상층을 하층과 같은 문법으로」의 실체다.
 */
public interface Level {
    char at(int col, int row);

    int width();

    int depth();

    java.util.List<Blueprint.Course> columnOf(char ch);

    int depthOf(char ch);

    int heightAt(int col, int row);

    char backingChar();

    String bracket();

    int axisCol();

    char bayRole(String role);

    boolean onBodyEdge(int col, int row);

    /**
     * 이 칸의 바깥 방향을 <b>자리가 안다면</b> 그것 — 모르면 {@code null}.
     *
     * <p>상층은 사각 테두리라 <b>어느 변에 앉았는가가 곧 법선</b>이다. 이웃의 높이로
     * 짐작하는 {@code outward} 는 상층에서 못 쓴다: 상층 처방이 4켜라 모든 칸이
     * 「벽」으로 세어져 모서리 근처에서 법선이 뒤집히고, 그러면 공포가 <b>이웃 창을 덮는다</b>
     * (덮임 감사가 12건으로 잡았다 · 2026-08-11).
     * <p>「방향은 자리가 정한다」 — 이 저장소의 계율 그대로다.
     */
    default org.bukkit.block.BlockFace outwardFace(int col, int row) {
        return null;
    }

    /** 이 층의 <b>몸체 상자</b> {@code [x0,z0,x1,z1]} — 모르면 {@code null} */
    default int[] bodyBox() {
        return null;
    }

    /**
     * <b>다포</b>인가 — 기둥 위(주상포)뿐 아니라 <b>기둥 사이</b>에도 포(간포)를 두는가.
     *
     * <p>주심포는 기둥 위에만, 다포는 창방 위 기둥 사이에도 포가 놓인다. 다포는 하중 분산이
     * 좋아 <b>건물의 대형화</b>에 적합하고 장엄한 팔작지붕에 쓰인다 — 주불전의 격식이다.
     * 우리 본전은 {@code rank: principal · bracket: elaborate} 로 다포를 <b>의도</b>하면서
     * 실제로는 기둥 위에만 얹어 주심포계로 서 있었다 (2026-08-11 실물 대조에서 드러남).
     */
    default boolean intercolumnar() {
        return false;
    }

    default boolean isPost(char ch) {
        return Blueprint.shaftIndex(columnOf(ch)) >= 0;
    }
}
