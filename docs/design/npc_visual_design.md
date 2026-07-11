# NPC 비주얼 디자인 — 명패·주민·승격·연출 규정

> 배경: MVT의 NPC는 이름 명패만 단 주민(Villager, AI off)이다 — "NPC 디자인 개선 필요" 피드백.
> 원칙: 등록제 명사(신규 이름 발명 금지) · 디자인 토큰 단일 원천(`config/resourcepack_design.yml`)
> · 무협 수묵 기조("정보가 장식보다 먼저") 위에서, NPC의 '보이는 것'을 규정한다.
> 기준 데이터: `config/npcs/cheongha_npcs.yml`(등록 21명) / `docs/design/npc_lifecycle.md`(일과·생애)
> / `docs/design/npc_dialogue_interaction.md`(웹훅 페르소나) / `docs/design/resourcepack_design.md`(글리프 슬롯)

---

## 0. 원칙 — NPC 비주얼도 등록제다

```text
등록제 명사   비주얼 자산의 키 = cheongha_npcs.yml의 npc_id (hanbaek, muksam …) — 파일명·발주서·코드가 전부 이 키를 쓴다
토큰 단일 원천 색·글리프·크기는 resourcepack_design.yml 토큰만 참조 — 본 문서의 표는 승격 시 config/npc_visual.yml로 이관 가능한 형태로 쓴다
수묵 기조     기본은 먹과 한지의 저채도, 채도는 의미(세력·태세)에만 — NPC가 화면에서 시끄러우면 안 된다
관측 가능한 사실만 명패·연출은 플레이어가 '알 수 있는 것'만 보여준다 — secrets(묵삼의 정체)는 비주얼로도 새지 않는다
교체형        스펙(이 문서)은 계약, 아트(스킨·초상 PNG)는 교체형 — 플레이스홀더로 시작해 커미션으로 승격 (코드 불변)
```

## 1. 명패 문법 — 이름표가 곧 정보 계층이다

### 1.1 명패 구성 문법

```text
명패 = [세력 문장 글리프][경지 문장 글리프] 역할 이름
       └ M3 (E060 대역)  └ 무인만 (E020~E027)  └ MVT 현행 유지 ("객잔 주인 한백")
색   = 이름·역할 전체를 세력 색 1색으로 (1.3) — 색 단독 금지 규칙은 '역할 텍스트'가 문자 채널로 충족
구현  = CustomName을 Adventure Component로 조립. PUA 글리프는 F26 준수 — 소스에 리터럴 금지, chr()/\uXXXX만
검증  = PUA 글리프가 엔티티 명패에서 렌더되려면 리소스팩 default 폰트에 등록돼 있어야 한다 (배선 확인 항목, 6장)
```

### 1.2 경지 문장 글리프 — 무인만, 공개 경지만

E020~E027(경지 문장 8단, 삼류~생사경)을 명패 접두로 재사용한다. 신규 슬롯 불요.

```text
표기 조건 (셋 다 만족할 때만):
① realm 필드 보유 (무인이다)          — 유문·백석 같은 민간인은 경지 문장 없음
② 표면 신분이 무인이다               — 위장 NPC(묵삼)는 realm이 있어도 표기 금지 (관측 가능한 사실만)
③ tier 3 이상                       — 졸개(템플릿)는 경지 문장 없이 이름·기세로만 읽힌다
```

| realm | 글리프 | 청하현 해당 NPC (npc_id) |
|---|---|---|
| 삼류 | U+E020 | masam (north_road_bandit는 tier 1 — 표기 제외) |
| 이류 | U+E021 | galho, jinun, bakho (heukrang·muksam은 은닉 — 표기 제외) |
| 일류 | U+E022 | jincheolsan, hyegak |
| 절정 | U+E023 | (청하현 현재 없음) |
| 초절정 | U+E024 | (없음 — 고수 총량 상한, npc_lifecycle 2.6) |
| 화경 | U+E025 | (없음) |
| 현경 | U+E026 | (없음) |
| 생사경 | U+E027 | (없음) |

