# [외부 설계서] Minecraft 1.21.11 RPG 리소스팩·전투 모션 시스템 요청서

> 출처: 사용자가 다른 AI로부터 받아 온 설계서. **그대로 적용 금지.**
> 문서 스스로의 지시: "먼저 현재 구조를 분석하라. 충돌·중복이 있으면
> ① 정상 작동하는 기존 구현을 우선 보존 ② 새 구조가 명확히 더 안정적일 때만 교체 제안
> ③ 역할 중복 시 통합/대체 비교 ④ 팩만으로 가능한 영역과 불가능한 영역 분리
> ⑤ 서버 플러그인 필요 기능과 클라이언트 모드 필요 기능 구분 ⑥ 구현 전 기술적 가능 여부·제약 설명
> ⑦ 전체를 한 번에 만들지 말고 최소 프로토타입부터 검증 ⑧ 확인 안 된 환경은 [가정]으로 표시"

## 1. 시스템 역할 삼분
- **리소스팩**: 아이템 텍스처·2D 아이콘·3D 모델·손에 든 무기 모델·인벤토리 모델·장비 외형·블록 텍스처·GUI 배경·HUD 아이콘·폰트·글리프·파티클 텍스처·커스텀 사운드·무기 상태별 모델·스킬 발동 모델·강화 모델·등급별 모델·모델 표시 위치와 회전값·1인칭/3인칭 표시 설정. **판정·피해 계산은 하지 않는다.**
- **서버 플러그인**: 공격 입력 감지·공격 상태 관리·콤보 진행·쿨타임·스킬 조건·공격 범위·히트박스·피해량·치명타·경직·넉백·다운·공중 띄우기·대시·회피·방어·패링·스태미나·자원·상태이상·대상 필터링·파티클 호출·사운드 재생·아이템 모델 상태 전환·GUI/HUD 갱신·플레이어 데이터 저장·서버 측 보안 검증.
- **클라이언트 모드**: 플레이어 신체 전체를 쓰는 정교한 애니메이션 — 양손 무기 자세·상체/허리 회전·무기별 대기 자세·횡베기/올려베기/내려베기/찌르기/회전 공격·구르기·백스텝·피격 경직·방어 자세·패링 자세·공중 공격·낙하 공격·무기 수납/꺼내기·달리기 중 무기 자세·손과 무기 궤적의 정밀 동기화.
  - **"가능하면 모드 설치가 필요 없는 서버 구조를 우선 검토. 다만 원하는 수준이 팩+플러그인만으로 불가능하면 억지로 우회하지 말고 모드가 필요한 이유를 설명하라."**

## 2. 구조 후보 비교
- **(2.1) 모드 없는 서버 구조**: Paper + 전투 코어 / 커스텀 아이템 / 팩 생성·배포 / 스킬 / 파티클·사운드 / GUI·HUD / 데이터 저장 / 관리·디버그 도구.
  - 가능: 커스텀 무기·아이템, 2D/3D 모델, 공격 시 모델 변경, 3연격+ 콤보, 강공격, 대시, 회피, 방어, 패링, 범위 공격, 스킬, 파티클, 커스텀 사운드, RPG형 HUD, 쿨타임 표시, 보스바, 데미지 표시, 상태이상 표시.
  - 제약: 플레이어 관절 애니메이션 신규 제작 어려움 · 몸동작과 무기 궤적 불일치 가능 · 바닐라 입력만 쓰면 조작키 제한 · 일부 연출은 아이템 모델/파티클/이동/사운드로 우회.
- **(2.2) 클라이언트 모드 구조**: Fabric/NeoForge — 애니메이션·입력·렌더링 모듈 + 서버 전투 코어 + 동기화 계층.
  - 가능: 정교한 전신 모션, 무기별 대기 자세, 발도/수납, 구르기, 백스텝, 공중 콤보, 피격 모션, 방어/패링 자세, 커스텀 키 바인딩, 궤적 동기화, 애니메이션 블렌딩.
  - 제약: **모든 플레이어가 모드 설치** · 접속 장벽 · 버전 호환 · 다른 모드와 충돌 · 유지보수 비용 · 동기화 설계 필요 · 서버 권위 판정은 별도 유지.
