#!/usr/bin/env python3
"""혼천 소리 대장간 — 결정론 합성 .ogg (T4 · B-035 보류 해제 시도).

【무엇인가】 ffmpeg(/usr/bin/ffmpeg · libvorbis)로 .ogg 를 굽는 **결정론** 합성기.
  파형은 순수 파이썬 수학으로 만든다 (난수 = 자체 LCG, 씨앗 상수 SEED — random 모듈 금지:
  파이썬 버전 간 재현성을 남에게 맡기지 않는다). ffmpeg 는 **인코더로만** 쓴다.

【결정론의 조건 — 셋 다 필요하다】
  ① 파형: 같은 코드 → 같은 float 열 (LCG·math 뿐, 시계·경로·환경 무관)
  ② WAV: 우리가 struct 로 직접 쓴다 (타임스탬프 청크 없음)
  ③ ffmpeg: -map_metadata -1 -fflags +bitexact -flags:a +bitexact
     → OggS 시리얼·vorbis 코멘트(encoder 문자열·날짜)가 고정된다.
  ★ 같은 ffmpeg 빌드 안에서의 결정론이다 (libvorbis 부호화기가 바뀌면 바이트가 바뀐다).
    이 저장소의 기준: ffmpeg 6.1.1-3ubuntu5 (/usr/bin/ffmpeg). --verify 가 2회 합성 해시를 대조한다.

【음량 규율】 채널마다 피크 정규화 PEAK=0.70 (≈ -3.1 dBFS) — 클리핑 불가능(정규화가 상한),
  과대음량 금지(바닐라 사건음과 비슷한 체감 크기). 끝 10ms 페이드아웃 — 마침 클릭 방지.

【서장 4채널 × 2방향】 (seojang_presentation.md §4.2 — 성격 확정은 **사용자 결정 회차**)
  방향 A 국악기 흉내: open=가야금 뜯기 / choose=박(拍) 마른 딱 / result=편경 / debut=북 고동+대금 숨
  방향 B 절제된 추상: open=종이 스침    / choose=마른 딸깍     / result=맑은 종 / debut=심장 고동+저공명
  상한(등록부 max_s): open 1.2s · choose 0.8s · result 1.2s · debut 1.8s — 코드가 강제한다.

【청구서 30종 — 국악 기조 (사용자 결정: A 국악 방향 확정 · 2026-07-16)】
  등록부 bill(resourcepack_design.yml sound_channels.bill)의 7묶음 30종을 같은 규율로 합성한다.
  타격음은 물리감 우선 — 바람가름·금속 울림 위에 국악 타악(장구 채편·꽹과리 스침)의 질감만 얹는다.
  세계 4종은 **이음매 무봉합 루프** (꼬리를 머리에 등전력 크로스페이드로 접는다 — loopify).
  경로: resourcepack/assets/honcheon/sounds/<묶음>/<이름>.ogg → 이벤트 honcheon:<묶음>.<이름>
  (respack/sounds.py 의 청구서 대역 자동 등재가 받는다. 파일명은 리소스 로케이션 규칙상 로마자 —
   [a-z0-9_./-] 만 허용된다. 한글 대응은 BILL 표의 둘째 칸이 정본.)
  ★ 개별 소리의 품질·성격은 **잠정** — 사용자 인게임 귀 확정 대기 (방향만 확정됐다).

【쓰는 법】
  python3 tools/sound_forge.py --audition <outdir>   # 서장 8표본 (채널×방향) — 사용자 청취용
  python3 tools/sound_forge.py --install <A|B>       # 서장: 고른 방향 4장을 팩 트리에 설치
                                                     #   resourcepack/assets/honcheon/sounds/seojang/
                                                     #   (빌더는 트리를 비우지 않는다 — 공존 확인 완료.
                                                     #    설치 후 build_resourcepack.py 가 재지향→실물 전환)
  python3 tools/sound_forge.py --forge-bill          # 청구서 30종을 팩 트리에 설치 (국악 기조)
  python3 tools/sound_forge.py --verify              # 결정론 증명 — 2회 독립 합성 해시 대조 (38종 전부)
"""
import argparse
import hashlib
import math
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK_SEOJANG = ROOT / "resourcepack" / "assets" / "honcheon" / "sounds" / "seojang"
FFMPEG = "/usr/bin/ffmpeg"
SR = 44100          # 모노 44.1kHz — 마인크래프트 위치 음원은 모노여야 감쇠가 산다
SEED = 0x48C40001   # 혼천(渾天) 씨앗 — 상수. 바꾸면 모든 소리가 바뀐다
PEAK = 0.70         # 피크 정규화 목표 (≈ -3.1 dBFS) — 클리핑·과대음량 방지
MAX_S = {"open": 1.2, "choose": 0.8, "result": 1.2, "debut": 1.8}   # 등록부 sound_channels.seojang