### 1.3 세력 → 명패 색 대응표 (기계 판독용 — config 이관 대상)

MC 색 코드(틴트 팔레트가 곧 규칙)로 지정. **red·yellow는 명패에 사용 금지** — 기세 4색(1.5)과의
오독을 막는다. 개별 세력이 아니라 **factions.yml 5계열** 단위로 색을 배정한다 (색 인플레 방지).

| 계열 | 등록 세력 id (cheongha_npcs.yml 출현분) | MC 색 코드 | 의미 |
|---|---|---|---|
| 정파 | orthodox, orthodox_heroes | `aqua` | 청(靑) — 정도 |
| 사파/흑도 | haomun, noklim | `dark_purple` | 자(紫) — 음지 (단, 은닉 규칙 1.4가 우선) |
| 사교/금기 | (hidden — magyo, hyeolgyo) | `dark_red` | 혈(血) — **명패 노출 금지.** 드러나는 순간이 연출이다 |
| 질서/행정 | gwanchung, gwangun | `gold` | 황(黃) — 관인 |
| 경제 | sangdan, jeongboSang, pyoguk | `green` | 전(錢) — 상로 |
| 민간 | mingan | `white` | 백(白) — 백성 |
| 불가 | bulga | `aqua` (정파 계열 준용) | 구파(소림) 계열 준용 — 신규 색 발명 금지 |

정합 메모: `gwanchung`·`gwangun`·`pyoguk`·`bulga`·`jeongboSang`은 factions.yml에 동일 id가
없다 (hyeollyeong / gwan_gun / jeongbosang 표기 상이, pyoguk·bulga 미등록). 색 배정은 위 계열
매핑으로 동작하지만 id 정합은 후속 배선(6장) 항목이다.

### 1.4 tier별 표기 차등 + 은닉 규칙

| tier | 표기 | 예 |
|---|---|---|
| 1 배경 | 직능명만, `gray` — 개체 이름 없음 (관측이 개성을 만들기 전) | "산길 도적" |
| 2 기능 | 역할+이름, `white` (세력색 미부여 — 승격 전) | "점소이 소칠" |
| 3 관계 | 역할+이름 세력색 + 경지 문장(1.2 조건 충족 시) | "표국주 진철산" (green) |
| 4 핵심 | tier 3 + 세력 문장 글리프(E060 대역, M3) | "\uE06x현령 조문원" (gold) |
| 5 세력 대표 | tier 4 + 별호 장식(E028+) | (청하현 현재 없음) |

```text
은닉 규칙 — 명패는 표면 신분을 따른다:
muksam    표기: "투숙객 묵삼" white (mingan 위장) — haomun 색·경지 문장 금지. 정체 해금 후에도 명패는 불변
          (명패는 세계의 공개 정보 — 아는 건 플레이어의 몫)
heukrang  평시 비스폰 (접선 밤에만 등장, 2.1) — 등장 시 "사내" gray, 이름 없음. 정체는 대화·조사로만
galho/졸개 적대 조우 전용 — 평시 마을 스폰 없음
```

### 1.5 기세 4색과의 관계 — 절대 정보와 상대 정보를 분리한다

```text
명패(경지 문장) = 절대·공개 정보 — 모든 관측자에게 동일. "표국주는 일류다"는 세간의 평판이다
기세(E000 4색)  = 상대·감각 정보 — 회(압도적 하수)/백(하수)/황(동수)/적(상수)은 '나 기준' 강약.
                조우·전투 HUD(액션바) 전용이며 명패에 절대 싣지 않는다
분리 이유       같은 NPC가 플레이어마다 다른 색이면 명패는 거짓말이 된다. 명패 금지색(red·yellow)도 이 분리의 방어선
교차 연출       기세 읽기(마삼 = "기세 읽기의 교보재")는 명패가 아니라 조우 시 액션바 한 줄로 — 즉시성 문법(interface) 그대로
```

