"""팩 게이트 — 바닐라 베이스 select 분기 (custom_model_data)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (PACK, write_json)
from .weapons import (MYEONG_BASE, WEAPON_SERIES, _GRADE_FORM)



# ═══════════════════════════════════════════════════════════════════════════
# 팩 게이트 — 바닐라 베이스 분기 (assets/minecraft/items/<base>.json)
#
# 【왜 item_model 을 버렸나】
#   1.21.4+ 는 minecraft:item_model 이 가리키는 정의가 **없으면 바탕 아이템으로 폴백하지 않는다** —
#   없는 모델(보라 큐브)을 그린다. 그런데 ItemStack 은 **모두에게 같은 바이트**다: 서버는 팩을 받은
#   눈과 거절한 눈에게 다른 아이템을 보낼 수 없다. 그러므로 실물 아이템에 item_model 을 박는 순간
#   **팩을 거절한 사람의 손에 보라 큐브가 쥐어진다.** 팩 게이트 불가침(require-resource-pack=false)의
#   위반이고, 등록부의 '팩없음' 열(전표 = "종이 한 장")은 거짓말이 된다.
#
# 【그래서 custom_model_data 로 옮긴다】
#   Weapons/Goods 는 minecraft:custom_model_data.strings[0] 에 키를 담는다 (예: honcheon:coin/jeonpyo).
#   · 팩 없는 눈 — 이 컴포넌트는 **불활성**이다. 바닐라는 select 분기를 모른다. 플레이어는 바탕
#     바닐라 아이템을 그대로 본다: 전표 = 종이, 영약 = 마그마 크림, 정련검 = 철검.
#     **등급은 재질 색이 말한다** (돌·철·다이아·금·네더라이트) — 등록부가 처음부터 그렇게 설계했다.
#   · 팩 있는 눈 — 이 파일의 select 가 strings[0] 을 읽어 3D 를 문다. 잃는 것이 없다.
#   · 컴포넌트 없는 바닐라 철검·종이·가죽 — **fallback 이 바닐라 모델이므로 그대로다** (전역 오염 0).
#
# 【마병만 2단】 strings[1] = unidentified | revealed. 미감정은 평범한 정련 병기로 보인다.
#
# ★ 보류 기물 3종(청낭·호신부·천잠사_요대)은 여기 **없다.** 베이스가 bundle·name_tag·lead 인데
#   bundle 의 바닐라 아이템 정의는 평범한 모델이 아니다 (열림/닫힘 + 선택 아이템 분기를 담은
#   합성 정의다). 그것을 flat select 로 덮으면 **세계의 모든 번들이 고장난다.** 지급 접합 때
#   베이스를 다시 고르라 — 이것은 팩이 아니라 등록부가 풀 문제다 (resourcepack_design.yml 에 적었다).
# ═══════════════════════════════════════════════════════════════════════════
VANILLA_ITEM_DIR = PACK / "assets" / "minecraft" / "items"

# 계열 → 바닐라 도구 (Weapons.java Series.base — 18반 병기는 바닐라 도구를 징발했다)
SERIES_TOOL = {
    "sword": "sword", "dao": "sword", "spear": "sword", "gauntlet": "sword", "dagger": "sword",
    "bu": "axe", "gyeom": "hoe", "wolasan": "shovel", "gu": "pickaxe",
}
# 등급 → 바닐라 재질 (Weapons.java Base.byGrade — **팩 없이도 이 색이 등급이다**)
GRADE_MATERIAL = {
    "beomcheol": "stone", "jeongryeon": "iron", "bobyeong": "diamond",
    "sinbyeong": "golden", "mabyeong": "netherite",
}
GOODS_BASE = {
    "tome/manual_original": "written_book",
    "tome/manual_copy": "book",
    "tome/gugyeol": "writable_book",
    "coin/jeonpyo": "paper",
    "pill/yeongyak": "magma_cream",
    "cargo/pyomul": "chest_minecart",
    "trinket/pidokju": "heart_of_the_sea",
    "trinket/yamyeongju": "nether_star",
    "pelt/wolf": "leather",
    "pelt/fox": "leather",
    "pelt/tiger": "leather",
    "spoil/ungdam": "slime_ball",
    "herb/yakjae": "wheat",
}


def _model_ref(ref: str):
    return {"type": "minecraft:model", "model": ref}


def write_vanilla_dispatch() -> int:
    """바닐라 베이스마다 select 분기 1장 — 팩 없는 눈은 fallback(바닐라)을 본다."""
    cases: dict[str, list] = {}

    def add(base_item: str, key: str, node):
        cases.setdefault(base_item, []).append({"when": f"honcheon:{key}", "model": node})

    # ─── 병기 45 (계열 9 × 등급 5) ───
    for series in WEAPON_SERIES:
        tool = SERIES_TOOL[series]
        for grade in _GRADE_FORM:
            key = f"weapon/{series}_{grade}"
            if grade == "mabyeong":
                # 마병 = 상태 변주 (strings[1]). 감정 전엔 정체가 보이면 안 된다 —
                # 기연의 탈을 쓴 저주가 첫눈에 보이면 저주가 아니다.
                node = {
                    "type": "minecraft:select",
                    "property": "minecraft:custom_model_data",
                    "index": 1,
                    "cases": [{"when": "revealed",
                               "model": _model_ref(f"honcheon:item/weapon/{series}_mabyeong")}],
                    "fallback": _model_ref(f"honcheon:item/weapon/{series}_jeongryeon"),
                }
            else:
                node = _model_ref(f"honcheon:item/{key}")   # key 가 이미 'weapon/…' 을 물고 있다
            add(f"{GRADE_MATERIAL[grade]}_{tool}", key, node)

    # ─── 명병 8 — 등급은 무엇이든 될 수 있다 (범철 매화검도 매화검이다) → 5재질 전부에 얹는다 ───
    for sect, series in MYEONG_BASE.items():
        tool = SERIES_TOOL[series]
        for grade in _GRADE_FORM:
            add(f"{GRADE_MATERIAL[grade]}_{tool}", f"weapon/myeong/{sect}",
                _model_ref(f"honcheon:item/weapon/myeong/{sect}"))

    # ─── 지물·기물·재료 ───
    for key, base in GOODS_BASE.items():
        add(base, key, _model_ref(f"honcheon:item/{key}"))

    for base, cs in sorted(cases.items()):
        write_json(VANILLA_ITEM_DIR / f"{base}.json", {
            "model": {
                "type": "minecraft:select",
                "property": "minecraft:custom_model_data",
                "index": 0,
                # 결정론 — 같은 입력에 같은 바이트 (사전순 고정)
                "cases": sorted(cs, key=lambda c: c["when"]),
                "fallback": _model_ref(f"minecraft:item/{base}"),
            },
        })
    return len(cases)


