#!/usr/bin/env python3
"""★★★ 형태냐 스케일이냐 — 확대 판단을 육안이 아니라 수치로 가르는 자 (REF Q4).

    사용자 질문: 「본전을 키워야 하는가?」
    Codex 권고(2026-08-10)를 반영한 설계:
      · 픽셀 분할을 안 쓴다 — 실측 기하를 <b>알려진 카메라로 직접 투영</b>한다 (결정론적)
      · 가로·세로를 따로 정규화하지 않는다 — 그러면 폭:높이 비가 사라진다.
        <b>한 스칼라로 등방 확대</b>해 종횡비를 보존한다
      · 1D 상단선이 아니라 <b>2D 실루엣과 높이별 폭 w(y)</b> 를 쓴다
        (상단선은 하층 처마·층간 분리를 놓친다)
      · 비교자는 같은 단(B5)의 <b>망루</b>다. 캠퍼스 평균은 역할·거리가 섞여 못 쓴다.
        AI 레퍼런스는 카메라·기하가 불명이라 <b>절대 크기의 기준이 될 수 없다</b>

★★<b>문턱을 여기에 두지 않는다.</b> 이 저장소는 「문턱을 조여 통과시키면 그건 오늘의 처방을
  지키는 눈」이라는 이유로 비율 문턱을 눈에 넣는 것을 이미 한 번 거부했다 (본전 회벽 비율).
  그래서 이 도구는 <b>수치·순위·효과량만 찍는다.</b> 판정은 사람이 한다.
  selftest 에 넣을 것은 미학 문턱이 아니라 <b>계측기 검증</b>뿐이다 —
  결정론 · 「확대 변이가 측정값을 올린다」.
"""
import math
import sys

from PIL import Image, ImageDraw

W, H = 1280, 720
FOV_Y = 70.0                      # 마인크래프트 기본 (세로 화각)
CAM = (1.0, 74.0, 95.0)           # 고정 REF 원경 카메라
YAW, PITCH = 180.0, 3.0


def basis():
    ry, rp = math.radians(YAW), math.radians(PITCH)
    fwd = (-math.sin(ry) * math.cos(rp), -math.sin(rp), math.cos(ry) * math.cos(rp))
    right = (math.cos(ry), 0.0, math.sin(ry))
    up = (right[1] * fwd[2] - right[2] * fwd[1],
          right[2] * fwd[0] - right[0] * fwd[2],
          right[0] * fwd[1] - right[1] * fwd[0])
    return fwd, right, up


FWD, RIGHT, UP = basis()
FY = (H / 2) / math.tan(math.radians(FOV_Y) / 2)


def project(p):
    d = (p[0] - CAM[0], p[1] - CAM[1], p[2] - CAM[2])
    z = sum(a * b for a, b in zip(d, FWD))
    if z <= 0.05:
        return None
    x = sum(a * b for a, b in zip(d, RIGHT))
    y = sum(a * b for a, b in zip(d, UP))
    return (W / 2 + x * FY / z, H / 2 - y * FY / z)


def mask_of(boxes, size=(W, H)):
    """상자 목록을 한 장의 실루엣으로 — 각 상자의 투영 볼록껍질을 채운다."""
    im = Image.new('1', size, 0)
    dr = ImageDraw.Draw(im)
    for (x0, x1, y0, y1, z0, z1) in boxes:
        pts = []
        for xx in (x0, x1 + 1):
            for yy in (y0, y1 + 1):
                for zz in (z0, z1 + 1):
                    q = project((xx, yy, zz))
                    if q:
                        pts.append(q)
        if len(pts) < 3:
            continue
        dr.polygon(hull(pts), fill=1)
    return im


def hull(pts):
    pts = sorted(set((round(a, 3), round(b, 3)) for a, b in pts))
    def half(ps):
        out = []
        for p in ps:
            while len(out) >= 2:
                (ax, ay), (bx, by) = out[-2], out[-1]
                if (bx - ax) * (p[1] - ay) - (by - ay) * (p[0] - ax) <= 0:
                    out.pop()
                else:
                    break
            out.append(p)
        return out
    lo, up = half(pts), half(pts[::-1])
    return lo[:-1] + up[:-1]


def profile(im):
    """높이별 폭 w(y) — 덮인 화소가 있는 행의 [가로 폭]. 위에서 아래로."""
    px = im.load()
    rows = []
    for y in range(im.size[1]):
        xs = [x for x in range(im.size[0]) if px[x, y]]
        rows.append((y, min(xs), max(xs), len(xs)) if xs else (y, 0, 0, 0))
    return [r for r in rows if r[3] > 0]


