package com.honcheon.mvt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 검기 점 템플릿 — 디자이너 컨셉 시트의 그림을 그대로 점으로 옮긴 것.
 *
 * <p>★ 왜 (2026-07-22 사용자: "시트처럼 완벽히 그대로 될때까지"): 파라미터(호·폭·테이퍼)로
 * 시트를 근사하는 길은 다듬어도 "다르다"가 나왔다. 그래서 근사를 버리고 시트의 초승달
 * 픽셀 표본(정규화 좌표 + 등록부 잉크 + 점 크기)을 통째로 싣는다 — 그림이 곧 배치도다.
 * 생성기는 {@code tools/sheet_to_template.py}, 산출물은 {@code config/kigi_template_<이름>.json}.
 *
 * <p>점은 y(그림 위→아래) 오름차순으로 정렬해 둔다 — 스윕 공개가 그 순서를 그대로 쓴다
 * (시트의 베기 방향 화살표가 위→아래다).
 */
final class QiTemplate {

    record Pt(double x, double y, String ink, float size) { }

    final String name;
    /** 세로/가로 비 — bbox 정규화 좌표를 실공간으로 되돌릴 때 가로 반폭 = halfH / aspect. */
    final double aspect;
    final List<Pt> pts;
    private final long mtime;

    private static final Map<String, QiTemplate> CACHE = new ConcurrentHashMap<>();

    private QiTemplate(String name, double aspect, List<Pt> pts, long mtime) {
        this.name = name;
        this.aspect = aspect;
        this.pts = pts;
        this.mtime = mtime;
    }

    /**
     * 적재(+캐시). 파일 mtime 이 바뀌면 다시 읽는다 — 「혼천 모션 재적재」로 서버를 안 내리고
     * 템플릿을 갈아끼우기 위해서다 (config 계열과 같은 계약).
     *
     * @return 파일이 없거나 못 읽으면 null (호출자가 밴드로 후퇴한다 — 조용히 죽지 않게 로그는 호출자 몫)
     */
    static QiTemplate get(File cfgDir, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        File f = new File(cfgDir, "kigi_template_" + name + ".json");
        if (!f.isFile()) {
            return null;
        }
        QiTemplate cached = CACHE.get(f.getAbsolutePath());
        if (cached != null && cached.mtime == f.lastModified()) {
            return cached;
        }
        try {
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            double aspect = root.has("aspect_h_over_w")
                    ? root.get("aspect_h_over_w").getAsDouble() : 1.0;
            List<Pt> pts = new ArrayList<>();
            JsonArray arr = root.getAsJsonArray("points");
            for (JsonElement e : arr) {
                JsonArray p = e.getAsJsonArray();
                pts.add(new Pt(p.get(0).getAsDouble(), p.get(1).getAsDouble(),
                        p.get(2).getAsString(), p.get(3).getAsFloat()));
            }
            pts.sort(Comparator.comparingDouble(Pt::y));
            QiTemplate t = new QiTemplate(name, aspect, List.copyOf(pts), f.lastModified());
            CACHE.put(f.getAbsolutePath(), t);
            return t;
        } catch (Exception ex) {
            return null;
        }
    }
}