- **요구: 현재 프로젝트에 더 적합한 구조를 추천하고 선택 기준을 설명.**

## 3. 권장 아키텍처 (계층)
```
Input → Combat State Machine → Attack Definition → Server Hit Detection
      → Damage/Status → Visual·Audio Event → Client Rendering
```
- Combat Controller: current state / combo index / input buffer / cooldown / resource cost / interrupt rules
- Attack Definition: startup / active / recovery / combo window / hitbox / damage / knockback / stagger / movement / particle / sound / model state
- Server Authority: position validation / hit detection / target filtering / damage calc / duplicate hit prevention / anti-cheat
- Presentation: player animation / item model / particle / sound / camera effect / HUD
- **판정과 연출 분리. 보이는 무기가 적을 통과했다고 클라이언트가 피해를 결정하면 안 된다.**

## 4. 공격 상태 머신
상태: IDLE, MOVE, SPRINT, JUMP, FALL, ATTACK_1~3, HEAVY_ATTACK, CHARGING, SKILL, ULTIMATE, DODGE, BLOCK, PARRY, STUN, KNOCKBACK, KNOCKDOWN, AIRBORNE, RECOVERY, COOLDOWN, DEAD.
전이 예: IDLE→ATTACK_1→ATTACK_2→ATTACK_3 / ATTACK_1→DODGE / ATTACK_2→SKILL / IDLE→BLOCK→PARRY / ANY_ATTACK→STUN→RECOVERY→IDLE.
상태마다: 진입/종료 조건·지속시간·이동 가능·시점 회전 가능·점프 가능·회피 가능·스킬 취소 가능·피격 중단 가능·슈퍼아머·무적 프레임·다음 콤보 입력 가능 시간·모델/애니메이션/파티클/사운드 상태.

## 5. 공격 시간 구조
Startup(판정 없음·이동 제한·방향 조정·피격 취소·차지 누적) / Active(히트박스·중복 방지·피해·경직·넉백·파티클·타격음·화면 흔들림) / Recovery(후딜·이동 감속·회피/스킬 취소 허용 여부) / Combo Window(이른 입력은 버퍼·늦으면 종료·무기별 상이) / Cancel Window(회피·방어·스킬·점프·무기 교체 취소).

## 6. 기본 3연격 프로토타입 (수치는 임시 예시 — 반드시 설정 파일로 분리)
1타 횡베기: 0.05 준비 → 0.12 시작 → 0.18 판정 → 0.20 가로 궤적 → 0.22 타격음 → 0.35 다음 입력 허용 → 0.55 종료
2타 올려베기: 0.10 시작 → 0.17 판정 → 0.18 대각 궤적 → 0.23 약한 띄우기 → 0.38 입력 허용 → 0.58 종료
3타 찌르기: 0.15 짧은 전진 → 0.19 직선 판정 → 0.20 파티클 → 0.24 강한 타격음 → 0.40 후딜 → 0.75 종료

## 7. 입력 버퍼
현재 공격 종료 전 입력 → 버퍼 저장 → Combo Window 열릴 때 자동 실행.
설정: 버퍼 유지 시간·저장 개수·중복 처리·회피/방어/스킬 우선순위·상태이상 중 입력 제거·무기 교체 시 초기화.
우선순위 예: STUN > PARRY > DODGE > BLOCK > SKILL > HEAVY_ATTACK > NORMAL_ATTACK.

