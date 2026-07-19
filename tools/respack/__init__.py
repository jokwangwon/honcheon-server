"""혼천 리소스팩 컴파일러 패키지 — main() 이 전 모듈을 순서대로 굽는다."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (
    CONTAINER_DIR, FONT_DIR, HUD_DIR, PACK, PACK_FORMAT, ROOT, art_rows, paint_rows, write_png,
)
from .gui import (
    FOOD_SPRITES, GLYPH_SHADES, HEART_SPRITES, REALM_CRESTS, bimu_icon, crafting_container,
    furnace_container, gauge, generic_54_container, gise_icon, gui_background, hotbar,
    hotbar_selection, inventory_container, seojang_glyphs, write_hud_sprites,
    write_ui_sprites, xp_bar,
)
from .weapons import (write_myeongbyeong_assets)
from .items import (write_item_assets)
from .dispatch import (write_vanilla_dispatch)
from .sounds import (MUTED_AMBIENT, MUTED_MUSIC, write_seojang_sounds, write_sounds)
from .blocks import (
    ANIMATED, UNTINT_CLIP, write_block_textures, write_particle_textures, write_prop_textures,
    write_tint_assets,
)
from .entities import (write_entity_textures, write_mob_assets)
from .furniture import (write_furniture_assets)
from .hanok import (write_hanok_assets)   # V2-H 한옥 건축 어휘 3D
from .qi import (write_qi_assets)

def assert_unique_codepoints(providers):
    """중복 codepoint 검사 【SJ-002 · RP 합의 7】 — 같은 자리에 두 글리프면 **뒤가 앞을 조용히 덮는다.**

    슬롯은 등록제(resourcepack_design.yml glyph_slots)지만 등록부는 사람이 읽는 표라서,
    빌더에 새 프로바이더를 더할 때 이미 쓰인 자리를 다시 쓰면 아무도 소리내지 않았다 —
    화면에서는 한쪽 글리프가 **말없이 사라진다** (E0B0 서장 대역을 열며 이 눈을 세운다).
    bitmap 의 chars 와 space 의 advances 키를 한 자리씩 센다. 겹치면 굽지 않는다.
    """
    seen = {}
    clashes = []
    for i, p in enumerate(providers):
        chars = []
        if p.get("type") == "bitmap":
            for row in p.get("chars", []):
                chars.extend(row)
        elif p.get("type") == "space":
            chars.extend(p.get("advances", {}).keys())
        for ch in chars:
            if ch in (" ", "\u0000"):
                continue   # 바닐라 문법의 빈 칸 — 자리가 아니다
            if ch in seen:
                clashes.append(f"U+{ord(ch):04X} (프로바이더 #{seen[ch]} ↔ #{i})")
            else:
                seen[ch] = i
    if clashes:
        raise ValueError("중복 codepoint — 뒤의 프로바이더가 앞을 조용히 덮는다: " + ", ".join(clashes))


def main():
    # --sheet: 엔티티 확대 검수 시트도 함께 굽는다 (run/texture-review/ — 커밋 대상 아님)
    sheet = "--sheet" in sys.argv
    write_png(FONT_DIR / "gise.png", gise_icon())
    providers = [{
        "type": "bitmap", "file": "honcheon:font/gise.png",
        # PUA 리터럴 금지 — 편집기가 사설 영역 문자를 조용히 지울 수 있다 (M2b에서 실제 유실
        # → 빈 chars 하나가 팩 전체를 무효화). 다른 프로바이더처럼 chr()로 만든다.
        "height": 8, "ascent": 7, "chars": [chr(0xE000)],
    }]
    for n in range(9):
        write_png(FONT_DIR / f"gauge_{n}.png", gauge(n))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/gauge_{n}.png",
            "height": 7, "ascent": 6, "chars": [chr(0xE010 + n)],
        })
    for i, (realm, art) in enumerate(REALM_CRESTS.items()):
        # 2026-07-16 승격 — 2값(art_rows) → 농담 4단(paint_rows). 실루엣(α≥128 마스크)은 불변:
        # 축 ㉑ 인접 XOR 합격값이 그대로 산다 (gui.REALM_CRESTS 머리말).
        write_png(FONT_DIR / f"crest_{i}.png", paint_rows(art, GLYPH_SHADES))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/crest_{i}.png",
            "height": 8, "ascent": 7, "chars": [chr(0xE020 + i)],
        })
    # 비무 표식 (E030) — 액션바·명패 앞에 붙일 글리프. 플러그인이 안 붙이면 그냥 안 보인다
    # (팩 게이트: 글리프는 강화지 필수가 아니다. Sparring 의 액션바 문구만으로도 읽힌다).
    write_png(FONT_DIR / "bimu.png", bimu_icon())
    providers.append({
        "type": "bitmap", "file": "honcheon:font/bimu.png",
        "height": 8, "ascent": 7, "chars": [chr(0xE030)],
    })
    # ─── 바닐라 HUD 텍스처 교체 (폰트 아님 — 스프라이트 직접 교체, 9x9 치수 계약) ───
    for name, art, palette in HEART_SPRITES:
        write_png(HUD_DIR / "heart" / f"{name}.png", paint_rows(art, palette))
    for name, art, palette in FOOD_SPRITES:      # 허기 — 닭다리를 만두로 (바닐라 허기는 살아 있다)
        write_png(HUD_DIR / f"{name}.png", paint_rows(art, palette))
    write_png(HUD_DIR / "experience_bar_background.png", xp_bar(False))
    write_png(HUD_DIR / "experience_bar_progress.png", xp_bar(True))   # 내공 — 초록을 옥으로
    write_png(HUD_DIR / "hotbar.png", hotbar())
    write_png(HUD_DIR / "hotbar_selection.png", hotbar_selection())
    write_png(CONTAINER_DIR / "inventory.png", inventory_container())
    write_png(CONTAINER_DIR / "generic_54.png", generic_54_container())
    # 화덕 3종 — 배치가 같으니 같은 그림이다 (바닐라도 furnace/smoker/blast_furnace 가 동일 배치)
    furnace = furnace_container()
    for name in ("furnace", "smoker", "blast_furnace"):
        write_png(CONTAINER_DIR / f"{name}.png", furnace)
    write_png(CONTAINER_DIR / "crafting_table.png", crafting_container())
    hud_new = write_hud_sprites()   # B-036 신작 — 십자선·숨 3종·보스바 파랑/빨강 (치수 = 1.21.11 실측)

    write_png(FONT_DIR / "gui_ledger.png", gui_background())
    providers.append({
        "type": "bitmap", "file": "honcheon:font/gui_ledger.png",
        "height": 110, "ascent": 13, "chars": [chr(0xE080)],   # ascent는 인게임 튜닝 대상
    })

    # 음수 공백 프로바이더 (E0A0~E0A5) — 경락도 GUI 배경(E080) 제목 음수 공백 기법용.
    # 2의 거듭제곱 음수 폭 + 미세조정 +1 폭 조합으로 임의 오프셋 구성. F26: 키도 chr()로만.
    providers.append({
        "type": "space",
        "advances": {
            chr(0xE0A0): -8, chr(0xE0A1): -16, chr(0xE0A2): -32,
            chr(0xE0A3): -64, chr(0xE0A4): -128, chr(0xE0A5): 1,
        },
    })

    # 서장 기억첩 (E0B0~E0B3) — seojang_presentation.md §3.2·§4.1 이 이름 댄 4종만.
    # 굽기와 프로바이더 등재는 한 몸이다 (glyph_slots E0B0_E0BF note — 등록부 등재도 함께).
    seojang = seojang_glyphs()
    for cp, name, glyph_rows, height, ascent in seojang:
        write_png(FONT_DIR / f"{name}.png", glyph_rows)
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/{name}.png",
            "height": height, "ascent": ascent, "chars": [chr(cp)],
        })

    # 중복 codepoint 검사 — 겹치면 굽지 않는다 (RP 합의 7: 뒤의 프로바이더가 앞을 조용히 덮는다)
    assert_unique_codepoints(providers)

    font = PACK / "assets" / "minecraft" / "font" / "default.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    # ensure_ascii=True — 산출 JSON에서도 PUA가 \uXXXX 이스케이프로 남는다 (F26: 리터럴 유실 방지)
    font.write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")

    # ─── 아이템·블록 텍스처 레이어 (texture_layer_design.md — 1차) ───
    items = write_item_assets()
    myeong = write_myeongbyeong_assets()      # 명병 — 문파의 얼굴 (등록된 8문파)
    gate = write_vanilla_dispatch()           # ★ 팩 게이트 — 팩 없는 눈은 바닐라를 본다 (보라 큐브 아님)
    muted = write_sounds()                    # 소리 — 배경음악·동굴 앰비언스를 끈다 (굽는 것은 청구서)
    seojang_snd = write_seojang_sounds()      # 서장 사건음 4채널 — .ogg 없으면 바닐라 재지향 (SJ-002)
    blocks = write_block_textures()
    tints = write_tint_assets()     # 컬러맵·해·달 — **세계의 초록은 컬러맵에서 나온다**
    parts = write_particle_textures()
    props = write_prop_textures()
    ents = write_entity_textures(sheet)

    # ─── 3D 모델층 (§texture_layer_design.md §6) — 획·형체·메뉴 ───
    furn_bs, furn_models = write_furniture_assets()   # V2-F 한옥 가구 3D — 팩 첫 blockstates+블록 모델
    hanok = write_hanok_assets()    # V2-H 한옥 건축 어휘 — 기와 프로파일·세살창 (모델 재정의 · blockstates 불변)  # noqa: F841
    qi = write_qi_assets()          # 무공의 획 9종 (skill_motion.yml display.models 의 청구서)
    mobs = write_mob_assets()       # 짐승의 형체 8종 (mob_models.yml 의 청구서)
    ui = write_ui_sprites()         # 메뉴·버튼 (바닐라 mcmeta 를 건드리지 않는다 = 좌표 계약 불변)

    # pack_format 은 클라이언트 버전이 정한다 — 서버 jar 의 version.json(pack_version.resource_major)이 진실.
    #   1.21.4 = 46 · 1.21.11 = 75. 숫자가 어긋나면 클라이언트가 "낡은 팩" 경고를 띄운다(적용은 되지만
    #   경고가 뜨는 팩은 사용자가 끈다). supported_formats 로 46~75 를 함께 받아 구 클라이언트도 살린다.
    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": PACK_FORMAT,
                 # 신·구 스키마를 **함께** 선언한다 — 1.21.9+ 클라이언트는 min_format/max_format 를 요구하고
                 # (없으면 "지원 버전 누락" 경고), 구 클라이언트는 supported_formats 를 읽는다.
                 # 데이터팩에서 이 필드가 없어 팩이 통째로 로드되지 않은 적이 있다 (조용히 꺼졌다).
                 "supported_formats": {"min_inclusive": 46, "max_inclusive": PACK_FORMAT},
                 "min_format": 46,
                 "max_format": PACK_FORMAT,
                 "description": "혼천(渾天) — 수묵 무협 팩 · 3D 병기 45 · 무공 획 9 · 짐승 형체 8 · 블록 242 · 컬러맵·물·풀·잎 · 메뉴 (1.21.11)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 1 + 9 + len(REALM_CRESTS) + 1 + 1 + len(seojang)      # + 서장 기억첩 4 (E0B0~E0B3)
    # 하트 6 + 만두 6 + 내공바 2 + 핫바 2 + 컨테이너 6 + B-036 신작(십자선·숨 3·보스바 4) 8
    vanilla = len(HEART_SPRITES) + len(FOOD_SPRITES) + 2 + 2 + 6 + hud_new
    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} "
          f"(글리프 {total}종 + 음수공백 6폭 + 바닐라 교체 {vanilla}장 + 폰트 주입 + pack.mcmeta)")
    print(f"  아이템 채널 {items}종 (PNG {items} + 모델 {items} + 아이템 정의 {items}) — 전역 오염 0")
    print(f"  ★ 팩 게이트 {gate}장 (assets/minecraft/items/<base>.json — custom_model_data select 분기)")
    print(f"  │  팩 없는 눈 = fallback(바닐라 아이템 그대로). **보라 큐브가 아니다.**"
          f" 팩 있는 눈 = 3D. 잃는 것 없음")
    print(f"  ├ 병기 45자루 = **3D 모델** (elements — 평면 스프라이트가 아니다)"
          f" · 등급이 형체로 갈린다 (고리 0~3 · 수실 · 마병 톱니)")
    print(f"  ├ 명병(名兵) {myeong}자루 — **문파의 얼굴** (실루엣이 지문에서 나온다: 점창=최박 ·"
          f" 종남=최중 · 남궁=최장 · 팽가=최광폭 · 화산=매화). 문양은 코등이·물미에만")
    print(f"  무공의 획 {qi}종 (3D 모션 — SkillDisplay 가 item_model 로 태운다. 길이축 +X)")
    print(f"  짐승의 형체 {mobs}종 (MobDisplay 가 본체를 감추고 태운다. 코가 +Z · 발이 원점)")
    print(f"  메뉴·버튼 {ui}장 (GUI 스프라이트 — mcmeta 미포함 = 바닐라 나인슬라이스·좌표 계약 그대로)")
    print(f"  소리 침묵 {muted}건 (배경음악 {len(MUTED_MUSIC)} + 동굴 앰비언스 {len(MUTED_AMBIENT)})"
          f" — 축음기(music_disc)는 남긴다. **혼천의 소리(.ogg)는 아직 0종** (청구서 참조)")
    print(f"  ├ 서장 사건음 {seojang_snd}채널 (honcheon:seojang.open/choose/result/debut —"
          f" .ogg 없는 채널은 바닐라 종이·책·종 계열로 재지향. SJ-002 · sound_channels.seojang)")
    print(f"  블록 징발 {blocks}장 (전역 치환 — block_channels.징발 등록분만)")
    print(f"  틴트층 {tints}장 (컬러맵 3 + 해·달 9) — ★ **세계의 초록은 컬러맵이 곱한다**. "
          f"텍스처만 칠하고 컬러맵을 두면 틴트가 도로 초록으로 물들인다")
    print(f"  ├ 애니메이션 {len(ANIMATED)}종 (물·용암·불·수초 — 프레임 시트 + mcmeta. 프레임 수는 바닐라 실측치)")
    if UNTINT_CLIP:
        # 역틴트 천장에 부딪힌 자리 — **조용히 어긋나지 않는다** (물의 R 은 63 을 넘을 수 없다)
        seen = sorted({(w, c, t) for w, c, _, t, _ in UNTINT_CLIP})
        print(f"  ├ ⚠ 역틴트 클램프 {len(seen)}건 — 틴트가 천장이다 (화면색은 틴트값을 못 넘는다): "
              + ", ".join(f"{w}.{c}(틴트 {t})" for w, c, t in seen[:6]))
    print(f"  획층(파티클) {parts}장 (무공 모션 — 엔진 불변. 팩 없으면 바닐라 파티클로 폴백)")
    print(f"  기물(블록 엔티티) {props}장 (항아리·궤 — 블록이되 텍스처는 entity/ 아래 산다)")
    print(f"  ★ 한옥 가구 3D — blockstate {furn_bs}표 + 블록 모델 {furn_models}종 (V2-F 신설 —"
          f" 술독·한약장·서안·시렁 5목재·절구·베틀·가마솥. 바닐라 전 variant 재정의 · 히트박스 불변"
          f" · 새 PNG 0장. chest·decorated_pot 은 블록엔티티라 모델 불가 — entity 텍스처까지)")
    print(f"  엔티티 징발 {ents}장 (전역 치환 — 사람 2 + 늑대 변종 27 + 고양잇과 2 + 곰·멧돼지·호랑이 3"
          f" + 마을 사람 27: 바탕 1 + 겉옷 7 + 생업 14 + 가슴패 5)")