## 2. 주민 기반 표현 (MVT~M2) — Villager로 최대한 말하기

M3 전까지 NPC의 몸은 Villager다. 장비를 못 입히는 대신 **직업 복장 + 정위치 소품 + 명패**의
세 채널로 역할을 읽게 한다.

### 2.1 역할 → Villager.Profession 대응표 — 등록 21명 전원 (기계 판독용)

배정 규칙 (개별 예외보다 규칙이 먼저):

```text
무인(민간·문파·표국) = WEAPONSMITH / 관군 무관 = ARMORER / 의·약·승려 = CLERIC
행정·글 = LIBRARIAN / 장부·문서·의뢰 = CARTOGRAPHER / 장인 = TOOLSMITH / 채집·행상 = FARMER
위장·무직·유랑 = NONE
금지: NITWIT(경멸적 함의 + 녹색 로브 오독), 아기 주민(연령 왜곡), 직업 혼용(같은 역할 = 같은 직업)
공통: Villager Type = PLAINS 통일 (청하현 온대 — 지역 확장 시 지역별 재론), AI off라 작업 블록 POI 간섭 없음
```

| npc_id | 이름 | tier | 역할 | Profession | 정위치 소품 / 비고 |
|---|---|---|---|---|---|
| hanbaek | 한백 | 3 | 청하객잔 주인 | BUTCHER | 카운터 뒤, 술통·화로 |
| muksam | 묵삼 | 4 | (표면) 투숙객 | NONE | 위장 — 표면 신분 표기 (1.4) |
| gwakjin | 곽진 | 3 | 상단 호위무사 | WEAPONSMITH | 무기 거치대 |
| yumun | 유문 | 3 | 의원 | CLERIC | 약탕(양조기)·약재 선반 |
| soyeon | 소연 | 3 | 의뢰소 관리자 | CARTOGRAPHER | 게시판(액자)·제도대 |
| heukrang | 흑랑 | 3 | 하오문 접선자 | NONE | 평시 비스폰 — 접선 밤에만 (1.4) |
| galho | 갈호 | 3 | 도적 두목 | WEAPONSMITH | 적대 조우 전용 — Pillager 계열 대체 허용* |
| north_road_bandit | 산길 도적 (졸개) | 1 | 도적 템플릿 | NONE | Pillager 계열 대체 허용* |
| jinun | 진운 | 3 | 화산파 외문 제자 | WEAPONSMITH | 검 소품(액자) |
| jomunwon | 조문원 | 4 | 현령 | LIBRARIAN | 독서대·서가 |
| bakho | 박호 | 3 | 포두 | ARMORER | 무기 거치대(참마도) |
| yacheolsu | 야철수 | 3 | 대장장이 | TOOLSMITH | 화덕·모루 (실블록 — 철방 자체가 소품) |
| geumseobang | 금서방 | 3 | 전장 지점주 | CARTOGRAPHER | 장부 독서대 |
| sochil | 소칠 | 2 | 점소이 | NONE | 객잔 홀 순회 지점 (4.2) |
| jincheolsan | 진철산 | 3 | 표국주 | WEAPONSMITH | 표기(標旗) 깃발 |
| hyegak | 혜각 | 3 | 승려 | CLERIC | 종·향로(양초) |
| baekseok | 백석 | 2 | 서당 훈장 | LIBRARIAN | 독서대 |
| masam | 마삼 | 2 | 왈패 두목 | NONE | 저자 골목 어귀 |
| heodaein | 허대인 | 3 | 염상 | FARMER | 소금 가마니·수레 |
| naengwol | 냉월 | 3 | 유랑 악사 | NONE | 노트블록 — 공연 연출(4.4) |
| gombo | 곰보영감 | 2 | 약초꾼 | FARMER | 건조 약초·화분 |

