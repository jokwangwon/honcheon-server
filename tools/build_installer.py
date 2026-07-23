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


def file_of(slug: str, version: str, mc: str = None, mod: bool = True) -> dict:
    """★모드는 fabric+게임버전 필터 필수 — 같은 version_number 가 로더별로 여러 벌이라
    무필터 첫 일치가 NeoForge/딴 게임버전 jar 를 문 적 있다 (2026-07-23 실측)."""
    query = ""
    if mod and mc:
        import urllib.parse
        query = "?" + urllib.parse.urlencode(
            {"game_versions": f'["{mc}"]', "loaders": '["fabric"]'})
    versions = fetch(f"{API}/project/{slug}/version{query}")
    hit = next((v for v in versions if v["version_number"] == version), None)
    if hit is None:
        sys.exit(f"❌ {slug}: 핀 '{version}' 이 Modrinth 에 없다 (필터: {query or '없음'})")
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
    mod_names = []
    shader_names = []
    for m in cfg.get("mods", []):
        f = file_of(m["slug"], m["version"], str(cfg["minecraft"]))
        mod_names.append(f["filename"])
        mod_lines.append(f"Get-File '{f['url']}' \"$inst\\.minecraft\\mods\\{f['filename']}\"")
        print(f"  ✔ {m['slug']}")
    shader_file = None
    for s in cfg.get("shaderpacks", []) or []:
        f = file_of(s["slug"], s["version"], mod=False)
        shader_file = f["filename"]
        shader_names.append(f["filename"])
        mod_lines.append(
            f"Get-File '{f['url']}' \"$inst\\.minecraft\\shaderpacks\\{f['filename']}\"")
        print(f"  ✔ (셰이더) {s['slug']}")
    keep_mods = ",".join(f"'{n}'" for n in mod_names)
    keep_shaders = ",".join(f"'{n}'" for n in shader_names) or "''"
    mmc = json.dumps({"formatVersion": 1, "components": [
        {"important": True, "uid": "net.minecraft", "version": str(cfg["minecraft"])},
        {"uid": "net.fabricmc.fabric-loader", "version": str(cfg["fabric_loader"])}]})

    # 전체 걸음 수 = Prism 1 + 내려받을 파일들 + 마무리 1 (진행 막대의 분모)
    total_steps = 4 + 1 + len(mod_lines) + 1
    ps1 = PS1_TEMPLATE.replace("@PRISM_URL@", prism_url) \
        .replace("@MMC_PACK@", mmc.replace("'", "''")) \
        .replace("@DOWNLOADS@", "\n".join(mod_lines)) \
        .replace("@KEEP_MODS@", keep_mods) \
        .replace("@KEEP_SHADERS@", keep_shaders) \
        .replace("@SHADER_FILE@", shader_file or "") \
        .replace("@TOTAL@", str(total_steps))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "honcheon_setup.ps1").write_bytes(b"\xef\xbb\xbf" + ps1.encode("utf-8"))

    bat = ("@echo off\r\n"
           "title Honcheon Installer\r\n"
           "echo Honcheon one-click setup - downloading installer...\r\n"
           "powershell -NoProfile -ExecutionPolicy Bypass -Command "
           "\"[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; "
           f"$p=Join-Path $env:TEMP 'honcheon_setup.ps1'; "
           f"Invoke-WebRequest -Uri '{PS1_URL}' -OutFile $p; "
           "& powershell -NoProfile -ExecutionPolicy Bypass -File $p\"\r\n")
    (OUT / "혼천설치.bat").write_bytes(bat.encode("ascii"))
    print(f"\n구웠다 — {OUT}/혼천설치.bat + honcheon_setup.ps1")
    print("배포: gh release upload pack 두 파일 --clobber (jokwangwon/honcheon-pack)")