## 8. 공격 판정 (히트박스 유형)
- **Raycast**: 찌르기·직선. 시작 위치·방향·최대 거리·관통 여부·블록 충돌 여부
- **Cone**: 횡베기·부채꼴. 중심 각도·거리·높이·최대 대상 수
- **Sphere**: 폭발·충격파. 반지름·거리 감쇠·중심부 추가 피해
- **Box**: 직사각 범위·넓은 무기·전방 밀기. 폭·높이·길이·회전
- **Line Sweep / Capsule 유사**: 움직이는 검의 궤적·연속 판정. MC에 Capsule이 없으면 다중 샘플링 또는 연속 Raycast로 대체
조건: 벽 관통 여부·아군 공격 여부·소환수·플레이어/몬스터 차이·PvP/PvE 피해 분리·중복 타격 방지·최대 타격 수·대상 우선순위·무적/사망 확인·지역 보호 연동·이벤트 취소 확인

## 9. 무기 유형별 규격 (수치는 설정 파일로)
직검(빠름·3~5연격·패링·좁은 판정) / 도(넓은 횡베기·높은 넉백·강공격) / 중검(느림·고피해·슈퍼아머·지면 충격파) / 창(긴 사거리·찌르기·돌진·관통) / 쌍검(빠른 연속·다단·짧은 사거리) / 단검(치명타·후방 보너스·회피 연계) / 부채(근·원거리 혼합·펼침/접힘 모델·상태이상) / 권갑(짧은 사거리·높은 경직·돌진·띄우기·공중 콤보) / 활(차지·투사체·낙차·헤드샷·거리별 피해)

## 10. 입력 체계 (모드 없는 서버의 한계)
감지 가능: 좌클릭·우클릭·웅크리기·점프·달리기·공중 상태·슬롯 변경·보조 손 교체·아이템 버리기 및 그 조합.
예: 좌클릭=기본 공격 / 연속=콤보 / 우클릭=방어 / 공격 직전 우클릭=패링 / 웅크림+좌클릭=강공격 / 웅크림+우클릭=특수 / 공중 좌클릭=공중 공격 / 낙하 중 좌클릭=낙하 공격 / 보조 손 교체=스킬 전환.
확인 필요: 블록 상호작용 충돌·엔티티 공격 이벤트 중복·아이템 사용 충돌·바닐라 공격 쿨타임 충돌·인벤토리 중 오입력·보호 구역·다른 플러그인의 이벤트 취소·패킷 기반 감지 필요 여부.
**새 키가 필요하면 그것은 클라이언트 모드 영역.**

## 11. 서버 권위 판정
검증: 생존·전투 상태·공격 가능·자원·쿨타임·장착 무기·공격 속도·위치·방향·거리·대상 생존·보호 지역·PvP 허용·이미 타격한 대상인지·비정상 이동·비정상적으로 빠른 입력.
클라이언트가 보낸 정보는 보조로만. 피해량과 대상은 서버가 재계산.

## 12. 이동과 공격 연동
```yaml
movement: { lock_horizontal, lock_vertical, speed_multiplier, allow_rotation, rotation_limit, forward_impulse, allow_jump, allow_sprint }
```
검토: 공격 중 이동/회전 제한·시작 시 짧은 전진·타격 성공/실패 시 보정·공중 낙하 속도·넉백 중 취소·블록 충돌·계단/반블록·물속·탑승·비행.
**연출 때문에 플레이어를 과도하게 순간이동시키지 말 것.** 짧은 대시는 Velocity vs 서버 위치 보정 중 안정적인 쪽 비교.

## 13. 방어와 패링
방어: 가능 무기·정면 각도·감소율·스태미나·넉백 감소·상태이상 방어·이동 속도·시작 시간·해제 후 딜레이.
패링: 가능 시간·입력 타이밍·방향 조건·공격 종류 제한·성공 시 공격자 경직·자원 회복·연속 패링 제한·원거리/보스 패링 여부.
흐름: 입력 → 패링 준비 → 0.15초 판정 → 그 안에 충돌 시 무효화 + 공격자 경직.
**네트워크 지연 고려 — 지나치게 짧게 잡지 말고 서버 기준 시간을 쓸 것.**

