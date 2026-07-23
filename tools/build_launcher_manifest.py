#!/usr/bin/env python3
"""런처 매니페스트 굽기 — config/modpack.yml(정본) → run/modpack/launcher_manifest.json

혼천 런처(Electron)가 런타임에 릴리스에서 이 파일을 받아 읽는다. 핀·URL 은 여기서만 온다
(build_modpack.py 와 같은 정본 — 두 벌 금지). 형식: main.js 의 syncMods 가 기대하는 것.
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
API = "https://api.modrinth.com/v2"
UA = {"User-Agent": "honcheon-launcher-manifest/1.0"}


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)


def file_of(slug, version, mc=None):
    q = ""
    if mc:
        q = "?" + urllib.parse.urlencode({"game_versions": f'["{mc}"]', "loaders": '["fabric"]'})
    versions = fetch(f"{API}/project/{slug}/version{q}")
    hit = next((v for v in versions if v["version_number"] == version), None)
    if hit is None:
        sys.exit(f"❌ {slug}: 핀 '{version}' 없음")
    f = next((x for x in hit["files"] if x.get("primary")), hit["files"][0])
    return {"file": f["filename"], "url": f["url"]}


def main():
    cfg = yaml.safe_load((ROOT / "config" / "modpack.yml").read_text(encoding="utf-8"))
    mc = str(cfg["minecraft"])
    mods = [file_of(m["slug"], m["version"], mc) for m in cfg.get("mods", [])]
    shaders = [file_of(s["slug"], s["version"]) for s in cfg.get("shaderpacks", []) or []]
    manifest = {
        "version": cfg["version"],
        "minecraft": mc,
        "mmc": {"formatVersion": 1, "components": [
            {"important": True, "uid": "net.minecraft", "version": mc},
            {"uid": "net.fabricmc.fabric-loader", "version": str(cfg["fabric_loader"])}]},
        "mods": mods,
        "shaders": shaders,
    }
    out = ROOT / "run" / "modpack" / "launcher_manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"구웠다 — {out} (모드 {len(mods)} · 셰이더 {len(shaders)})")


if __name__ == "__main__":
    main()
