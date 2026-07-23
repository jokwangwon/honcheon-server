#!/usr/bin/env python3
"""원클릭 설치기 굽기 — config/modpack.yml(정본) → run/modpack/{혼천설치.bat, honcheon_setup.ps1}

.bat(더블클릭 입구 · ASCII 안전) 이 GitHub 릴리스의 .ps1(본체 · UTF-8 BOM 한국어)을 받아
실행한다. ps1 은: Prism 포터블 + Java 자동 + 인스턴스 + 모드·셰이더 전부 + 바탕화면 아이콘.

★ 핀은 전부 config/modpack.yml 에서 온다 (build_modpack.py 와 같은 정본 — 두 벌 금지).
배포: bash scripts/publish_pack.sh 뒤 gh release upload pack <두 파일> --clobber
"""
import json
import sys
import urllib.request
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "modpack"
API = "https://api.modrinth.com/v2"
UA = {"User-Agent": "honcheon-installer-builder/1.0"}
PS1_URL = "https://github.com/jokwangwon/honcheon-pack/releases/download/pack/honcheon_setup.ps1"


def fetch(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)


def file_of(slug: str, version: str) -> dict:
    versions = fetch(f"{API}/project/{slug}/version")
    hit = next((v for v in versions if v["version_number"] == version), None)
    if hit is None:
        sys.exit(f"❌ {slug}: 핀 '{version}' 이 Modrinth 에 없다")
    return next((x for x in hit["files"] if x.get("primary")), hit["files"][0])


def prism_portable() -> str:
    rel = fetch("https://api.github.com/repos/PrismLauncher/PrismLauncher/releases/latest")
    for a in rel["assets"]:
        n = a["name"]
        if "Windows-MSVC-Portable" in n and n.endswith(".zip") and "arm64" not in n.lower():
            return a["browser_download_url"]
    sys.exit("❌ Prism 포터블(Windows MSVC) 자산을 못 찾았다")


def main() -> None:
    cfg = yaml.safe_load((ROOT / "config" / "modpack.yml").read_text(encoding="utf-8"))
    prism_url = prism_portable()
    mod_lines = []
    for m in cfg.get("mods", []):
        f = file_of(m["slug"], m["version"])
        mod_lines.append(f"Get-File '{f['url']}' \"$inst\\.minecraft\\mods\\{f['filename']}\"")
        print(f"  ✔ {m['slug']}")
    shader_file = None
    for s in cfg.get("shaderpacks", []) or []:
        f = file_of(s["slug"], s["version"])
        shader_file = f["filename"]
        mod_lines.append(
            f"Get-File '{f['url']}' \"$inst\\.minecraft\\shaderpacks\\{f['filename']}\"")
        print(f"  ✔ (셰이더) {s['slug']}")
    mmc = json.dumps({"formatVersion": 1, "components": [
        {"important": True, "uid": "net.minecraft", "version": str(cfg["minecraft"])},
        {"uid": "net.fabricmc.fabric-loader", "version": str(cfg["fabric_loader"])}]})

    ps1 = PS1_TEMPLATE.replace("@PRISM_URL@", prism_url) \
        .replace("@MMC_PACK@", mmc.replace("'", "''")) \
        .replace("@DOWNLOADS@", "\n".join(mod_lines)) \
        .replace("@SHADER_FILE@", shader_file or "")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "honcheon_setup.ps1").write_bytes(b"\xef\xbb\xbf" + ps1.encode("utf-8"))

    bat = ("@echo off\r\n"
           "title Honcheon Installer\r\n"
           "echo Honcheon one-click setup - downloading installer...\r\n"
           "powershell -NoProfile -ExecutionPolicy Bypass -Command "
           "\"[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; "
           f"$p=Join-Path $env:TEMP 'honcheon_setup.ps1'; "
           f"Invoke-WebRequest -Uri '{PS1_URL}' -OutFile $p; "
           "& powershell -NoProfile -ExecutionPolicy Bypass -File $p\"\r\n"
           "pause\r\n")
    (OUT / "혼천설치.bat").write_bytes(bat.encode("ascii"))
    print(f"\n구웠다 — {OUT}/혼천설치.bat + honcheon_setup.ps1")
    print("배포: gh release upload pack 두 파일 --clobber (jokwangwon/honcheon-pack)")


