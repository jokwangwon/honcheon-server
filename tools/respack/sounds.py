"""소리 — 침묵 채널 + 서장 사건음 4채널 (T4 소유)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (PACK, write_json)


# ═══════════════════════════════════════════════════════════════════════════
# 소리 — 침묵 채널. 【2026-07 신설】 0/470 의 첫 삽.
#
# 【정직한 벽】 .ogg 는 파이썬으로 만들 수 없다. 그러므로 **혼천의 소리는 아직 없다.**
#   그러나 **없애는 것은 지금 할 수 있고, 그것만으로도 세계가 조용해진다.**
#   조용한 강호가 마인크래프트 강호보다 낫다 — C418 이 흐르는 무당산은 무당산이 아니다.
#
# 【무엇을 끄는가】 sounds.json 에 "replace": true + 빈 sounds 목록 → 그 사건은 **소리를 잃는다.**
#   ① 배경음악 전부 (music.* — 95종, 1.21.11 서버 jar 레지스트리 실측).
#      ★ music_disc.* 는 **건드리지 않는다** — 축음기(주크박스)는 플레이어가 **스스로 켜는** 것이다.
#        세계가 멋대로 트는 소리와, 사람이 틀기로 한 소리는 다르다.
#   ② 동굴 앰비언스 (ambient.cave) — 동혈(洞穴)에서 나는 그 유명한 '누가 있다' 소리.
#
# 【무엇을 끄지 않는가 — 그리고 왜】
#   · 발소리·물소리·짐승 소리 — 끄면 세계가 **죽는다.** 이건 마인크래프트다워서가 아니라
#     **정보**다 (뒤에서 오는 발소리는 강호에서도 발소리다). 바꿀 것은 지우는 게 아니라 **굽는 것**이다.
#   · 무공의 소리 (block.conduit.activate 등) — 바닐라 키를 **다른 바닐라 키로 바꾸는 것**은
#     귀 없이 할 수 없다. 나는 소리를 들을 수 없다. 짐작으로 고르면 그건 검수가 아니라 도박이다.
#     ★ 진짜 답은 .ogg 를 굽는 것이다 — 청구서로 넘긴다 (resourcepack_design.yml sound_channels).
MUTED_MUSIC = [   # music.* — 1.21.11 server jar 레지스트리에서 실측 (짐작 아님)
    "music.creative", "music.credits", "music.dragon", "music.end", "music.game",
    "music.menu", "music.under_water",
    "music.game.a_familiar_room", "music.game.an_ordinary_day", "music.game.ancestry",
    "music.game.below_and_above", "music.game.broken_clocks", "music.game.bromeliad",
    "music.game.clark", "music.game.comforting_memories", "music.game.crescent_dunes",
    "music.game.danny", "music.game.deeper", "music.game.dry_hands",
    "music.game.echo_in_the_wind", "music.game.eld_unknown", "music.game.endless",
    "music.game.featherfall", "music.game.fireflies", "music.game.floating_dream",
    "music.game.haggstrom", "music.game.infinite_amethyst", "music.game.key",
    "music.game.komorebi", "music.game.left_to_bloom", "music.game.lilypad",
    "music.game.living_mice", "music.game.mice_on_venus", "music.game.minecraft",
    "music.game.one_more_day", "music.game.os_piano", "music.game.oxygene",
    "music.game.pokopoko", "music.game.puzzlebox", "music.game.stand_tall",
    "music.game.subwoofer_lullaby", "music.game.sweden", "music.game.watcher",
    "music.game.wending", "music.game.wet_hands", "music.game.yakusoku",
    "music.game.creative.aria_math", "music.game.creative.biome_fest",
    "music.game.creative.blind_spots", "music.game.creative.dreiton",
    "music.game.creative.haunt_muskie", "music.game.creative.taswell",
    "music.game.end.alpha", "music.game.end.boss", "music.game.end.the_end",
    "music.game.nether.ballad_of_the_cats", "music.game.nether.concrete_halls",
    "music.game.nether.crimson_forest.chrysopoeia", "music.game.nether.dead_voxel",
    "music.game.nether.nether_wastes.rubedo", "music.game.nether.soulsand_valley.so_below",
    "music.game.nether.warmth",
    "music.game.swamp.aerie", "music.game.swamp.firebugs", "music.game.swamp.labyrinthine",
    "music.game.water.axolotl", "music.game.water.dragon_fish", "music.game.water.shuniji",
    "music.menu.beginning_2", "music.menu.floating_trees", "music.menu.moog_city_2",
    "music.menu.mutation",
    "music.nether.basalt_deltas", "music.nether.crimson_forest", "music.nether.nether_wastes",
    "music.nether.soul_sand_valley", "music.nether.warped_forest",
    "music.overworld.badlands", "music.overworld.bamboo_jungle", "music.overworld.cherry_grove",
    "music.overworld.deep_dark", "music.overworld.desert", "music.overworld.dripstone_caves",
    "music.overworld.flower_forest", "music.overworld.forest", "music.overworld.frozen_peaks",
    "music.overworld.grove", "music.overworld.jagged_peaks", "music.overworld.jungle",
    "music.overworld.lush_caves", "music.overworld.meadow", "music.overworld.old_growth_taiga",
    "music.overworld.snowy_slopes", "music.overworld.sparse_jungle",
    "music.overworld.stony_peaks", "music.overworld.swamp",
]
MUTED_AMBIENT = ["ambient.cave"]   # 동혈의 '누가 있다' 소리 — 강호에 그 소리는 없다


def write_sounds() -> int:
    """assets/minecraft/sounds.json — 끄는 것만으로 세계가 조용해진다 (굽는 것은 아직 못 한다)."""
    muted = MUTED_MUSIC + MUTED_AMBIENT
    doc = {event: {"replace": True, "sounds": []} for event in sorted(muted)}
    write_json(PACK / "assets" / "minecraft" / "sounds.json", doc)
    return len(doc)


# ─── 서장 사건음 4채널 【SJ-002】 — 등록부: config/resourcepack_design.yml sound_channels.seojang ───
#
# 【.ogg 없이 어떻게 사는가】 sounds.json 은 한 이벤트를 **다른 바닐라 이벤트로 재지향**할 수 있다
#   ({"type": "event"} — 파일 경로 짐작이 아니라 이벤트 이름이라 낡을 수 없다). 그래서
#   honcheon:seojang.* 는 지금도 난다: 등록된 바닐라 종이·책·종 계열로
#   (seojang_presentation.md §4.2 "음원 파일이 없을 때는 등록된 바닐라 종이·책·종 계열 소리로 폴백").
# 【실물이 오면】 assets/honcheon/sounds/seojang/<채널>.ogg 를 놓기만 하면 이 빌더가 재지향 대신
#   실물을 등록한다 — 등록부도 서버 코드도 안 바뀐다 (config/seojang.yml presentation.sounds 의
#   key 는 이벤트 이름이지 파일이 아니므로).
# ★ 이 표의 폴백은 config/seojang.yml presentation.sounds 의 fallback,
#   resourcepack_design.yml sound_channels.seojang 의 fallback_event 와 **같아야 한다**
#   (한 사건 = 한 소리 — 팩 있는 귀와 없는 귀가 같은 것을 듣는다).
SEOJANG_SOUND_FALLBACK = {   # 채널 → 바닐라 폴백 이벤트 (전부 1.21 실존 이벤트 — 종이·책·종 계열)
    "open": "item.book.page_turn",      # 장 도착 — 책장 넘김 (종이와 붓의 시작)
    "choose": "item.book.put",          # 입력 접수 — 책을 놓는 마른 소리 (낙관)
    "result": "block.note_block.bell",  # 판정 확정 — 등급별 음높이는 서버가 pitch 로 변주한다
    "debut": "block.bell.use",          # 출도 — 짧은 종
}


def write_seojang_sounds() -> int:
    """assets/honcheon/sounds.json — 서장 사건음 4채널 (실물 .ogg 우선, 없으면 바닐라 재지향).

    【자동 전환 — T4】 채널마다 따로 판단한다: .ogg 가 실존하면 그 파일을, 없으면 바닐라
    이벤트 재지향을 등록한다. .ogg 를 만드는 손은 tools/sound_forge.py (결정론 합성 —
    --install 이 채운다) — 팩은 .ogg 0장에서도 4장에서도 깨지지 않는다.

    【청구서 대역 — sound_channels.bill (약 30종)】 seojang/ 밖의
    assets/honcheon/sounds/<묶음>/<이름>.ogg 는 실존하는 것만 honcheon:<묶음>.<이름> 으로
    자동 등재한다 (정렬 순회 — 결정론). 파일이 없으면 이벤트도 없다: 폴백 계약이 등록된
    것은 서장 4채널뿐이라, 나머지는 소비자(skill_motion.yml 등)가 실물이 생긴 뒤에야
    honcheon:* 키로 갈아탄다 (등록부 note: "코드 변경 0" 경로)."""
    snd_root = PACK / "assets" / "honcheon" / "sounds"
    doc = {}
    for channel, vanilla in SEOJANG_SOUND_FALLBACK.items():
        ogg = snd_root / "seojang" / f"{channel}.ogg"
        doc[f"seojang.{channel}"] = {"sounds": (
            [f"honcheon:seojang/{channel}"] if ogg.is_file()
            else [{"name": vanilla, "type": "event"}]
        )}
    if snd_root.is_dir():   # 청구서 .ogg — 실물이 놓인 것만 (seojang/ 은 위 표가 소유한다)
        for ogg in sorted(snd_root.rglob("*.ogg")):
            rel = ogg.relative_to(snd_root).with_suffix("")
            if rel.parts[0] == "seojang":
                continue
            doc[".".join(rel.parts)] = {"sounds": ["honcheon:" + "/".join(rel.parts)]}
    write_json(PACK / "assets" / "honcheon" / "sounds.json", doc)
    return len(doc)