def shoulders(w, eps=0.04):
    """어깨(층이 갈리는 지점) — w(y) 가 <b>두드러지게</b> 꺾이는 곳의 수와 세기.
    ★원시 극대·단조 구간 수는 블록 잡음에 민감하다 (Codex). 평활 뒤 prominence 로 센다."""
    if len(w) < 5:
        return 0, 0.0
    sm = [sum(w[max(0, i - 2):i + 3]) / len(w[max(0, i - 2):i + 3]) for i in range(len(w))]
    mx = max(sm) or 1
    sm = [v / mx for v in sm]
    n, tot = 0, 0.0
    for i in range(2, len(sm) - 2):
        d1 = sm[i] - sm[i - 2]
        d2 = sm[i + 2] - sm[i]
        if abs(d2 - d1) > eps:
            n += 1
            tot += abs(d2 - d1)
    return n, tot


def resample(w, n=128):
    """★고정 길이로 되맞춘다 — 검출기가 <b>언제나 같은 표본 밀도</b>를 보게."""
    if not w:
        return []
    return [w[min(len(w) - 1, round(i * (len(w) - 1) / (n - 1)))] for i in range(n)]


def retention(w):
    """다중 스케일 잔존도 — 1/2·1/4·1/8 로 흐려도 어깨가 몇이나 남는가.

    ★★결함 하나를 고쳤다 (Codex 지적 2026-08-10): 전에는 줄인 배열을 <b>그대로</b> 셌더니
      표본이 짧아져 ±2 창이 상대적으로 넓어졌고, 그래서 잔존이 [10, 9, 10, <b>14</b>] 로
      <b>끝에서 되레 늘었다.</b> 흐릴수록 덜 보여야 하는데 더 보인 것이다 — 지표 결함이다.
      이제 흐린 뒤 <b>고정 길이로 되맞춰</b> 센다. 잔존은 단조 감소해야 한다."""
    out = []
    for k in (1, 2, 4, 8):
        cur = w if k == 1 else [sum(w[i:i + k]) / len(w[i:i + k])
                                for i in range(0, max(1, len(w) - k + 1), k)]
        out.append(shoulders(resample(cur))[0])
    return out


def norm_iso(im):
    """★등방 정규화 — 세로를 1 로 맞추되 <b>가로도 같은 배율</b>로 (종횡비 보존)."""
    px = im.load()
    xs = [x for x in range(im.size[0]) for y in range(im.size[1]) if px[x, y]]
    ys = [y for y in range(im.size[1]) for x in range(im.size[0]) if px[x, y]]
    if not xs:
        return None
    bw, bh = max(xs) - min(xs) + 1, max(ys) - min(ys) + 1
    crop = im.crop((min(xs), min(ys), max(xs) + 1, max(ys) + 1))
    k = 256 / bh
    return crop.resize((max(1, round(bw * k)), 256), Image.NEAREST)


def area(im):
    px = im.load()
    return sum(1 for y in range(im.size[1]) for x in range(im.size[0]) if px[x, y])