# ─── 결정론 난수 — 자체 LCG (Knuth MMIX 계수) ──────────────────────────────
def _lcg(salt):
    x = (SEED * 6364136223846793005 + salt * 1442695040888963407 + 1) & 0xFFFFFFFFFFFFFFFF
    while True:
        x = (x * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        yield ((x >> 33) & 0x7FFFFFFF) / 0x40000000 - 1.0   # [-1, 1)


# ─── 파형 기본기 ─────────────────────────────────────────────────────────────
def buf(dur):
    return [0.0] * int(dur * SR + 0.5)


def add(dst, src, at=0.0, gain=1.0):
    """src 를 dst 의 at 초 지점에 겹쳐 얹는다 (범위 밖은 조용히 버린다)."""
    i0 = int(at * SR)
    for i, v in enumerate(src):
        j = i0 + i
        if 0 <= j < len(dst):
            dst[j] += v * gain
    return dst


def partial(dur, freq, tau, gain=1.0, bend=0.0, bend_tau=0.03, attack=0.002):
    """감쇠 사인 배음 하나 — bend 는 타격 순간의 음높이 처짐 (현·막의 물리)."""
    n = int(dur * SR)
    out = [0.0] * n
    ph = 0.0
    for i in range(n):
        t = i / SR
        f = freq * (1.0 + bend * math.exp(-t / bend_tau))
        ph += 2.0 * math.pi * f / SR
        env = math.exp(-t / tau) * (min(1.0, t / attack) if attack > 0 else 1.0)
        out[i] = math.sin(ph) * env * gain
    return out


def noise_burst(dur, salt, lp=6000.0, hp=200.0, tau=0.05, attack=0.001, gain=1.0):
    """감쇠 잡음 — 1극 저역(lp)·고역(hp) 필터로 대역을 자른다 (타격·마찰의 살)."""
    n = int(dur * SR)
    g = _lcg(salt)
    a_lp = math.exp(-2.0 * math.pi * lp / SR)
    a_hp = math.exp(-2.0 * math.pi * hp / SR)
    lp_s = hp_s = 0.0
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        lp_s = (1.0 - a_lp) * next(g) + a_lp * lp_s
        hp_s = (1.0 - a_hp) * lp_s + a_hp * hp_s
        env = math.exp(-t / tau) * (min(1.0, t / attack) if attack > 0 else 1.0)
        out[i] = (lp_s - hp_s) * env * gain
    return out


def noise_swell(dur, salt, lp, hp, gain=1.0, skew=0.5):
    """부풀었다 잦아드는 잡음 — skew 가 마루의 위치 (0=앞쪽, 1=뒤쪽). 종이·숨의 살."""
    n = int(dur * SR)
    g = _lcg(salt)
    a_lp = math.exp(-2.0 * math.pi * lp / SR)
    a_hp = math.exp(-2.0 * math.pi * hp / SR)
    lp_s = hp_s = 0.0
    out = [0.0] * n
    for i in range(n):
        p = i / max(1, n - 1)
        if p < skew:
            env = math.sin(0.5 * math.pi * p / skew) ** 2
        else:
            env = math.cos(0.5 * math.pi * (p - skew) / (1.0 - skew)) ** 2
        lp_s = (1.0 - a_lp) * next(g) + a_lp * lp_s
        hp_s = (1.0 - a_hp) * lp_s + a_hp * hp_s
        out[i] = (lp_s - hp_s) * env * gain
    return out


def reso_whoosh(dur, salt, f0, f1, bw=180.0, skew=0.4, gain=1.0):
    """바람가름 — 2극 공진기를 지나는 잡음. 중심이 f0→f1 로 지수 보간으로 쓸린다 (참격의 뼈)."""
    n = int(dur * SR)
    g = _lcg(salt)
    r = math.exp(-math.pi * bw / SR)
    y1 = y2 = 0.0
    out = [0.0] * n
    for i in range(n):
        p = i / max(1, n - 1)
        w = 2.0 * math.pi * (f0 * (f1 / f0) ** p) / SR
        if p < skew:
            env = math.sin(0.5 * math.pi * p / skew) ** 2
        else:
            env = math.cos(0.5 * math.pi * (p - skew) / (1.0 - skew)) ** 2
        y = 2.0 * r * math.cos(w) * y1 - r * r * y2 + next(g) * (1.0 - r)
        y2, y1 = y1, y
        out[i] = y * env * gain
    return out


def lfo_noise(dur, salt, lp, hp, rate=0.31, rate2=0.47, depth=0.45, gain=1.0):
    """느리게 숨쉬는 잡음 — 앰비언스의 바탕. 두 사인 LFO 가 진폭을 흔든다 (주기 비정수 — 루프는 loopify 가 접는다)."""
    n = int(dur * SR)
    g = _lcg(salt)
    a_lp = math.exp(-2.0 * math.pi * lp / SR)
    a_hp = math.exp(-2.0 * math.pi * hp / SR)
    lp_s = hp_s = 0.0
    ph = (salt % 7) * 0.9
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        lp_s = (1.0 - a_lp) * next(g) + a_lp * lp_s
        hp_s = (1.0 - a_hp) * lp_s + a_hp * hp_s
        m = 1.0 - depth * (0.5 + 0.25 * math.sin(2 * math.pi * rate * t + ph)
                           + 0.25 * math.sin(2 * math.pi * rate2 * t + ph * 1.7))
        out[i] = (lp_s - hp_s) * m * gain
    return out


def loopify(samples, fade_s=0.30):
    """이음매 무봉합 — 꼬리 fade 구간을 머리에 등전력 크로스페이드로 접는다 (세계 루프용).
    합성은 L+fade 초를 만들고, 여기서 L 초로 접는다 — 끝 표본이 첫 표본으로 매끄럽게 이어진다."""
    f = int(fade_s * SR)
    body, tail = samples[:-f], samples[-f:]
    out = list(body)
    for i in range(f):
        t = i / max(1, f - 1)
        out[i] = out[i] * math.sin(0.5 * math.pi * t) + tail[i] * math.cos(0.5 * math.pi * t)
    return out


def rnds(salt, n):
    """결정론 난수 n개 [0,1) — 사건 시각·음높이 흩기용."""
    g = _lcg(salt)
    return [(next(g) + 1.0) * 0.5 for _ in range(n)]


def normalize(samples, peak=PEAK, fade_out=True):
    """피크 정규화 + 끝 10ms 페이드아웃 — 클리핑 불가능·마침 클릭 방지 (음량 규율의 집).
    루프는 fade_out=False — loopify 가 이음매를 이미 접었다 (페이드가 도리어 이음매를 만든다)."""
    m = max(abs(s) for s in samples) or 1.0
    k = peak / m
    out = [s * k for s in samples]
    if fade_out:
        n = min(len(out), int(0.010 * SR))
        for i in range(n):
            out[len(out) - n + i] *= (n - 1 - i) / max(1, n - 1)
    return out


# ─── 악기 — 방향 A: 국악기 흉내 ──────────────────────────────────────────────
def pluck(freq, dur, salt):
    """가야금 뜯기 — 배음 가산(고차일수록 빨리 죽는다) + 뜯는 순간 마찰 + 미세 처짐."""
    out = buf(dur)
    for h in range(1, 9):
        add(out, partial(dur, freq * h * (1.0 + 0.0004 * h * h), 0.30 / (h ** 0.9),
                         gain=1.0 / (h ** 1.25), bend=0.012, bend_tau=0.04))
    add(out, noise_burst(0.05, salt, lp=5000, hp=800, tau=0.012), 0.0, 0.5)
    return out


def buk(dur, salt, f0=96.0):
    """북 — 낮은 막의 고동: 음높이가 처지는 저음 + 몸통 잡음."""
    out = buf(dur)
    add(out, partial(dur, f0, 0.28, bend=0.85, bend_tau=0.035, attack=0.001), 0.0, 1.0)
    add(out, partial(dur, f0 * 1.6, 0.12, bend=0.6, bend_tau=0.02), 0.0, 0.35)
    add(out, noise_burst(0.08, salt, lp=900, hp=60, tau=0.03), 0.0, 0.6)
    return out


# ─── 서장 4채널 × 2방향 — 각 함수가 정규화 전의 날 파형을 돌려준다 ───────────
def r_open_A():
    """open·A 국악 — 가야금 두 번 뜯기 (D4→G4): 책장을 여는 손."""
    out = buf(1.15)
    add(out, pluck(293.66, 0.90, salt=0x11), 0.00, 0.9)
    add(out, pluck(392.00, 0.78, salt=0x12), 0.32, 0.8)
    return out


def r_choose_A():
    """choose·A 국악 — 박(拍): 마른 나무가 한 번 딱 (입력 접수의 낙관)."""
    out = buf(0.38)
    add(out, noise_burst(0.06, 0x21, lp=6500, hp=1200, tau=0.010), 0.0, 1.0)
    add(out, partial(0.20, 210.0, 0.045, bend=0.35, bend_tau=0.010), 0.0, 0.8)
    add(out, partial(0.12, 1250.0, 0.020), 0.0, 0.25)
    return out


def r_result_A():
    """result·A 국악 — 편경(編磬) 한 타: 비조화 배음의 돌 종 (등급 pitch 변주는 서버 몫)."""
    out = buf(1.15)
    for r, g, tau in ((1.0, 1.0, 0.50), (2.756, 0.55, 0.28),
                      (5.404, 0.28, 0.15), (8.933, 0.13, 0.08)):
        add(out, partial(1.15, 523.25 * r, tau, gain=g, attack=0.001))
    add(out, noise_burst(0.03, 0x31, lp=8000, hp=2000, tau=0.006), 0.0, 0.35)
    return out


def r_debut_A():
    """debut·A 국악 — 북 두 타 + 대금의 숨: 강호로 나서는 고동."""
    out = buf(1.75)
    add(out, buk(0.60, salt=0x41), 0.00, 1.0)
    add(out, buk(0.55, salt=0x42, f0=88.0), 0.42, 0.8)
    add(out, noise_swell(1.00, 0x43, lp=2600, hp=900, skew=0.45), 0.62, 0.30)   # 숨
    add(out, partial(1.00, 587.33, 0.45, attack=0.25), 0.66, 0.16)              # 숨 속의 관음
    return out


def r_open_B():
    """open·B 절제 — 종이 한 장 스치는 소리 + 낮은 마침 점."""
    out = buf(1.00)
    add(out, noise_swell(0.75, 0x51, lp=4200, hp=700, skew=0.35), 0.00, 0.9)
    add(out, partial(0.35, 220.0, 0.10, attack=0.004), 0.62, 0.5)
    return out


def r_choose_B():
    """choose·B 절제 — 마른 딸깍: 짧은 잡음 + 목질 공명 한 점."""
    out = buf(0.26)
    add(out, noise_burst(0.020, 0x61, lp=9000, hp=2500, tau=0.004), 0.0, 0.9)
    add(out, partial(0.15, 1046.5, 0.030, attack=0.001), 0.004, 0.4)
    add(out, partial(0.10, 196.0, 0.030), 0.0, 0.5)
    return out


def r_result_B():
    """result·B 절제 — 맑은 종: 조화 배음, 편경보다 순한 울림."""
    out = buf(1.15)
    for r, g, tau in ((1.0, 1.0, 0.55), (2.0, 0.45, 0.32),
                      (3.01, 0.26, 0.20), (4.16, 0.12, 0.11)):
        add(out, partial(1.15, 659.26 * r, tau, gain=g, attack=0.001))
    add(out, noise_burst(0.02, 0x71, lp=9000, hp=3000, tau=0.005), 0.0, 0.2)
    return out


def r_debut_B():
    """debut·B 절제 — 심장 고동 두 번 + 낮은 공명이 멀어진다."""
    out = buf(1.75)
    thump = lambda: partial(0.32, 62.0, 0.095, bend=0.9, bend_tau=0.020, attack=0.002)
    add(out, thump(), 0.00, 1.0)
    add(out, thump(), 0.33, 0.85)
    for r, g in ((1.0, 0.55), (1.5, 0.22), (2.02, 0.12)):
        add(out, partial(1.10, 98.0 * r, 0.40, attack=0.02, gain=g), 0.58)
    return out


DIRECTIONS = {
    "A": {"이름": "국악", "open": (r_open_A, "가야금"), "choose": (r_choose_A, "박딱"),
          "result": (r_result_A, "편경"), "debut": (r_debut_A, "북과숨")},
    "B": {"이름": "절제", "open": (r_open_B, "종이스침"), "choose": (r_choose_B, "딸깍"),
          "result": (r_result_B, "맑은종"), "debut": (r_debut_B, "고동")},
}
CHANNELS = ("open", "choose", "result", "debut")


# ═══ 청구서 30종 — 국악 기조 (등록부 bill · 사용자 결정: A 방향) ═══════════════
# 타격음의 철칙: 물리감이 국악보다 앞선다 — 바람가름(reso_whoosh)·금속 울림이 뼈,
# 장구 채편의 마른 딱·꽹과리 스침의 쇳가루는 살로만 얹는다.

# ── 병기_참격 5 — entity.player.attack.* 의 대체 (75용처 1순위) ──
def r_geom_cham():
    """검_참 — 바람가름 위에 얇은 쇳울림 한 획 (꽹과리 스침의 질감)."""
    out = buf(0.42)
    add(out, reso_whoosh(0.28, 0x101, 2600, 900, bw=260, skew=0.25), 0.0, 1.0)
    add(out, partial(0.20, 3200.0, 0.055, attack=0.001), 0.08, 0.35)
    add(out, noise_burst(0.06, 0x102, lp=9000, hp=4000, tau=0.018), 0.08, 0.30)
    return out


def r_do_cham():
    """도_참 — 검보다 무겁고 낮은 바람 + 몸이 있는 쇳소리 (베는 무게)."""
    out = buf(0.48)
    add(out, reso_whoosh(0.32, 0x111, 1800, 600, bw=220, skew=0.28), 0.0, 1.1)
    add(out, partial(0.22, 2100.0, 0.06, attack=0.001), 0.10, 0.40)
    add(out, partial(0.16, 180.0, 0.05, bend=0.4, bend_tau=0.015), 0.10, 0.45)
    return out


def r_chang_jjireum():
    """창_찌름 — 좁고 빠른 바람이 위로 쏘이고, 나무 자루가 운다 (점획)."""
    out = buf(0.34)
    add(out, reso_whoosh(0.16, 0x121, 900, 2400, bw=300, skew=0.35), 0.0, 1.0)
    add(out, partial(0.14, 320.0, 0.04, bend=0.3, bend_tau=0.012), 0.09, 0.5)
    add(out, partial(0.12, 170.0, 0.05), 0.09, 0.4)
    add(out, partial(0.08, 4100.0, 0.018), 0.10, 0.22)
    return out


def r_gwon_tagyeok():
    """권_타격 — 장구 북편의 깊은 통 + 살의 마찰 + 매듭의 스냅 (주먹의 무게)."""
    out = buf(0.32)
    add(out, partial(0.24, 85.0, 0.07, bend=0.7, bend_tau=0.018, attack=0.001), 0.0, 1.0)
    add(out, noise_burst(0.06, 0x131, lp=1200, hp=200, tau=0.020), 0.0, 0.7)
    add(out, noise_burst(0.03, 0x132, lp=6000, hp=2500, tau=0.008), 0.0, 0.30)
    return out


def r_dangeom_sokgyeok():
    """단검_속격 — 아주 짧은 높은 바람 + 쇠끝 한 점 (빠름이 곧 정보)."""
    out = buf(0.26)
    add(out, reso_whoosh(0.12, 0x141, 3500, 1400, bw=340, skew=0.3), 0.0, 1.0)
    add(out, partial(0.10, 5000.0, 0.020, attack=0.001), 0.05, 0.35)
    return out


# ── 기_운용 5 — beacon/conduit 의 대체 (대금 숨·저음 공명·현 하모닉스) ──
def r_ungi_sijak():
    """운기_시작 — 대금의 숨이 차오르고 낮은 단전이 함께 운다."""
    out = buf(1.20)
    add(out, noise_swell(1.05, 0x201, lp=2400, hp=800, skew=0.40), 0.0, 0.75)
    add(out, partial(1.15, 98.0, 0.55, attack=0.30), 0.0, 0.85)
    add(out, partial(1.00, 392.0, 0.40, attack=0.50), 0.10, 0.22)
    return out


def r_eungjip():
    """응집 — 현 하모닉스가 층층이 쌓여 오르고 저음이 부푼다 (모이는 기)."""
    out = buf(1.00)
    add(out, partial(0.95, 110.0, 0.45, attack=0.25), 0.0, 0.8)
    for i, f in enumerate((523.25, 659.26, 783.99)):
        add(out, partial(0.60 - 0.1 * i, f, 0.22, attack=0.05), 0.15 + 0.24 * i, 0.34)
    add(out, noise_swell(0.90, 0x211, lp=3200, hp=1600, skew=0.75), 0.05, 0.12)
    return out


def r_balgyeong():
    """발경 — 북 한 타에 공기가 터진다: 짧은 폭발, 낮은 여운."""
    out = buf(0.62)
    add(out, buk(0.45, salt=0x221, f0=92.0), 0.0, 1.0)
    add(out, reso_whoosh(0.18, 0x222, 500, 150, bw=200, skew=0.2), 0.0, 0.8)
    add(out, partial(0.50, 58.0, 0.16, bend=0.5, bend_tau=0.03), 0.01, 0.6)
    return out


def r_gi_balhyeon():
    """기_발현 — 배음이 꽃처럼 벌어진다 (conduit 의 바다 대신 현의 만개)."""
    out = buf(0.80)
    for i, (r, g) in enumerate(((1.0, 0.9), (1.5, 0.5), (2.0, 0.38), (3.0, 0.22))):
        add(out, partial(0.70 - 0.08 * i, 440.0 * r, 0.28, attack=0.02 + 0.03 * i), 0.02 * i, g)
    add(out, noise_swell(0.55, 0x231, lp=6000, hp=3000, skew=0.35), 0.05, 0.10)
    return out


def r_gi_somyeol():
    """기_소멸 — 높은 데서 가라앉는 활강 + 새는 숨 (걷히는 기)."""
    out = buf(0.80)
    add(out, partial(0.75, 440.0, 0.30, bend=0.8, bend_tau=0.30, attack=0.01), 0.0, 0.8)
    add(out, partial(0.70, 220.0, 0.35, bend=0.5, bend_tau=0.28, attack=0.02), 0.05, 0.5)
    add(out, noise_swell(0.65, 0x241, lp=1800, hp=600, skew=0.20), 0.10, 0.35)
    return out


# ── 무공_오의 4 ──
def r_oui_sijeon():
    """오의_시전 — 북이 몰아치고(가속 롤) 숨이 차오르다 징이 벌어진다."""
    out = buf(1.50)
    for i, (t, g) in enumerate(((0.0, 0.55), (0.30, 0.62), (0.55, 0.70),
                                (0.75, 0.80), (0.90, 0.90), (1.00, 1.0))):
        add(out, buk(0.35, salt=0x301 + i, f0=90.0 - 4.0 * i), t, g)
    add(out, noise_swell(1.05, 0x30A, lp=2600, hp=900, skew=0.85), 0.0, 0.35)
    add(out, partial(0.48, 196.0, 0.28, attack=0.01), 1.00, 0.55)
    add(out, partial(0.44, 196.0 * 2.756, 0.16, attack=0.01), 1.00, 0.28)
    return out


def r_oui_jeokjung():
    """오의_적중 — 큰 북과 깨지는 쇳소리(꽹과리 파열)와 낮은 파문."""
    out = buf(1.20)
    add(out, buk(0.55, salt=0x311, f0=70.0), 0.0, 1.0)
    add(out, noise_burst(0.12, 0x312, lp=9500, hp=3000, tau=0.035), 0.0, 0.6)
    add(out, partial(0.30, 2400.0, 0.09, attack=0.001), 0.0, 0.35)
    add(out, partial(0.26, 3900.0, 0.06, attack=0.001), 0.0, 0.22)
    add(out, partial(1.05, 55.0, 0.38, bend=0.4, bend_tau=0.05), 0.02, 0.75)
    add(out, reso_whoosh(0.45, 0x313, 1200, 300, bw=200, skew=0.15), 0.08, 0.4)
    return out


def r_geomgi_bangchul():
    """검기_방출 — 날카로운 바람가름 뒤에 밝은 현이 빛줄기처럼 남는다."""
    out = buf(1.00)
    add(out, reso_whoosh(0.25, 0x321, 3000, 700, bw=280, skew=0.22), 0.0, 1.0)
    add(out, partial(0.85, 1318.5, 0.45, attack=0.01), 0.10, 0.42)
    add(out, partial(0.80, 1318.5 * 1.006, 0.40, attack=0.01), 0.10, 0.30)   # 미세 어긋남 — 맥놀이
    add(out, partial(0.60, 659.26, 0.30, attack=0.02), 0.12, 0.25)
    return out


def r_hosin_ganggi():
    """호신강기_전개 — 낮은 종이 천천히 벌어지고 밑에서 오래 운다 (지키는 소리)."""
    out = buf(1.20)
    for r, g, tau in ((1.0, 1.0, 0.50), (2.0, 0.40, 0.30), (3.01, 0.20, 0.18)):
        add(out, partial(1.10, 261.63 * r, tau, gain=g, attack=0.05))
    add(out, partial(1.10, 130.81, 0.55, attack=0.20), 0.0, 0.6)
    add(out, noise_swell(0.80, 0x331, lp=5000, hp=2500, skew=0.30), 0.05, 0.08)
    return out


# ── 타격_피격 4 ──
def r_pigyeok_yuk():
    """피격_육 — 둔한 통과 살의 마찰 (맞았다는 사실만, 과장 없이)."""
    out = buf(0.26)
    add(out, partial(0.20, 120.0, 0.05, bend=0.5, bend_tau=0.015, attack=0.001), 0.0, 1.0)
    add(out, noise_burst(0.05, 0x401, lp=900, hp=150, tau=0.020), 0.0, 0.7)
    return out


def r_pigyeok_geumsok():
    """피격_금속 — 갑주의 쨍: 비조화 쇠 배음 + 짧은 쇳가루."""
    out = buf(0.32)
    for f, g, tau in ((1650.0, 0.9, 0.05), (2470.0, 0.55, 0.035), (3900.0, 0.3, 0.022)):
        add(out, partial(0.24, f, tau, gain=g, attack=0.001))
    add(out, noise_burst(0.03, 0x411, lp=9000, hp=2000, tau=0.010), 0.0, 0.5)
    add(out, partial(0.16, 240.0, 0.04), 0.0, 0.4)
    return out


def r_makgi():
    """막기 — 단단히 받아낸 딱: 나무 몸통과 쇠 한 점이 함께 운다."""
    out = buf(0.26)
    add(out, partial(0.18, 480.0, 0.040, attack=0.001), 0.0, 0.8)
    add(out, partial(0.20, 190.0, 0.055), 0.0, 0.7)
    add(out, noise_burst(0.035, 0x421, lp=3000, hp=600, tau=0.012), 0.0, 0.6)
    add(out, partial(0.10, 2900.0, 0.018), 0.0, 0.2)
    return out


def r_binnagam():
    """빗나감 — 바람만 지나간다 (닿은 것이 없으니 울릴 것도 없다)."""
    out = buf(0.30)
    add(out, reso_whoosh(0.24, 0x431, 2200, 800, bw=240, skew=0.3), 0.0, 1.0)
    return out


# ── 세계 4 — 이음매 무봉합 루프 (앰비언스: 피크 0.45 절제) ──
def r_munpa_jong():
    """문파_종소리 — 범종 한 타가 6초에 한 번, 낮게 오래 남는다 (루프)."""
    out = buf(6.0 + 0.30)   # +0.30 = loopify 크로스페이드 여유
    for r, g, tau in ((1.0, 1.0, 2.2), (2.0, 0.45, 1.3), (2.756, 0.30, 0.8), (4.1, 0.14, 0.45)):
        add(out, partial(5.9, 98.0 * r, tau, gain=g, attack=0.005), 0.20)
    add(out, noise_burst(0.05, 0x501, lp=3000, hp=800, tau=0.015), 0.20, 0.25)
    return out


def r_jeojatgeori():
    """저잣거리_웅성 — 먼 무리의 낮은 웅성: 두 겹의 띠 잡음이 서로 다르게 숨쉰다 (루프)."""
    out = buf(4.5 + 0.30)
    add(out, lfo_noise(4.8, 0x511, lp=1100, hp=250, rate=0.23, rate2=0.41, depth=0.5), 0.0, 0.9)
    add(out, lfo_noise(4.8, 0x512, lp=800, hp=400, rate=0.37, rate2=0.59, depth=0.55), 0.0, 0.6)
    ts, fs = rnds(0x513, 7), rnds(0x514, 7)
    for t, f in zip(ts, fs):   # 말소리의 그림자 — 뜻 없는 낮은 점들
        add(out, partial(0.14, 220.0 + 160.0 * f, 0.06, attack=0.03), 0.2 + 4.0 * t, 0.16)
    return out


def r_daenamu_baram():
    """대나무_바람 — 바람이 쓸고, 잎이 스치고, 마디가 어쩌다 부딪힌다 (루프)."""
    out = buf(5.0 + 0.30)
    add(out, lfo_noise(5.3, 0x521, lp=900, hp=80, rate=0.19, rate2=0.31, depth=0.55), 0.0, 0.9)
    for i, t in enumerate(rnds(0x522, 3)):
        add(out, noise_swell(0.9, 0x523 + i, lp=6000, hp=2000, skew=0.5), 0.3 + 3.8 * t, 0.28)
    for i, t in enumerate(rnds(0x526, 2)):
        add(out, partial(0.10, 520.0, 0.030, attack=0.001), 0.5 + 3.8 * t, 0.20)
        add(out, partial(0.12, 260.0, 0.045), 0.5 + 3.8 * t, 0.16)
    return out


def r_gyegok_mul():
    """계곡_물 — 물의 쉼 없는 살랑임 위로 방울이 톡톡 솟는다 (루프)."""
    out = buf(4.0 + 0.30)
    add(out, lfo_noise(4.3, 0x531, lp=5500, hp=900, rate=1.3, rate2=2.1, depth=0.30), 0.0, 0.8)
    add(out, lfo_noise(4.3, 0x532, lp=1600, hp=300, rate=0.7, rate2=1.1, depth=0.4), 0.0, 0.45)
    ts, fs = rnds(0x533, 12), rnds(0x534, 12)
    for t, f in zip(ts, fs):   # 방울 — 음높이가 살짝 솟는 짧은 점 (bend<0 = 상승)
        add(out, partial(0.05, 600.0 + 800.0 * f, 0.020, bend=-0.35, bend_tau=0.025), 0.1 + 3.9 * t, 0.18)
    return out


# ── UI 4 ──
def r_seunggeup():
    """승급 — 가야금이 세 층을 오르고 편경이 위에서 받는다 (오르는 소리)."""
    out = buf(1.50)
    for i, f in enumerate((293.66, 392.0, 523.25)):
        add(out, pluck(f, 0.6, salt=0x601 + i), 0.22 * i, 0.75 + 0.1 * i)
    for r, g, tau in ((1.0, 1.0, 0.45), (2.756, 0.4, 0.22)):
        add(out, partial(0.80, 523.25 * r, tau, gain=g, attack=0.001), 0.66, 0.65)
    return out


def r_simbeop_jeonsu():
    """심법_전수 — 낮은 현 한 뜯음과 종 하나, 숨이 밑을 받친다 (건네받는 소리)."""
    out = buf(1.30)
    add(out, pluck(196.0, 0.9, salt=0x611), 0.0, 0.7)
    for r, g, tau in ((1.0, 1.0, 0.50), (2.0, 0.35, 0.28)):
        add(out, partial(0.90, 392.0 * r, tau, gain=g, attack=0.02), 0.35, 0.6)
    add(out, noise_swell(0.90, 0x612, lp=2200, hp=900, skew=0.45), 0.15, 0.18)
    return out


def r_giyeon():
    """기연 — 높은 데서 반짝 두 번, 잔 종이 멀리서 답한다 (뜻밖의 소리)."""
    out = buf(1.20)
    add(out, pluck(783.99, 0.5, salt=0x621), 0.0, 0.7)
    add(out, pluck(1046.5, 0.45, salt=0x622), 0.18, 0.6)
    add(out, partial(0.70, 1568.0, 0.30, attack=0.005), 0.40, 0.35)
    add(out, noise_swell(0.60, 0x623, lp=9000, hp=4000, skew=0.35), 0.05, 0.10)
    return out


def r_juhwaipma():
    """주화입마 — 어긋난 두 저음이 맥놀이로 울고, 고동이 엇박으로 뛰고, 높은 것이 떨어진다."""
    out = buf(1.60)
    add(out, partial(1.50, 110.0, 0.60, attack=0.05), 0.0, 0.75)
    add(out, partial(1.50, 116.5, 0.60, attack=0.05), 0.0, 0.65)   # 어긋난 반음 — 맥놀이
    thump = lambda: partial(0.28, 60.0, 0.08, bend=0.9, bend_tau=0.018, attack=0.002)
    for t, g in ((0.20, 0.9), (0.55, 1.0), (1.00, 0.8)):           # 엇박 고동
        add(out, thump(), t, g)
    add(out, partial(1.20, 660.0, 0.50, bend=1.2, bend_tau=0.50, attack=0.02), 0.15, 0.30)
    add(out, noise_swell(1.10, 0x631, lp=700, hp=120, skew=0.55), 0.20, 0.30)
    return out


# ── 기타 4 — 바닐라 대체(선택) ──
def r_balsori_heuk():
    """발소리_흙 — 무른 바닥의 낮은 톡."""
    out = buf(0.18)
    add(out, noise_burst(0.06, 0x701, lp=700, hp=60, tau=0.028), 0.0, 1.0)
    add(out, partial(0.08, 95.0, 0.030), 0.0, 0.5)
    return out


def r_balsori_dol():
    """발소리_돌 — 단단한 바닥의 마른 딱."""
    out = buf(0.18)
    add(out, noise_burst(0.04, 0x711, lp=2500, hp=300, tau=0.014), 0.0, 1.0)
    add(out, partial(0.05, 900.0, 0.010, attack=0.001), 0.0, 0.4)
    add(out, partial(0.08, 140.0, 0.030), 0.0, 0.5)
    return out


def r_balsori_namu():
    """발소리_나무 — 마루가 우는 속 빈 통."""
    out = buf(0.18)
    add(out, partial(0.09, 310.0, 0.030, attack=0.001), 0.0, 0.8)
    add(out, partial(0.11, 155.0, 0.040), 0.0, 0.7)
    add(out, noise_burst(0.03, 0x721, lp=1500, hp=200, tau=0.012), 0.0, 0.5)
    return out


def r_mandu_meokgi():
    """만두_먹기 — 촉촉한 두 입 (부드럽게, 요란하지 않게)."""
    out = buf(0.52)
    for i, t in enumerate((0.0, 0.24)):
        add(out, noise_burst(0.12, 0x731 + i, lp=1800, hp=150, tau=0.045, attack=0.015), t, 0.9)
        add(out, partial(0.10, 120.0, 0.040, attack=0.008), t, 0.35)
    return out


# BILL: 묶음 → [(파일이름(로마자 — 리소스 로케이션 규칙), 한글, 합성기, 길이상한 s, 루프?, 피크)]
# 길이상한·피크는 **잠정** — 등록부 bill 은 길이를 못 박지 않았다 (사용자 인게임 귀 확정 대기).
BILL = {
    "byeonggi": [   # 병기_참격 — entity.player.attack.* 대체 (1순위)
        ("geom_cham", "검_참", r_geom_cham, 0.6, False, PEAK),
        ("do_cham", "도_참", r_do_cham, 0.6, False, PEAK),
        ("chang_jjireum", "창_찌름", r_chang_jjireum, 0.6, False, PEAK),
        ("gwon_tagyeok", "권_타격", r_gwon_tagyeok, 0.6, False, PEAK),
        ("dangeom_sokgyeok", "단검_속격", r_dangeom_sokgyeok, 0.6, False, PEAK),
    ],
    "gi": [         # 기_운용 — beacon/conduit 대체
        ("ungi_sijak", "운기_시작", r_ungi_sijak, 1.3, False, PEAK),
        ("eungjip", "응집", r_eungjip, 1.1, False, PEAK),
        ("balgyeong", "발경", r_balgyeong, 0.8, False, PEAK),
        ("gi_balhyeon", "기_발현", r_gi_balhyeon, 0.9, False, PEAK),
        ("gi_somyeol", "기_소멸", r_gi_somyeol, 0.9, False, PEAK),
    ],
    "oui": [        # 무공_오의
        ("oui_sijeon", "오의_시전", r_oui_sijeon, 1.6, False, PEAK),
        ("oui_jeokjung", "오의_적중", r_oui_jeokjung, 1.3, False, PEAK),
        ("geomgi_bangchul", "검기_방출", r_geomgi_bangchul, 1.1, False, PEAK),
        ("hosin_ganggi", "호신강기_전개", r_hosin_ganggi, 1.3, False, PEAK),
    ],
    "tagyeok": [    # 타격_피격
        ("pigyeok_yuk", "피격_육", r_pigyeok_yuk, 0.4, False, PEAK),
        ("pigyeok_geumsok", "피격_금속", r_pigyeok_geumsok, 0.4, False, PEAK),
        ("makgi", "막기", r_makgi, 0.4, False, PEAK),
        ("binnagam", "빗나감", r_binnagam, 0.4, False, PEAK),
    ],
    "segye": [      # 세계 — 이음매 무봉합 루프 · 앰비언스는 조용해야 한다 (피크 0.45 잠정)
        ("munpa_jong", "문파_종소리", r_munpa_jong, 6.5, True, 0.45),
        ("jeojatgeori", "저잣거리_웅성", r_jeojatgeori, 5.0, True, 0.45),
        ("daenamu_baram", "대나무_바람", r_daenamu_baram, 5.5, True, 0.45),
        ("gyegok_mul", "계곡_물", r_gyegok_mul, 4.5, True, 0.45),
    ],
    "ui": [         # UI — 소비자는 Java(playSound) — 전환은 별도 회차
        ("seunggeup", "승급", r_seunggeup, 1.6, False, PEAK),
        ("simbeop_jeonsu", "심법_전수", r_simbeop_jeonsu, 1.4, False, PEAK),
        ("giyeon", "기연", r_giyeon, 1.3, False, PEAK),
        ("juhwaipma", "주화입마", r_juhwaipma, 1.7, False, PEAK),
    ],
    "gita": [       # 기타 — 바닐라 대체(선택)
        ("balsori_heuk", "발소리_흙", r_balsori_heuk, 0.3, False, PEAK),
        ("balsori_dol", "발소리_돌", r_balsori_dol, 0.3, False, PEAK),
        ("balsori_namu", "발소리_나무", r_balsori_namu, 0.3, False, PEAK),
        ("mandu_meokgi", "만두_먹기", r_mandu_meokgi, 0.6, False, PEAK),
    ],
}


# ─── WAV → ogg ───────────────────────────────────────────────────────────────
def write_wav(path, samples):
    pcm = b"".join(struct.pack("<h", max(-32767, min(32767, int(round(s * 32767)))))
                   for s in samples)
    path.write_bytes(
        b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16)
        + b"data" + struct.pack("<I", len(pcm)) + pcm)