## 14. 회피와 무적 프레임
방향·거리·시간·스태미나·쿨타임·무적 프레임·공격 취소 가능·회피 후 연계·공중 사용·연속 사용 제한.
DODGE_START → INVULNERABLE → DODGE_MOVE → DODGE_RECOVERY → IDLE.
무적이 모든 피해를 무효화할지, 특정 공격만 회피할지 구분.

## 15. 아이템 모델 상태
weapon_id 아래: inventory / idle / first_person_idle / third_person_idle / attack_prepare / attack_active / skill_active / charged / enhanced / broken.
**모든 상태에 모델을 만들 필요 없음.** 실루엣 변화가 클 때만 분리 · 단순 발광은 텍스처/파티클 · **공격 궤적은 모델보다 파티클을 우선 검토** · 동일 계열 텍스처 재사용 · 인벤토리 아이콘과 손 모델 분리 · 1인칭/3인칭 크기 차이 · 모델 전환 빈도 제한 · 상태 추적 비용 검토.

## 16. 팩 파일 구조
`assets/<ns>/{items,models,textures,sounds,font,lang}` + `pack.mcmeta`·`pack.png`·`sounds.json`.
포함할 것: 파일명 규칙·아이템/모델/사운드/파티클/GUI ID 규칙·버전 관리·빌드 결과물 생성·**해시 생성·서버 배포·캐시 무효화**.

## 17. GUI와 HUD
체력·자원·스태미나·경험치·스킬 슬롯·쿨타임·콤보 수·보스 체력·타겟 정보·상태이상·장비 등급·퀘스트·전투 상태·패링 성공·치명타·데미지 숫자·궁극기 게이지·버프/디버프.
배치: 좌하=체력/자원 · 우하=스태미나 · 하단중앙=스킬 슬롯 · 상단중앙=보스 체력 · 십자선 주변=패링/콤보/치명타 · 우상=퀘스트.
구현 후보: ActionBar / BossBar / Scoreboard / **Custom Font Glyph** / Inventory GUI / Display Entity / Packet HUD. 장단점·충돌 비교 요구.
**매 틱 전체 갱신 금지 — 값이 바뀐 경우에만.**

## 18. 파티클
정의 항목: particle_id·type·texture·spawn_origin·offset·direction·speed·count·duration·scale·rotation·follow_player·follow_weapon·visibility_range·lod_level.
유형: 직선/곡선/대각 검기·충격파·지면 균열·먼지·불꽃·연기·마법 입자·타격 스파크·피격/회복/버프 표시.
성능: 플레이어별 최대 수·거리 LOD·안 보는 사람에게 전송 제한·화면 밖 최소화·객체 재사용·**엔티티 기반 파티클 남용 금지**·장시간 잔류 제한·보스전 동시 효과 제한.

## 19. 사운드
계층: Attack Swing / Attack Hit / Critical / Block / Parry / Dodge / Skill Cast / Skill Impact / Charge / UI / Ambient.
설정: sound_id·volume·pitch·category·distance·random_variants·cooldown·priority.
**같은 공격 반복 시 완전히 동일하게 들리지 않게** pitch/variant 제한적 적용. 중복 재생·볼륨 누적 방지.

## 20. 데이터 구조 (코드에 수치 금지 — 데이터 파일로)
```yaml
weapon_id: sword_basic_01
weapon_type: sword
rarity: common
resources: { item_model, inventory_icon, swing_sound, hit_sound, particle }
combat: { base_damage, attack_speed, attack_range, knockback, stagger, combo_count, stamina_cost }
attacks:
  attack_1:
    startup: 0.12 / active: 0.08 / recovery: 0.35
    combo_window_start: 0.25 / combo_window_end: 0.45
    damage_multiplier: 1.0
    hitbox: { type: cone, range: 3.2, angle: 80, height: 1.8, max_targets: 4 }
    movement: { speed_multiplier: 0.5, forward_impulse: 0.3, allow_rotation: true }
    effects: { particle, swing_sound, hit_sound, model_state }
  attack_2: { ..., target_effect: { launch_power: 0.15 } }
  attack_3: { ..., hitbox: { type: ray, range: 4.0, width: 0.5 }, movement: { forward_impulse: 1.2 } }
```
**현재 프로젝트가 JSON/YAML/TOML/DB 중 무엇을 쓰는지에 맞출 것.**