\* 적대 NPC 대체는 엔티티 타입 변경일 뿐 명사 발명이 아니다 — 명패·시트는 동일 npc_id.

### 2.2 장비 연출 불가의 대안 — 다섯 채널

Villager는 armor/handItem 렌더가 안 된다. 대신:

```text
① 직업 복장    위 Profession 표 — 무료로 얻는 유일한 '옷'
② 정위치 소품   NPC 좌표 곁의 블록 연출 (위 표 소품 열) — 장비를 '몸'이 아니라 '자리'가 입는다
              소품도 등록제: 표에 없는 소품 추가 = 본 표 갱신 커밋
③ 명패 텍스트   역할 접두가 직능을 문자로 보증 (colorblind rule의 문자 채널)
④ 파티클·사운드 감정·활동 연출 (4.4) — 냉월의 NOTE가 비파를 대신한다
⑤ ItemDisplay  M2 옵션: 손 소품(찻잔·비파·붓)을 ItemDisplay 엔티티로 NPC 좌표에 부착
              — 스킨 없이 '든 것'을 보여주는 중간 단계. 슬롯·좌표 오프셋은 도입 시 본 문서에 등록
```

## 3. M3 승격 경로 — 주민에서 '사람'으로

### 3.1 기술 비교와 권장

플레이어형 NPC(스킨 복식)를 구현하는 세 갈래:

| 기준 | Citizens2 (플러그인) | ProtocolLib/PacketEvents 자작 가짜 플레이어 | Paper API 실체 엔티티 (주민 유지·개량) |
|---|---|---|---|
| 무협 복식 스킨 | ◎ 내장 (스킨 API 연동) | ○ GameProfile 패킷 직접 조립 | ✕ 불가 — 주민 한계 그대로 |
| 유지보수 | 커뮤니티가 MC 버전업 흡수 | 매 버전 패킷 회귀 추적 = 자체 부담 | ◎ 최저 (공식 API만) |
| lookAt·경로 이동 | ◎ LookClose·Waypoints 내장 (4장 요구와 정합) | △ 전부 자작 | △ 자작 (4장 규정으로 가능) |
| 명패·PUA 글리프 | ○ (홀로그램/명패 트레이트) | ○ TextDisplay 병용 | ◎ 현행 그대로 |
| 서버 부하 | 낮음 | 최저 (엔티티 틱 없음) | 낮음 |
| 리스크 | 외부 플러그인 의존 | 개발비 최고 — 1인 규모 부적합 | 표현 한계 (복식 요구 미충족) |

```text
권장: Citizens2 채택 + 어댑터 격리
근거 ① 요구 기능(스킨·lookAt·waypoint 이동)이 전부 내장 — 4장 연출 규정이 자작 없이 성립
    ② 자작 패킷 NPC는 성능은 최고지만 버전업마다 회귀 비용 — "아트는 게임플레이를 막지 않는다"의 코드판 위반
    ③ Paper 단독은 M3의 핵심 요구(복식)를 못 채운다
격리 규칙: 코드는 NpcVisual 어댑터 인터페이스(spawn/명패/lookAt/moveTo)만 호출 — Citizens 직접 의존 금지.
         스펙은 계약, 구현은 교체형 (resourcepack_design decision_process의 코드판)
승격 대상: tier 3+ 개체만 플레이어형 전환. tier 1~2와 템플릿은 주민/Pillager 유지 — 승격도 등록제
```

### 3.2 무협 복식 스킨 스펙 — 세력별 복식 코드 (기계 판독용)