def encode_ogg(wav, ogg):
    """libvorbis q4 · bitexact — 시리얼·코멘트·타임스탬프가 전부 고정된다 (결정론 조건 ③)."""
    subprocess.run(
        [FFMPEG, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", str(wav),
         "-c:a", "libvorbis", "-qscale:a", "4", "-ac", "1", "-ar", str(SR),
         "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
         str(ogg)], check=True)


def forge(fn, max_s, out_path, loop=False, peak=PEAK):
    """날 파형 → (루프면 무봉합 접기) → 정규화 → WAV → bitexact ogg. 길이 상한을 강제한다."""
    raw = fn()
    samples = loopify(raw) if loop else raw
    dur = len(samples) / SR
    if dur > max_s + 1e-9:   # 상한 — 넘으면 굽지 않는다
        raise ValueError(f"{out_path.name}: {dur:.2f}s > 상한 {max_s}s")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        wav = Path(td) / "x.wav"
        write_wav(wav, normalize(samples, peak=peak, fade_out=not loop))
        encode_ogg(wav, out_path)
    return out_path


def render(direction, channel, out_path):
    fn, _ = DIRECTIONS[direction][channel]
    return forge(fn, MAX_S[channel], out_path)


def render_all(outdir):
    """서장 8표본 — 파일명이 채널·방향을 말한다 (청취 표본용)."""
    made = []
    for ch in CHANNELS:
        for d in ("A", "B"):
            name = DIRECTIONS[d]["이름"]
            desc = DIRECTIONS[d][ch][1]
            made.append(render(d, ch, Path(outdir) / f"seojang-{ch}__{d}{name}_{desc}.ogg"))
    return made


