#!/usr/bin/env python3
"""클라이언트 모드팩 굽기 — config/modpack.yml(정본) → run/modpack/혼천-<v>.mrpack

Modrinth API 에서 핀된 version_number 의 파일(주소·sha1·sha512·크기)을 받아
modrinth.index.json 을 짓는다. 파일 실물은 안 담는다 — mrpack 은 참조 형식이라
설치기(Modrinth App·Prism)가 CDN 에서 받는다.

★ 눈: 핀이 Modrinth 에 없으면 그 자리에서 죽는다 (조용한 누락 금지 — 없는 모드를
   담은 척한 팩은 접속 안 되는 클라를 만든다).
"""
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "run" / "modpack"
API = "https://api.modrinth.com/v2"
UA = {"User-Agent": "honcheon-modpack-builder/1.0"}


def fetch(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)


def pin(slug: str, version: str, folder: str, mc: str = None) -> dict:
    """slug 의 version_number 일치 판을 찾아 mrpack files[] 한 줄을 짓는다.

    ★모드는 fabric+게임버전 필터를 건다 — 같은 version_number 가 로더별로 여러 벌이라
    무필터 첫 일치가 NeoForge/딴 게임버전 jar 를 문 적 있다 (2026-07-23 실측).
    """
    query = ""
    if folder == "mods" and mc:
        import urllib.parse
        query = "?" + urllib.parse.urlencode(
            {"game_versions": f'["{mc}"]', "loaders": '["fabric"]'})
    versions = fetch(f"{API}/project/{slug}/version{query}")
    hit = next((v for v in versions if v["version_number"] == version), None)
    if hit is None:
        near = [v["version_number"] for v in versions[:8]]
        sys.exit(f"❌ {slug}: 핀 '{version}' 이 Modrinth 에 없다 — 근처 판: {near}")
    f = next((x for x in hit["files"] if x.get("primary")), hit["files"][0])
    return {
        "path": f"{folder}/{f['filename']}",
        "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
        "env": {"client": "required", "server": "unsupported"},
        "downloads": [f["url"]],
        "fileSize": f["size"],
    }


def main() -> None:
    cfg = yaml.safe_load((ROOT / "config" / "modpack.yml").read_text(encoding="utf-8"))
    files = []
    for m in cfg.get("mods", []):
        files.append(pin(m["slug"], m["version"], "mods", str(cfg["minecraft"])))
        print(f"  ✔ {m['slug']} {m['version']}")
    for s in cfg.get("shaderpacks", []) or []:
        files.append(pin(s["slug"], s["version"], "shaderpacks"))
        print(f"  ✔ (셰이더) {s['slug']} {s['version']}")
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": cfg["version"],
        "name": cfg["name"],
        "summary": "혼천(渾天) 무협 공유세계 — 필수 클라이언트 모드팩",
        "files": files,
        "dependencies": {
            "minecraft": str(cfg["minecraft"]),
            "fabric-loader": str(cfg["fabric_loader"]),
        },
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"{cfg['name']}-{cfg['minecraft']}-{cfg['version']}.mrpack"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, ensure_ascii=False, indent=1))
    print(f"\n구웠다 — {out} ({out.stat().st_size:,} bytes · 모드 {len(files)}개 참조)")
    print("설치: Modrinth App 또는 Prism Launcher 에서 '파일에서 가져오기' → 이 mrpack")


if __name__ == "__main__":
    main()