PS1_TEMPLATE = r"""
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

# ── 수묵 GUI (WinForms) — 실패하면 콘솔로 물러선다 ──────────────────────────
$gui = $false
try {
    Add-Type -AssemblyName System.Windows.Forms, System.Drawing
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $gui = $true
} catch {}

$INK_BG  = [System.Drawing.Color]::FromArgb(22, 22, 26)     # 먹지
$INK_FG  = [System.Drawing.Color]::FromArgb(232, 228, 218)  # 종이빛
$INK_DIM = [System.Drawing.Color]::FromArgb(120, 118, 110)
$INK_ACC = [System.Drawing.Color]::FromArgb(63, 167, 160)   # 청록 (검기의 색)

if ($gui) {
    $form = New-Object System.Windows.Forms.Form
    $form.Text = '혼천 설치기'
    $form.Size = New-Object System.Drawing.Size(520, 340)
    $form.FormBorderStyle = 'FixedSingle'
    $form.MaximizeBox = $false
    $form.StartPosition = 'CenterScreen'
    $form.BackColor = $INK_BG

    $title = New-Object System.Windows.Forms.Label
    $title.Text = '渾 天'
    $title.Font = New-Object System.Drawing.Font('Malgun Gothic', 34, [System.Drawing.FontStyle]::Bold)
    $title.ForeColor = $INK_FG
    $title.TextAlign = 'MiddleCenter'
    $title.Size = New-Object System.Drawing.Size(500, 70)
    $title.Location = New-Object System.Drawing.Point(2, 28)

    $sub = New-Object System.Windows.Forms.Label
    $sub.Text = '혼천 — 무협 공유세계'
    $sub.Font = New-Object System.Drawing.Font('Malgun Gothic', 11)
    $sub.ForeColor = $INK_DIM
    $sub.TextAlign = 'MiddleCenter'
    $sub.Size = New-Object System.Drawing.Size(500, 24)
    $sub.Location = New-Object System.Drawing.Point(2, 100)

    $barBack = New-Object System.Windows.Forms.Panel
    $barBack.Size = New-Object System.Drawing.Size(420, 3)
    $barBack.Location = New-Object System.Drawing.Point(45, 176)
    $barBack.BackColor = [System.Drawing.Color]::FromArgb(52, 52, 60)

    # 붓 획 — 그림(honcheon_stroke.png)을 진행 폭만큼 늘린다 (자르면 꼬리가 죽는다)
    $barFill = New-Object System.Windows.Forms.PictureBox
    $barFill.Size = New-Object System.Drawing.Size(1, 18)
    $barFill.Location = New-Object System.Drawing.Point(45, 168)
    $barFill.SizeMode = 'StretchImage'
    $barFill.BackColor = [System.Drawing.Color]::Transparent

    # 낙관 「入門」 — 완료의 도장 (숨겨 뒀다가 끝에 찍는다)
    $seal = New-Object System.Windows.Forms.PictureBox
    $seal.Size = New-Object System.Drawing.Size(92, 92)
    $seal.Location = New-Object System.Drawing.Point(398, 96)
    $seal.SizeMode = 'StretchImage'
    $seal.BackColor = [System.Drawing.Color]::Transparent
    $seal.Visible = $false

    $status = New-Object System.Windows.Forms.Label
    $status.Text = '준비하는 중...'
    $status.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $status.ForeColor = $INK_DIM
    $status.TextAlign = 'MiddleCenter'
    $status.Size = New-Object System.Drawing.Size(500, 22)
    $status.Location = New-Object System.Drawing.Point(2, 186)

    $pctLabel = New-Object System.Windows.Forms.Label
    $pctLabel.Text = ''
    $pctLabel.Font = New-Object System.Drawing.Font('Malgun Gothic', 9, [System.Drawing.FontStyle]::Bold)
    $pctLabel.ForeColor = $INK_ACC
    $pctLabel.TextAlign = 'MiddleCenter'
    $pctLabel.Size = New-Object System.Drawing.Size(500, 20)
    $pctLabel.Location = New-Object System.Drawing.Point(2, 148)

    $done = New-Object System.Windows.Forms.Button
    $done.Text = '게임 시작'
    $done.Font = New-Object System.Drawing.Font('Malgun Gothic', 10)
    $done.ForeColor = $INK_FG
    $done.BackColor = [System.Drawing.Color]::FromArgb(40, 40, 46)
    $done.FlatStyle = 'Flat'
    $done.FlatAppearance.BorderColor = $INK_ACC
    $done.Size = New-Object System.Drawing.Size(140, 34)
    $done.Location = New-Object System.Drawing.Point(190, 240)
    $done.Visible = $false
    $done.Add_Click({
        Start-Process (Join-Path $prism 'prismlauncher.exe') -ArgumentList '-l','honcheon' -WorkingDirectory $prism
        $form.Close()
    })

    foreach ($c in @($title, $sub, $pctLabel, $status)) { $c.BackColor = [System.Drawing.Color]::Transparent }
    try { $title.Font = New-Object System.Drawing.Font('Batang', 34, [System.Drawing.FontStyle]::Bold) } catch {}
    $form.Controls.AddRange(@($title, $sub, $pctLabel, $barFill, $barBack, $status, $done, $seal))
    $barFill.BringToFront(); $seal.BringToFront()
    $form.Show()
    $form.Activate()
    # 콘솔 창은 뒤로 숨긴다 — 창이 얼굴이다
    try {
        Add-Type -Name Win -Namespace Native -MemberDefinition '[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow(); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);'
        [Native.Win]::ShowWindow([Native.Win]::GetConsoleWindow(), 0) | Out-Null
    } catch {}
}
if (-not $gui) {
    # exe 가 파워셸을 숨김으로 띄운다 — GUI 폴백(콘솔)이면 콘솔을 도로 보인다 (보이지 않는 멈춤 금지)
    try {
        Add-Type -Name Win2 -Namespace Native -MemberDefinition '[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow(); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);'
        [Native.Win2]::ShowWindow([Native.Win2]::GetConsoleWindow(), 5) | Out-Null
    } catch {}
}

$script:stepDone = 0
$TOTAL_STEPS = @TOTAL@

function Set-Face($text, $filePct) {
    $overall = [int](100 * ($script:stepDone + $filePct / 100.0) / $TOTAL_STEPS)
    if ($overall -gt 100) { $overall = 100 }
    if ($gui) {
        $status.Text = $text
        $pctLabel.Text = "$overall%"
        $barFill.Width = [Math]::Max(1, [int](420 * $overall / 100))
        [System.Windows.Forms.Application]::DoEvents()
    } else {
        Write-Host ("`r  [{0,3}%] {1}" -f $overall, $text.PadRight(60)) -NoNewline
    }
}

function Get-File($url, $to) {
    $name = Split-Path $to -Leaf
    if (Test-Path $to) { $script:stepDone++; Set-Face "$name (있음)" 0; return }
    $req = [Net.HttpWebRequest]::Create($url)
    $req.UserAgent = 'honcheon-installer'
    $res = $req.GetResponse()
    $total = $res.ContentLength
    $in = $res.GetResponseStream()
    $out = [IO.File]::Create($to)
    try {
        $buf = New-Object byte[] 131072
        $got = 0L
        while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
            $out.Write($buf, 0, $n)
            $got += $n
            if ($total -gt 0) { Set-Face "내려받는 중 — $name" ([int](100 * $got / $total)) }
        }
    } finally { $out.Close(); $in.Close(); $res.Close() }
    $script:stepDone++
    Set-Face "받음 — $name" 0
}

$root  = Join-Path $env:LOCALAPPDATA 'Honcheon'
$prism = Join-Path $root 'prism'
$inst  = Join-Path $prism 'instances\honcheon'
$art   = Join-Path $root 'art'
New-Item -ItemType Directory -Force -Path $prism, $art | Out-Null

# [0] 화폭 — 창의 옷부터 입힌다 (수묵 산수 · 붓 획 · 낙관)
$AB = 'https://github.com/jokwangwon/honcheon-pack/releases/download/pack'
# 그림은 항상 최신을 받는다 — 화가 그림으로 갈리면 기존 설치자에게도 가야 한다 (작은 파일들)
Remove-Item (Join-Path $art '*') -ErrorAction SilentlyContinue
Get-File "$AB/honcheon_bg.png" (Join-Path $art 'honcheon_bg.png')
Get-File "$AB/honcheon_stroke.png" (Join-Path $art 'honcheon_stroke.png')
Get-File "$AB/honcheon_seal.png" (Join-Path $art 'honcheon_seal.png')
Get-File "$AB/honcheon.ico" (Join-Path $art 'honcheon.ico')
if ($gui) {
    try {
        $form.BackgroundImage = [System.Drawing.Image]::FromFile((Join-Path $art 'honcheon_bg.png'))
        $form.BackgroundImageLayout = 'Stretch'
        $barFill.Image = [System.Drawing.Image]::FromFile((Join-Path $art 'honcheon_stroke.png'))
        $seal.Image = [System.Drawing.Image]::FromFile((Join-Path $art 'honcheon_seal.png'))
        $title.TextAlign = 'TopLeft'
        $title.Location = New-Object System.Drawing.Point(30, 22)
        $title.Size = New-Object System.Drawing.Size(240, 70)
        $sub.TextAlign = 'TopLeft'
        $sub.Location = New-Object System.Drawing.Point(34, 92)
        $sub.Size = New-Object System.Drawing.Size(300, 24)
        [System.Windows.Forms.Application]::DoEvents()
    } catch {}
}

# [1] Prism Launcher (포터블 — 시스템에 아무것도 설치하지 않는다)
if (!(Test-Path (Join-Path $prism 'prismlauncher.exe'))) {
    $zip = Join-Path $root 'prism.zip'
    Get-File '@PRISM_URL@' $zip
    Set-Face 'Prism Launcher 푸는 중...' 50
    Expand-Archive -Path $zip -DestinationPath $prism -Force
    Remove-Item $zip
} else { $script:stepDone++; Set-Face 'Prism Launcher — 이미 있음' 0 }
New-Item -ItemType File -Force -Path (Join-Path $prism 'portable.txt') | Out-Null
@'
[General]
Language=ko_KR
AutomaticJavaDownload=true
AutomaticJavaSwitch=true
'@ | Set-Content -Path (Join-Path $prism 'prismlauncher.cfg') -Encoding UTF8

# [2] 혼천 인스턴스
New-Item -ItemType Directory -Force -Path "$inst\.minecraft\mods","$inst\.minecraft\shaderpacks","$inst\.minecraft\config" | Out-Null
@'
[General]
ConfigVersion=1.2
InstanceType=OneSix
name=혼천
iconKey=default
'@ | Set-Content -Path "$inst\instance.cfg" -Encoding UTF8
Set-Content -Path "$inst\mmc-pack.json" -Value '@MMC_PACK@' -Encoding UTF8

# [3] 모드·셰이더 — ★예의 있는 동기화 (2026-07-23 사용자 지적):
#   지우는 것은 **우리가 전에 설치한 것 중 핀에서 빠진 것**뿐이다 (장부 manifest 대조).
#   사용자가 제 손으로 넣은 모드는 건드리지 않는다 — 남의 짐을 지우는 것은 손실이다.
$keepMods = @(@KEEP_MODS@)
$keepShaders = @(@KEEP_SHADERS@)
$manifest = Join-Path $inst 'honcheon_manifest.txt'
$ours = @()
if (Test-Path $manifest) { $ours = @(Get-Content $manifest -ErrorAction SilentlyContinue) }
Get-ChildItem "$inst\.minecraft\mods" -Filter *.jar -ErrorAction SilentlyContinue |
    Where-Object { ($ours -contains $_.Name) -and ($keepMods -notcontains $_.Name) } |
    Remove-Item
Get-ChildItem "$inst\.minecraft\shaderpacks" -Filter *.zip -ErrorAction SilentlyContinue |
    Where-Object { ($ours -contains $_.Name) -and ($keepShaders -notcontains $_.Name) } |
    Remove-Item
Set-Content -Path $manifest -Value ($keepMods + $keepShaders) -Encoding UTF8
@DOWNLOADS@
if ('@SHADER_FILE@' -ne '') {
    "enableShaders=true`nshaderPack=@SHADER_FILE@" |
        Set-Content -Path "$inst\.minecraft\config\iris.properties" -Encoding ASCII
}

# [4] 바탕화면 바로가기
Set-Face '바탕화면 바로가기 만드는 중...' 50
$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) '혼천.lnk'))
$lnk.TargetPath = Join-Path $prism 'prismlauncher.exe'
$lnk.Arguments = '-l honcheon'
$lnk.WorkingDirectory = $prism
$lnk.IconLocation = (Join-Path $art 'honcheon.ico') + ',0'
$lnk.Save()
$script:stepDone = $TOTAL_STEPS
Set-Face '설치 완료 — 바탕화면의 「혼천」 아이콘으로 시작하세요' 0

# ★ 설치가 곧 실행이다 (사용자: "다른 걸 누를 필요 없게") — 낙관 찍고 바로 게임을 띄운다
$launch = Join-Path $prism 'prismlauncher.exe'
if ($gui) {
    if ($seal.Image) {
        $seal.Visible = $true
        foreach ($z in 130, 112, 100, 92) {
            $seal.Size = New-Object System.Drawing.Size($z, $z)
            $seal.Location = New-Object System.Drawing.Point((444 - [int]($z/2)), (142 - [int]($z/2)))
            [System.Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 55
        }
    }
    $status.ForeColor = $INK_ACC
    $status.Text = '혼천을 시작합니다...'
    $sub.Text = '첫 실행: Java 자동 설치 · 계정 로그인 한 번'
    $sub.ForeColor = $INK_FG
    [System.Windows.Forms.Application]::DoEvents()
    Start-Sleep -Milliseconds 1200
    try {
        Start-Process $launch -ArgumentList '-l','honcheon' -WorkingDirectory $prism
        Start-Sleep -Milliseconds 800
        $form.Close()
    } catch {
        # 자동 시작이 막혔다 — 버튼이 예비다 (조용히 닫지 않는다)
        $status.Text = '자동 시작 실패 — 아래 버튼으로 시작하세요'
        $done.Visible = $true
        while ($form.Visible) { [System.Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 40 }
    }
} else {
    Write-Host ''
    Write-Host '  ✅ 설치 완료 — 혼천을 시작합니다.' -ForegroundColor Green
    Start-Process $launch -ArgumentList '-l','honcheon' -WorkingDirectory $prism
}
"""

if __name__ == "__main__":
    main()