def render_bill(root):
    """청구서 30종 — root/<묶음>/<이름>.ogg (팩 트리 또는 검증용 임시 트리)."""
    made = []
    for group, items in BILL.items():
        for name, _hangul, fn, max_s, loop, peak in items:
            made.append(forge(fn, max_s, Path(root) / group / f"{name}.ogg",
                              loop=loop, peak=peak))
    return made


def main():
    ap = argparse.ArgumentParser(description="혼천 소리 대장간 — 결정론 합성 .ogg")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--audition", metavar="OUTDIR", help="8표본(채널×방향)을 OUTDIR 에 굽는다")
    g.add_argument("--install", choices=("A", "B"),
                   help="서장: 고른 방향 4장을 resourcepack/assets/honcheon/sounds/seojang/ 에 설치")
    g.add_argument("--forge-bill", action="store_true",
                   help="청구서 30종을 resourcepack/assets/honcheon/sounds/<묶음>/ 에 설치 (국악 기조)")
    g.add_argument("--verify", action="store_true",
                   help="결정론 증명 — 2회 독립 합성 해시 대조 (서장 8 + 청구서 30)")
    args = ap.parse_args()

    if args.audition:
        for p in render_all(args.audition):
            print(f"  {p}")
        return

    if args.install:
        for ch in CHANNELS:
            p = render(args.install, ch, PACK_SEOJANG / f"{ch}.ogg")
            print(f"  {p.relative_to(ROOT)}")
        print("설치 완료 — python3 tools/build_resourcepack.py 로 sounds.json 을 재지향→실물로 전환하라")
        return

    if args.forge_bill:
        for p in render_bill(PACK_SEOJANG.parent):
            print(f"  {p.relative_to(ROOT)}")
        print("청구서 30종 설치 완료 — build_resourcepack.py 가 honcheon:<묶음>.<이름> 으로 자동 등재한다"
              " (소비자 key 전환은 별도 회차 — skill_motion.yml 등)")
        return

    # --verify: 같은 코드 2회 → 바이트 동일 증명 (서장 8표본 + 청구서 30종)
    def _one_pass(td):
        made = render_all(Path(td) / "seojang_ab") + render_bill(Path(td) / "bill")
        return {str(p.relative_to(td)): hashlib.sha256(p.read_bytes()).hexdigest() for p in made}

    with tempfile.TemporaryDirectory() as t1, tempfile.TemporaryDirectory() as t2:
        a, b = _one_pass(t1), _one_pass(t2)
    bad = [n for n in a if a[n] != b[n]]
    for n in sorted(a):
        mark = "✗" if n in bad else "="
        print(f"  {mark} {a[n][:16]}  {n}")
    if bad:
        print(f"결정론 깨짐: {len(bad)}건 — ffmpeg 빌드가 바뀌었는가?")
        sys.exit(1)
    print(f"결정론 확인 — {len(a)}/{len(a)} 표본이 2회 합성에서 바이트 동일 (ffmpeg bitexact)")


if __name__ == "__main__":
    main()