PS1_TEMPLATE = r"""
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$host.UI.RawUI.WindowTitle = '혼천 설치기'
Write-Host ''
Write-Host '══════════════════════════════════════════'
Write-Host '  혼천(渾天) — 원클릭 설치'
Write-Host '══════════════════════════════════════════'

function Get-File($url, $to) {
    $name = Split-Path $to -Leaf
    if (Test-Path $to) { Write-Host "  · $name (이미 있음)"; return }
    Write-Host "  ↓ $name"
    Invoke-WebRequest -Uri $url -OutFile $to
}

$root  = Join-Path $env:LOCALAPPDATA 'Honcheon'
$prism = Join-Path $root 'prism'
$inst  = Join-Path $prism 'instances\honcheon'
New-Item -ItemType Directory -Force -Path $prism | Out-Null

# [1/4] Prism Launcher (포터블 — 시스템에 아무것도 설치하지 않는다)
if (!(Test-Path (Join-Path $prism 'prismlauncher.exe'))) {
    Write-Host '[1/4] Prism Launcher 내려받는 중... (약 20MB)'
    $zip = Join-Path $root 'prism.zip'
    Invoke-WebRequest -Uri '@PRISM_URL@' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $prism -Force
    Remove-Item $zip
} else { Write-Host '[1/4] Prism Launcher — 이미 있음' }
New-Item -ItemType File -Force -Path (Join-Path $prism 'portable.txt') | Out-Null
@'
[General]
Language=ko_KR
AutomaticJavaDownload=true
AutomaticJavaSwitch=true
'@ | Set-Content -Path (Join-Path $prism 'prismlauncher.cfg') -Encoding UTF8

# [2/4] 혼천 인스턴스
Write-Host '[2/4] 혼천 인스턴스 만드는 중...'
New-Item -ItemType Directory -Force -Path "$inst\.minecraft\mods","$inst\.minecraft\shaderpacks","$inst\.minecraft\config" | Out-Null
@'
[General]
ConfigVersion=1.2
InstanceType=OneSix
name=혼천
iconKey=default
'@ | Set-Content -Path "$inst\instance.cfg" -Encoding UTF8
Set-Content -Path "$inst\mmc-pack.json" -Value '@MMC_PACK@' -Encoding UTF8

# [3/4] 모드·셰이더
Write-Host '[3/4] 모드·셰이더 내려받는 중...'
@DOWNLOADS@
if ('@SHADER_FILE@' -ne '') {
    "enableShaders=true`nshaderPack=@SHADER_FILE@" |
        Set-Content -Path "$inst\.minecraft\config\iris.properties" -Encoding ASCII
}

# [4/4] 바탕화면 바로가기
Write-Host '[4/4] 바탕화면 바로가기 만드는 중...'
$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) '혼천.lnk'))
$lnk.TargetPath = Join-Path $prism 'prismlauncher.exe'
$lnk.Arguments = '-l honcheon'
$lnk.WorkingDirectory = $prism
$lnk.Save()

Write-Host ''
Write-Host '✅ 완료! 바탕화면의 「혼천」 아이콘으로 시작하세요.'
Write-Host '   · 첫 실행에서 Java 를 자동으로 받고, 마인크래프트 계정 로그인 한 번이 필요합니다.'
Write-Host '   · 셰이더(Complementary Unbound)는 켜진 채 시작됩니다.'
Read-Host '엔터를 누르면 닫힙니다'
"""

if __name__ == "__main__":
    main()