```text
규격    64x64 PNG, classic(4px 팔) 통일 — 한복·도포의 넓은 소매는 slim에서 붕괴한다
기조    수묵 저채도 — 먹(흑갈)·한지(회백) 기본, 채도는 세력 포인트 1개소(깃·띠·흉배)에만
정합    포인트 색상은 명패 색(1.3)과 같은 계열로 — 명패와 옷이 같은 말을 해야 한다
얼굴    8x8 얼굴 영역에서 개체 식별 가능해야 함 (수용 기준 "1초 가독"의 스킨판)
금지    바닐라 해상도 초과(64x64 고정), 채도 2개소 이상, 문파 상징 무단 신설 (문장은 E060 대역 등록분만)
```

| 복식 코드 | 대상 세력 (계열) | 기조 배색 | 포인트 hex (제안 토큰) | 포인트 요소 |
|---|---|---|---|---|
| bok_jeongpa | orthodox, orthodox_heroes | 회백 도포 | `#3E6B8C` (저채도 청) | 깃·검대 |
| bok_sapa | haomun, noklim | 흑갈 단삼 | `#5C4470` (저채도 자) | 허리띠 |
| bok_gwan | gwanchung, gwangun | 현색 관복 | `#B08A36` (저채도 황) | 관모·흉배 |
| bok_sang | sangdan, jeongboSang, pyoguk | 다갈 장삼 | `#4E6B4E` (저채도 녹) | 전대(錢帶) |
| bok_min | mingan | 갈백 마의 | 없음 (무채) | — |
| bok_bulga | bulga | 회 승복 | `#8C7549` (저채도 갈) — 가사 한정 예외색 | 가사 |

위 hex는 스킨용 제안 토큰(MC 틴트 색은 스킨에 과채도) — 확정 시 config/npc_visual.yml로 이관.

### 3.3 커미션 발주서 형식

슬롯 표 + 기준 문서가 그대로 발주서가 된다는 원칙(resourcepack_design)의 NPC판. 발주 1건 = 표 1행.

| 필드 | 내용 | 예 (hanbaek) |
|---|---|---|
| npc_id / 이름 | yml 키 그대로 — 납품 파일명의 원천 | hanbaek / 한백 |
| tier / 우선순위 | 4 → 3 → 2 순 발주 | 3 / 2차 |
| 역할 · 성격 발췌 | role + disposition 요약 2줄 이내 | 객잔 주인 — 보호 성향, 낯선 이 경계 |
| 복식 코드 | 3.2 표의 코드 | bok_min |
| 체형 · 연령 | 시트 stats·notes에서 유추해 지정 | 중년, 다부진 체격 |
| 납품물 | ① 스킨 64x64 PNG(classic) ② 초상 512x512 PNG(5.1 스펙) | 2점 1조 |
| 파일명 | `skins/<npc_id>.png`, `portraits/<npc_id>.png` | skins/hanbaek.png |
| 수용 기준 | acceptance(1초 가독·바닐라 이질감 없음) + 8x8 얼굴 식별 + 포인트 1개소 규율 | — |

## 4. 행동 연출 — AI off의 한계와 대안

AI off는 의도된 선택이다(배회·직업 갱신·POI 간섭 차단). 문제는 '멈춘 세계'로 보인다는 것.
해법은 AI를 켜는 게 아니라 **세 가지 통제된 움직임**만 허가하는 것이다.

### 4.1 한계의 명시

```text
AI off = 배회 없음, 경로 탐색 없음, 시선 추적 없음, 회피 없음 (setInvulnerable로 상쇄)
허가되는 움직임: ① 일과 재배치(4.2) ② 시선(4.3) ③ 파티클·사운드(4.4) — 이 밖의 움직임 금지
```

### 4.2 일과 5구간 스케줄 연동 위치 이동

NPC 위치의 원천은 schedule(npc_lifecycle 1장 — "낮의 한백은 카운터에, 밤의 묵삼은 창고에").
구간 전환 시 이동 규정:

```text
MVT   정적 유지 (현행). 단, schedule 보유 NPC의 '구간별 좌표 프리셋'만 먼저 정의해 둔다
      — 대화 시작 전 schedule 조회(npc_dialogue precondition)와 좌표가 어긋나지 않게

M2    구간 전환(time.yml 5구간) 시:
      관측자 없음 (반경 48블록 내 플레이어 0 또는 청크 언로드) → 프리셋 좌표로 재배치 (텔레포트 허용)
      관측자 있음 → 등급 3+는 경로 이동: AI 일시 on → Paper Pathfinder.moveTo(목적지, 속도 0.5)
                  → 도착 시 AI off 복귀. 60초 미도달 시 관측자 이탈 후 재배치 폴백
      근거: "이동이 목격 가능해야 미행이 성립한다 — 텔레포트 금지"(npc_lifecycle)는 관측자 앞에서만
           지불하면 되는 비용이다. 배경 NPC(1~2)는 언제나 프리셋 재배치 (동 문서 규정 그대로)

이동 자체가 단서   스케줄 이탈(오버라이드)은 시각 신호를 따로 내지 않는다 — "묵삼이 낮에 나갔다?"는
                플레이어가 평소 위치를 기억해서 알아채는 것이다 (관측이 만드는 정보를 UI가 스포일하지 않는다)
```

### 4.3 접근 시 lookAt — 시선 하나로 '살아 있음'을 산다

```text
트리거   반경 5블록 (대화 시작 반경과 같은 토큰 — npc_dialogue mc.start.radius_blocks 재사용)
대상    최근접 플레이어 1인. 대화 잠금 중엔 대화 상대 고정 (동시 대화 중재와 정합)
구현    Entity#setRotation (AI off에서도 동작) — 10틱 주기 갱신, 수평 회전만 (몸 전체 — 목 꺾기 금지)
예외    access 폐쇄 구간(취침·칩거)엔 무반응 — 일과가 시선도 정한다. 잠입자에게 시선을 주면 경계도 -2가 거짓말이 된다
이탈    반경 이탈 10초 후 기본 방향(소품 쪽) 복귀
```

### 4.4 감정 파티클 규정 — 등록 4종, 순간에만

파티클은 세계의 몫이되 조용하게 — **상태 지속 발산 금지, 태세 '전환의 순간' 1회만.**
채팅의 태세 색(백/녹/적 — npc_dialogue)과 이중 표기가 되어 색약 규칙도 충족한다.

| 감정 | 파티클 (Bukkit) | 트리거 (엔진 이벤트) | 수량 |
|---|---|---|---|
| 호감·성사 | HAPPY_VILLAGER | 태세 우호 전환, favor 상향, 거래 성사 | 5개 1회 |
| 분노·적대 | ANGRY_VILLAGER | 태세 적대 전환, 무례 개입(-1의 순간) | 3개 1회 |
| 경계·의심 | SMOKE | 의심도 +1 기록 시 | 소량 1회 |
| 연주 (예외) | NOTE | 냉월 공연 활동 구간 한정 — 유일한 지속 허용 (2초 간격) | 1개/2초 |

이 표 밖의 파티클 사용 금지. 추가 = 본 표 갱신 커밋 (등록제).

## 5. 봇 쪽 대응 — 웹훅 페르소나 아바타 규정

디스코드에서 NPC는 웹훅 페르소나(이름 + 초상 아바타)로 발화한다(npc_dialogue.yml). 초상이
곧 NPC의 '얼굴'이므로 MC 스킨과 같은 세계관 규율을 받는다.

### 5.1 수묵 초상 스펙

```text
규격     512x512 PNG (디스코드 축소 대응 원본), 원형 크롭 안전영역 = 중앙 지름 70% 안에 얼굴 배치
가독 기준  40px 원형(실표시 크기)에서 개체 식별 가능 — "1초 가독"의 아바타판. 발주 수용 검사 항목
양식     수묵 흉상 — 한지 바탕(저채도 회백) + 먹선 위주. 배경 장식 금지 (인물만)
채도     세력 포인트 1개소(깃·띠·관모)만 — 3.2 복식 코드의 포인트 hex와 동일 토큰 (스킨과 초상이 같은 옷)
표면 신분  초상도 관측 가능한 사실만 — 묵삼의 초상은 '투숙객'의 얼굴이다 (복식 bok_min). 정체 연출은 서사의 몫
플레이스홀더 먹 실루엣 + 세력 포인트 띠 1줄 — 자체 제작 허용 (MVT 수준). 승격 = PNG 교체 (봇 코드 불변)
```

