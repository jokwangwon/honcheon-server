# Fable B-004 완료 — 팩 실물 게이트 감사

> 2026-07-14 · 소유: Fable · 검토자: Codex
> 소유 파일: `tools/pack_gate_audit.py` · `tools/pack_gate_audit_selftest.py`
> (부수 갱신: `docs/BACKLOG.md` B-004 닫힘 · `docs/collaboration/ACTIVE_V6.md` 상태)

## 무엇이 병이었나

게이트가 **실제로 열리는 길은 하나뿐**이었다: 팩 zip 실물이 없으면 `PackPusher.load()` 가
`enabled = false` 로 배급을 끄고(`PackPusher.java:112-127`), 팩을 안 보내니 거절·다운로드 실패·침묵의
문이 **발동할 일 자체가 없다.** severe 로그 한 줄만 남고 게이트는 조용히 열린다.
그런데 `pack_gate_audit.py` 는 등록부 YAML 과 소스 텍스트만 봤다 — `exists`·`sha1`·`실물` 이 한 글자도 없었다.
**등록부는 닫혔다 말하고, 세계에선 열려 있었다.**

## 무엇을 했나

`tools/pack_gate_audit.py` 에 **축 ① 「실물이 있는가」** 신설 (기존 축은 ②~⑦ 로 밀림):

1. **실물 계측** — 등록부 `local_path` 를 코드와 같은 기준으로 푼다:
   - 기본값 미러: `local_path` 부재 시 `../pack-http/<file>` (코드의 `Path.of(path == null ? …)` 와 동일)
   - 기준 디렉터리: `run/mvt` (`scripts/run_mvt_server.sh` 의 `RUN` — JVM 작업 디렉터리)
   - 존재하지 않으면 **위반** · 0바이트면 위반(문이 아니라 벽) · 못 읽으면 위반
   - 있으면 크기·sha1 을 보고한다 (코드의 `sha1()` 과 같은 계측)
2. **미러 표류 방지** — 눈과 코드가 같은 곳을 보는지 소스에서 잰다:
   - 코드에 `"../pack-http/" + file` 기본 경로가 그대로 있는가
   - `hash = sha1(localPath)` — 실물에서 재는가
   - `if (hash == null)` 가지에 `enabled = false` 와 `.severe(` 가 있는가 (부재가 조용해지면 위반)
3. **축 ② 보강** — 등록부 `enabled: false` 도 잰다 (배급이 꺼지면 팩을 안 보내고 문도 안 선다 —
   기존 눈은 `required`·`gate.enabled` 만 봤다)

`tools/pack_gate_audit_selftest.py` 에 변이 5종 신설 (⑫~⑯):

| 변이 | 여는 방법 | 잡는 말 |
|---|---|---|
| ⑫ | 등록부 `local_path` 를 없는 파일로 | 실물이 없다 |
| ⑬ | 실물 zip 을 잠시 개명 (텍스트 변이가 아닌 **파일 숨김 변이** — `old=None` 형식 신설, finally 로 복원) | 실물이 없다 |
| ⑭ | 등록부 `enabled: false` | 배급 자체가 켜져 있다 |
| ⑮ | 코드 기본 경로를 `../pack-cache/` 로 표류 | 기본 경로 |
| ⑯ | 실물 부재의 severe 를 info 로 | 소리내어 |

## 검증 (실행 결과)

```
python3 tools/pack_gate_audit.py
→ exit 0 · 위반 0건 · 축 ①: 실물이 있다 — run/pack-http/honcheon_pack.zip · 411,859바이트 · sha1 dfb01c0d2010…
  (sha1sum 실측 dfb01c0d20102c7eee88b9299646435687b1fa6c 과 일치 — 눈과 코드가 같은 실물을 본다)

python3 tools/pack_gate_audit_selftest.py
→ exit 0 · 변이 16/16 전부 잡음 · 되돌리기 후 재감사 위반 0건 (config·소스·실물 원상 복구 확인)

python3 tools/backlog_audit.py --run
→ exit 0 · 위반 0건 — 축 ⑦ 이 B-004 의 두 검증 명령을 실제로 돌려 확인함
```

## 남은 위험 (B-004 범위 밖 — 기록만)

1. **외부 호스팅과 로컬 실물의 이중성**: 등록부 `url:` 이 GitHub releases 를 가리키는데 sha1 은
   로컬 실물에서 잰다. 로컬만 새로 굽고 `scripts/publish_pack.sh` 를 안 돌리면 **전원이 다운로드
   실패로 튕긴다** (sha1 불일치). 시끄러운 실패라 게이트는 닫힌 방향(안전)이지만 운영 걸림돌 —
   "로컬 실물 sha1 = 릴리스 sha1" 을 재는 눈은 아직 없다.
2. **팩 사본 세 벌**: `run/pack-http/`(배급 실물·최신) · `run/honcheon_pack.zip`(수동 설치용·최신) ·
   `run/mvt/honcheon_pack.zip`(**13KB·7/11 낡음** — `run_mvt_server.sh:40` 이 여기 굽게 되어 있으나
   실제 최신은 `run/` 에 있음). 스크립트 두 갈래가 서로 다른 곳에 굽는 정황 — 혼동의 씨앗.
3. **self-test ⑬ 은 실물을 잠시 개명한다**: 라이브 서버가 도는 중 그 찰나에 플레이어가 접속하면
   그 한 명은 팩을 못 받는다. 로컬 1인 검증 서버라 감수했다.

## 인수 방법 (한쪽만 남아도)

- 병의 정의: `docs/BACKLOG.md` 닫힌 것 절 B-004
- 눈: `python3 tools/pack_gate_audit.py` — 축 ① 이 실물을 잰다
- 눈의 눈: `python3 tools/pack_gate_audit_selftest.py` — 16 변이 전부 ✅ 여야 한다
- 실물을 지우고 감사를 돌리면 위반이 나야 정상이다 (그게 이 작업의 전부다)
