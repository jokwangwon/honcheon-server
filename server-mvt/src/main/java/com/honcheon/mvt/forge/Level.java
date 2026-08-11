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

    default boolean isPost(char ch) {
        return Blueprint.shaftIndex(columnOf(ch)) >= 0;
    }
}
