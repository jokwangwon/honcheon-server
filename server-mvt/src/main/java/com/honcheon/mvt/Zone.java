package com.honcheon.mvt;

import org.bukkit.Location;

/**
 * 이름 붙은 구역 — 입장 시 타이틀 알림의 단위 (마을 전체·건물·장터).
 * 조성기가 만들고 zones.yml 이 기억한다. 중첩 시 부피가 작은 쪽이 이긴다 (건물 > 마을).
 */
record Zone(String name, String subtitle, String world,
            int x1, int y1, int z1, int x2, int y2, int z2) {

    boolean contains(Location at) {
        return at.getWorld() != null && at.getWorld().getName().equals(world)
                && at.getBlockX() >= x1 && at.getBlockX() <= x2
                && at.getBlockY() >= y1 && at.getBlockY() <= y2
                && at.getBlockZ() >= z1 && at.getBlockZ() <= z2;
    }

    long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }
}