## 21. 코드 모듈 구조 (권장안)
combat/ CombatManager·CombatSession·CombatState·CombatStateMachine·InputHandler·InputBuffer·ComboManager·AttackExecutor·AttackDefinition·HitboxService·DamageService·KnockbackService·StatusEffectService·MovementService·CooldownService·ResourceService·CombatEventBus
resource/ ItemModelService·ResourcePackBuilder·ResourcePackPublisher·SoundService·ParticleService·FontGlyphService·GuiResourceService
ui/ HudManager·BossBarManager·ActionBarManager·SkillBarManager·CooldownRenderer·DamageIndicator
data/ WeaponRepository·AttackRepository·SkillRepository·PlayerCombatRepository·ConfigurationLoader
debug/ HitboxVisualizer·StateDebugger·CombatLogger·PerformanceProfiler·AdminCommand

## 22. 이벤트 흐름
Input → Validation → State Check → Cooldown → Resource → Attack Definition Load → State Transition → Startup → Active → Hitbox → Target Filter → Damage → Knockback/Stagger → Particle/Sound → Recovery → Combo Window → Next or Idle.
이벤트 기반 vs 직접 호출 비교 요구. 외부 연동용 이벤트: CustomAttackStart/Hit/End·CustomDamage·CustomParry·CustomDodge·CustomCombo·CustomSkillCast.

## 23. 플레이어 데이터 (영구 vs 일시 분리)
영구: 장착 무기·전투 스타일·스킬 슬롯·쿨타임·스태미나·자원·장비 옵션·해금 스킬·HUD 설정·파티클 품질·사운드 설정.
일시(저장 불필요): 현재 공격 상태·콤보 인덱스·입력 버퍼·Active 프레임·현재 히트 대상 목록.

## 24. 성능
매 틱 전체 플레이어 탐색 금지 · 공격 중인 플레이어만 갱신 · 스케줄러 난립 방지 · **공격마다 BukkitTask 생성 최소화 → 공통 Tick Loop / 중앙 스케줄러** · 히트박스 비용 · Raycast 빈도 · 주변 엔티티 탐색 범위 · 파티클 패킷 수 · 사운드 중복 · 모델 상태 변경 패킷 · HUD 갱신 빈도 · Display Entity 사용량 · 임시 엔티티 생성 비용 · 객체 생성/GC · 설정 반복 파싱 금지 · 캐싱 · 종료 시 세션 정리.
```
Combat Tick Manager ├ Active Combat Sessions ├ Active Attacks ├ Active Skills └ Expired Session Cleanup
```
**플레이어 수에 따른 예상 비용과 병목 설명 요구.**

## 25. 보안·악용 방지
비정상적으로 빠른 입력·패킷 스팸·콤보 매크로·쿨타임 우회·무기 교체로 상태 초기화·로그아웃으로 쿨타임 초기화·이동 속도 조작·사거리 조작·**벽 너머 공격**·순간이동+공격·보호 지역 공격·사망 상태 공격·인벤토리 상태 공격·지연을 이용한 중복 타격·동일 대상 반복 타격·**클라이언트가 보낸 피해량 신뢰 금지**.

## 26. 기존 플러그인 충돌 검사
충돌 가능: 기본 공격 이벤트 취소·데미지 이벤트 중복·커스텀 아이템 식별 방식·AttributeModifier 중복·공격 속도 속성 충돌·CustomModelData/아이템 모델 정의 충돌·우클릭 충돌·웅크리기 충돌·액션바/보스바 중복·패킷 라이브러리 중복·사운드 ID 중복·**네임스페이스 중복**·팩 병합 문제.
결론은 다음 중 하나로: 기존 유지 / 기존 확장 / 어댑터 추가 / 일부 대체 / 완전 분리 독립 모듈.

