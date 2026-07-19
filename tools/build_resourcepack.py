#!/usr/bin/env python3
"""혼천 서버 리소스팩 컴파일러 — 결정론 생성 (맵과 같은 철학: 팩도 컴파일한다).

산출: resourcepack/ (팩 소스) — 기동 스크립트가 zip으로 묶는다.
글리프 (사설 영역 코드포인트, minecraft:default 폰트에 주입 — 채팅/액션바 어디서나 렌더):
  U+E000        기세 아이콘 (백색 — 채팅 색 코드로 틴트: 회/백/황/적)
  U+E010~E018   화후 게이지 0~8칸 (경락도 원장용)
                내력·원기 게이지는 같은 글리프를 색 틴트로 재사용 (슬롯 절약 — 설계 등록)
  U+E020~E027   경지 문장 8단: 삼류~생사경 (하위=획 수, 중위=봉우리/검, 상위=원환+중심)
  U+E030        비무 표식 — 교차 목검 + 묶은 띠 (Sparring 카운트다운. 2026-07-16 등록부 등재)
  U+E080        경락도 GUI 배경 (먹색 패널 + 전각 도장풍 모서리 + 제목 구분선 + 여백 가이드,
                인벤토리 제목 음수 공백 기법용)
  U+E0A0~E0A5   음수 공백 (space 프로바이더: -8/-16/-32/-64/-128/+1 — E080 제목 오프셋용)
  U+E0B0~E0B3   서장 기억첩 — 장 구분선·붓점·찍힌 인장·빈 인장 (SJ-002 · seojang_presentation.md
                §3.2·§4.1 이 이름 댄 4종만. E0B4~ 잔여 예약. glyph_slots.E0B0_E0BF 참조)
소리 (assets/honcheon/sounds.json — SJ-002): seojang.open/choose/result/debut 4채널.
  .ogg 가 없는 채널은 바닐라 이벤트로 재지향(type: event) — 종이·책·종 계열 폴백.
  실물이 오면 assets/honcheon/sounds/seojang/<채널>.ogg 를 놓기만 하면 된다 (등록·코드 변경 0).
바닐라 텍스처 교체 (1.21.11 / pack_format 75 — 화면 HUD·인벤토리 수묵 재해석):
  hud/heart/    container·full·half (+_blinking) 9x9 — 하트 대신 기혈 구슬 (주사+먹)
  hud/          hotbar 182x22 (먹 반투명+화선지 테두리), hotbar_selection 24x23 (주사 프레임)
  gui/container inventory·generic_54 256x256 — 화선지 재채색 (슬롯 18x18 좌표는 바닐라 계약 불변)
  hud/          crosshair 15x15 · air/air_bursting/air_empty 9x9 — B-036 신작 (2026-07-16)
  boss_bar/     blue·red x background·progress 182x5 — 내공/내력 실사용 두 색만 (energy_bossbar)
  ※ XP 바 기능·위치 불가침 (숨기기 금지) — 텍스처만 옥(玉)으로 재채색 (내공 막대, hud_channels)
아이템 채널 (docs/design/texture_layer_design.md §2 — item_model 컴포넌트, 전역 오염 0):
  honcheon/items/<key>.json         아이템 정의 (1.21.4) — item_model이 가리키는 곳
  honcheon/models/item/<key>.json   parent: handheld(무기) | generated(그 외)
  honcheon/textures/item/<key>.png  16x16 플레이스홀더 (2~3값 + 의미 강조)
  등급 = 베이스 바닐라 아이템(팩 게이트) / 계열 = model_key / 등급 표식 = 자루 고리 0~3 + 마병 혈적
블록 징발 (동 문서 §3 — 전역 치환. block_channels.징발에 등록된 것만):
  block/deepslate_tiles(+cracked)   흑와 — PNG 1장이 계단·반블록·담장 전부를 덮는다
  block/deepslate_bricks(+cracked)  흑와 직각 변형 — tiles의 90도 회전판 (동서 경사면용. ROOF_ISOTROPY)
  block/white|light_gray_terracotta 회벽 (무늬·이음선 금지 — 배들랜드 지층 무해화 조건)
  block/glass(+glass_pane_top)      격자창 / block/bamboo_planks 죽렴
  block/lantern·soul_lantern        등롱·백등롱 / block/chiseled_bookshelf_* 한약장 서랍
  painting/*                        족자 (1x1 소형 4종 — 수묵 산수·죽·서예·난)
의존성 없음 — 순수 표준 라이브러리 PNG 작성기.
"""

# ─── 기계 분할 【pack_upgrade_v1.md §2 0단계 · 2026-07-16】 ───
# 본체는 tools/respack/ 패키지로 갈라졌다 — 병렬 아트 트랙의 파일 소유권 분리가 목적이다:
#   core(공용 기반·경로/팔레트 상수·3D 규약) · gui(HUD·컨테이너·글리프·메뉴) ·
#   weapons(병기·명병 등록부) · items(지물·기물·재료) · dispatch(팩 게이트) ·
#   sounds(침묵·서장 사건음) · blocks(블록·틴트·프롭·파티클) · entities(엔티티 징발·짐승 형체) ·
#   qi(무공의 획) · __init__(main — 전 모듈을 원본과 같은 순서로 굽는다).
# 이 파일은 진입점으로 남는다 — CLI 인자·출력 문구·산출물 완전 동일 (분할 전후 트리 해시 대조로 검증).
from respack import main
from respack.weapons import MYEONGBYEONG   # 재수출 — tools/texture_audit.py 가 여기서 import 한다 (등록부는 하나다)

if __name__ == "__main__":
    main()