### 5.2 파일 명명 규칙 (기계 판독용)

| 항목 | 규칙 | 예 |
|---|---|---|
| 기본 초상 | `assets/npc/portraits/<npc_id>.png` — npc_id = cheongha_npcs.yml 키 | `portraits/hanbaek.png` |
| 상태 변형 | `<npc_id>.<variant>.png` — variant 등록제: `wounded`, `aged`, `hostile` 3종만 | `portraits/galho.hostile.png` |
| tier 1~2 공용 | `default.<계열>.png` — 계열 = 1.3 표의 7행 | `default.mingan.png` |
| MC 스킨(M3) | `assets/npc/skins/<npc_id>.png` | `skins/jinun.png` |
| 금지 | 한글·공백 파일명, npc_id 밖의 임의 이름 (등록제 명사의 파일 시스템판) | — |

제작 우선순위: tier 4(muksam·jomunwon) → tier 3 대화 빈도순(hanbaek·soyeon·yumun 선두)
→ tier 2. tier 1은 공용 실루엣으로 충분 — 얼굴을 얻는 것은 승격(이름을 얻는 것)과 함께다.

## 6. 접합과 후속 배선

```text
resourcepack_design.yml  E020~E027 명패 재사용(1.2) / E060 대역 세력 문장 = tier 4+ 명패(M3)
                        / 명패 금지색(red·yellow) 규칙은 semantic_colors 기세 4색에서 파생
npc_lifecycle           schedule = 위치·시선·개폐의 원천(4.2·4.3) / 승격(이름 획득) = 명패 tier 상향 + 초상 발주 트리거
npc_dialogue            반경 5블록 토큰 공유(4.3) / 태세 색(백/녹/적) ↔ 파티클 이중 표기(4.4) / 웹훅 페르소나(5장)
factions.yml            5계열 = 색 배정 단위(1.3)
```

후속 배선 필요 지점 (본 문서는 규정만 — 구현·수정은 별도 작업):

```text
① CheonghaBuilder.npc() — 명패 문법(1장) 적용: 세력색 Component + tier 차등. 또한 현행 빌더의
   "전장 서기 조문원"은 yml과 불일치 (조문원 = 현령, 전장 = 금서방) — 명패 교정 필요
② 스폰 확충 — 빌더는 5인만 스폰 중. 2.1 표 기준 상주 NPC 추가 + 비스폰 규정(흑랑·갈호·졸개) 반영
③ Profession 지정 — 2.1 표를 빌더 spawn 시 setProfession으로 배선 (현재 전원 무직)
④ PUA 글리프 명패 렌더 검증 — E020~이 default 폰트에 등록돼 있는지 확인, 아니면 build_resourcepack.py에
   default 폰트 provider 추가 (F26 준수)
⑤ 세력 id 정합 — cheongha_npcs.yml의 gwanchung/gwangun/jeongboSang/pyoguk/bulga를 factions.yml에
   등록 또는 표기 통일 (1.3 메모)
⑥ config 이관 — 1.3 색 대응표·2.1 직업표·3.2 복식 코드·4.4 파티클표 → config/npc_visual.yml 신설 시 이동
   (본 문서가 기준 문서, yml이 기계 판독 — resourcepack_design 이원 구조 그대로)
⑦ M2 이동 규정(4.2) — 구간 전환 훅 + 좌표 프리셋 데이터 (schedule place → 실좌표 매핑이 선행 과제)
```