# ── 실측 기하 (인게임에서 잰 값) ────────────────────────────────────────
def grand_rise(i, steps):
    soft = max(1, steps // 3)
    return i // 2 if i <= soft else soft // 2 + (i - soft)


def roof_rings(x0, x1, cy, z0, z1, sx):
    """★지붕을 <b>통짜 상자로 두면 안 된다</b> — 그러면 실루엣이 실물이 아니라 모형을 잰다.
    조성과 같은 식(grand_rise)으로 <b>켜마다 한 상자</b>를 낸다."""
    out = []
    steps = min(x1 - x0, z1 - z0) // 2 + 1
    for i in range(steps):
        ax0, ax1 = x0 + i, x1 - i
        az0, az1 = z0 + i, z1 - i
        if ax1 - ax0 <= 0 or az1 - az0 <= 0:
            break
        y = cy + grand_rise(i, steps)
        out.append((sx(ax0), sx(ax1), y, y, az0, az1))
    return out


def honjeon(scale=1.0):
    """본전 — 기단·하층·층간지붕·상층·대지붕·용마루.
    scale 은 <b>가로만</b> 늘리는 확대 실험 (형태는 그대로)."""
    def sx(a):
        return round(1 + (a - 1) * scale)
    boxes = [
        (sx(-17), sx(19), 55, 58, 37, 59),      # 기단 3단 + 몰딩
        (sx(-15), sx(17), 58, 66, 40, 54),      # 하층 몸체 (적주면까지)
    ]
    boxes += roof_rings(-17, 19, 67, 38, 55, sx)    # 층간 지붕 — 켜마다
    boxes.append((sx(-11), sx(13), 70, 73, 42, 51))  # 상층 몸체
    boxes += roof_rings(-15, 17, 74, 40, 53, sx)    # 대지붕 — 켜마다
    boxes.append((sx(-7), sx(9), 81, 82, 46, 46))    # 용마루
    return boxes


# ★★망루도 <b>실측</b>에서 세운다 (Codex: 계측 기하가 실제 조성과 함께 변해야 한다).
#   손으로 한 상자를 그려 두면 망루가 바뀌어도 계측기는 <b>아주 결정론적으로 틀린 채</b> 남는다.
#   아래는 인게임 실측 — z=50 단면에서 x 마다 잰 꼭대기 y (지면 y51).
TOWER_TOPS = {20: 52, 22: 51, 24: 51, 26: 51, 28: 61, 30: 67, 32: 69,
              34: 73, 36: 69, 38: 67, 40: 61, 42: 51, 44: 51, 46: 51}
TOWER_GROUND = 51


def tower():
    out = []
    xs = sorted(TOWER_TOPS)
    for i, x in enumerate(xs):
        top = TOWER_TOPS[x]
        if top <= TOWER_GROUND:
            continue
        x1 = xs[i + 1] - 1 if i + 1 < len(xs) else x + 1
        out.append((x, x1, TOWER_GROUND, top, 44, 56))
    return out


def main():
    print('== 스케일 (같은 카메라 · 투영 화소) ==')
    hj = mask_of(honjeon())
    tw = mask_of(tower())
    a_hj, a_tw = area(hj), area(tw)
    print(f'   본전   투영 면적 {a_hj:>7,}')
    print(f'   망루   투영 면적 {a_tw:>7,}')
    print(f'   본전/망루 = {a_hj / a_tw:.2f}배')

    print('== 확대 효과량 (가로만 늘렸을 때 · 형태 동일) ==')
    base = a_hj
    for lab, s in (('현행 31폭', 1.0), ('35폭', 35 / 31), ('37폭', 37 / 31)):
        m = mask_of(honjeon(s))
        print(f'   {lab:<9} 투영 면적 {area(m):>7,}  ({area(m) / base:.2f}배 · 망루 대비 '
              f'{area(m) / a_tw:.2f})')

    print('== 형태 (등방 정규화 뒤) ==')
    nz = norm_iso(hj)
    w = [r[3] for r in profile(nz)]
    n, strength = shoulders(w)
    print(f'   정규화 실루엣 {nz.size[0]}×{nz.size[1]}  (종횡비 {nz.size[0] / nz.size[1]:.2f})')
    print(f'   어깨 {n}개 · 세기 합 {strength:.2f}')
    print(f'   다중 스케일 잔존 (1·1/2·1/4·1/8) {retention(w)}')
    ntw = norm_iso(tw)
    wtw = [r[3] for r in profile(ntw)]
    ntn, ntstr = shoulders(wtw)
    print(f'   ─ 견줌: 망루 어깨 {ntn}개 · 세기 {ntstr:.2f} · 잔존 {retention(wtw)}')

    print('== 실루엣 충전율 (제3 원인 — 가림·배경 대비와 함께 봐야 한다) ==')
    px = hj.load()
    xs = [x for x in range(W) for y in range(H) if px[x, y]]
    ys = [y for y in range(H) for x in range(W) if px[x, y]]
    bb = (max(xs) - min(xs) + 1) * (max(ys) - min(ys) + 1)
    print(f'   본전 실루엣/외접상자 = {a_hj / bb:.2f}')
    print()
    print('※ 문턱은 여기 없다. 수치·순위·효과량만 찍는다 — 판정은 사람이 한다.')


def selftest():
    """★★<b>눈을 시험하는 눈</b> — 미학 문턱이 아니라 <b>계측기</b>를 잰다 (Codex 권고).
    「계측기는 아주 결정론적으로 틀릴 수 있다」 — 통짜 지붕 오류가 그것이었다."""
    ok = [0]
    bad = []

    def chk(name, cond, got=''):
        if cond:
            ok[0] += 1
        else:
            bad.append(f'{name} — 실제: {got}')

    box = [(0, 20, 60, 80, 40, 50)]
    m = mask_of(box)
    # ① 결정론
    chk('결정론 — 두 번 재면 같다', area(m) == area(mask_of(box)))
    # ② 집합 불변 — 순서·중복·내부 상자
    chk('상자 순서를 바꿔도 실루엣이 같다',
        area(mask_of(box + [(2, 5, 62, 70, 42, 48)])) == area(mask_of([(2, 5, 62, 70, 42, 48)] + box)))
    chk('같은 상자를 겹쳐도 실루엣이 같다', area(mask_of(box * 3)) == area(m))
    chk('완전히 안에 든 상자를 더해도 실루엣이 같다',
        area(mask_of(box + [(5, 15, 65, 75, 42, 48)])) == area(m))
    # ③ 투영 오라클
    far = [(0, 20, 60, 80, 10, 20)]
    chk('멀어지면 투영 면적이 준다', area(mask_of(far)) < area(m),
        f'{area(mask_of(far))} vs {area(m)}')
    # ④ 어깨 합성 시험
    chk('직사각형은 어깨가 0', shoulders(resample([100] * 200))[0] == 0,
        shoulders(resample([100] * 200))[0])
    step = [100] * 100 + [60] * 100
    chk('한 단 계단은 어깨가 생긴다', shoulders(resample(step))[0] >= 1,
        shoulders(resample(step))[0])
    noisy = [v + (1 if i % 2 else -1) for i, v in enumerate([100] * 200)]
    chk('±1 잡음이 새 어깨를 만들지 않는다', shoulders(resample(noisy))[0] == 0,
        shoulders(resample(noisy))[0])
    # ⑤ 잔존은 <b>단조 감소</b> — 흐릴수록 덜 보여야 한다
    r = retention([r2[3] for r2 in profile(norm_iso(mask_of(honjeon())))])
    chk('잔존이 단조 감소한다 (흐릴수록 덜 보인다)', all(r[i] >= r[i + 1] for i in range(len(r) - 1)), r)
    # ⑥ 확대 민감도 — 면적도 투영 폭도 오른다 · 축소는 내린다
    def w_of(s):
        im = mask_of(honjeon(s))
        px = im.load()
        xs = [x for x in range(W) for y in range(H) if px[x, y]]
        return area(im), max(xs) - min(xs) + 1
    a1, w1 = w_of(1.0)
    a2, w2 = w_of(37 / 31)
    a0, w0 = w_of(25 / 31)
    chk('확대하면 면적이 오른다', a2 > a1, f'{a2} > {a1}')
    chk('확대하면 <b>투영 폭</b>도 오른다 (면적만 보면 두께로 속을 수 있다)', w2 > w1, f'{w2} > {w1}')
    chk('축소하면 둘 다 내린다', a0 < a1 and w0 < w1, f'{a0}/{w0}')
    # ⑦ 음성 대조군 — 확대와 무관한 이동이 「확대 성공」으로 안 읽힌다
    moved = [(x0, x1, y0 + 3, y1 + 3, z0, z1) for (x0, x1, y0, y1, z0, z1) in honjeon()]
    _, wm = area(mask_of(moved)), None
    pxm = mask_of(moved).load()
    xsm = [x for x in range(W) for y in range(H) if pxm[x, y]]
    # ★자를 고쳤다: 처음엔 「±2 화소 안」으로 쟀는데, 원근에서는 위로 옮기기만 해도 폭이
    #   조금 변한다 (533 → 536 · +0.6%). 절대 화소는 자가 아니다 —
    #   물어야 할 것은 <b>진짜 확대에 견줘 무시할 만한가</b>다 (+0.6% vs +19%).
    wm = max(xsm) - min(xsm) + 1
    chk('위로 옮기기만 한 것은 확대로 안 읽힌다 (폭 변화가 진짜 확대의 1/5 미만)',
        abs(wm - w1) < abs(w2 - w1) / 5,
        f'옮김 {abs(wm - w1)} vs 확대 {abs(w2 - w1)}')
    # ⑧ 라벨이 실물과 맞나 — 「31폭」이 정말 31 인가
    body = [b for b in honjeon() if b[2] == 58][0]
    chk('라벨 「31폭」이 모델의 하층 몸체 폭과 맞다 (적주 +1 포함 33)',
        body[1] - body[0] + 1 == 33, body[1] - body[0] + 1)

    print(f'계측기의 눈 — {ok[0]}/{ok[0] + len(bad)} 통과')
    for b in bad:
        print('  ✗', b)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(selftest() if '--selftest' in sys.argv else main())