## 27. 디버그
현재 상태·콤보 인덱스·입력 버퍼·쿨타임·공격 시간 단계·**히트박스 시각화**·공격 방향·타격 대상·피해 계산 로그·넉백 로그·경직 상태·파티클/사운드/HUD 호출 횟수·공격 처리 시간·틱 시간·TPS·활성 세션 수.
명령 예: debug state / hitbox / damage / performance · reload · give <weapon_id> · test <attack_id>

## 28. 테스트 계획
단위(상태 전이·콤보·버퍼·쿨타임·자원·피해·치명타·경직·넉백·설정 파싱) / 통합(입력→피해·모델 전환·파티클·사운드·HUD·사망·로그아웃·무기 교체·보호 지역·PvP/PvE) / 멀티(동시 공격·같은 대상 동시 타격·지연·TPS 저하·파티클/HUD/사운드 부하) / 회귀(MC·Paper·플러그인·팩 포맷·아이템 시스템 변경).

## 29. 최소 프로토타입 (첫 구현은 여기까지만)
무기: **커스텀 직검 1종** (인벤토리 아이콘·손 모델·공격 상태 모델 또는 파티클)
전투: **3연격 + 강공격 1종 + 방어 + 패링 + 회피 + 공격 판정 + 경직 + 넉백 + 스태미나 + 쿨타임**
시청각: 횡베기/대각/찌르기 파티클 · 휘두름/타격/패링 사운드
HUD: 체력·스태미나·현재 콤보·쿨타임
디버그: 히트박스 표시·현재 상태·피해 로그·처리 시간
검증 대상: 입력감·콤보 연결감·판정 정확도·지연 영향·파티클 가독성·모델 전환 안정성·HUD 비용·다중 플레이어 성능

## 30. 단계별 개발 순서
1단계 현재 프로젝트 분석 (서버 SW·MC 버전·Java·빌드 도구·플러그인 목록·기존 아이템/스킬/데미지/HUD 시스템·저장 방식·팩 구조·모드 사용 여부·목표 동접)
2단계 기술 검증 (모델 적용·팩 배포·공격 이벤트 감지·콤보 입력·히트박스·파티클·사운드·HUD·성능)
3단계 전투 코어 · 4단계 표현 계층 · 5단계 확장

## 31. 요구 답변 형식
바로 코드 금지. 순서대로: 요구사항 요약 → 팩만으로 가능 → 플러그인 필요 → 모드 필요 → 두 구조 비교 → 권장 아키텍처 → **기존 프로젝트와 충돌 가능성** → 최소 프로토타입 범위 → 상태 머신 → 시간 구조 → 입력 버퍼 → 판정 → 서버 권위 → 무기 데이터 → 팩 구조 → HUD → 파티클/사운드 → 성능 → 보안 → 디버그 → 테스트 → 개발 순서 → 필요한 코드 파일 목록 → **추가로 필요한 정보**.
확인 안 된 것은 태그로: `[확인 필요]` `[가정]` `[권장안]` `[대안]` `[기존 구조와 충돌 가능]`

## 최종 목표 (문서의 문장)
> "리소스팩은 아이템·모델·GUI·파티클·사운드 등의 표현을 담당하고, 서버 전투 시스템은 입력·상태·판정·피해량·콤보·스킬과 플레이어 데이터를 담당하며, **정교한 전신 애니메이션이 필요할 때만 클라이언트 모드를 선택적으로 결합**할 수 있는 확장 가능한 RPG 전투 시스템."

> 우선 전체 구현을 시작하지 말고, **커스텀 직검 1종과 기본 3연격**을 기준으로 기술 설계와 최소 프로토타입 구조부터 작성할 것.
