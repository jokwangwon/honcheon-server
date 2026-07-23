# 혼천 접속 안내 (친구 배포용)

> 2026-07-23 확정. exe 는 폐기했다 — Windows Defender 가 서명 없는 부트스트래퍼를
> 오탐·격리했다 (다운로드+실행 패턴은 악성코드와 겉이 같아, 서명 없이는 신뢰를 못 받는다).
> **실행 파일이 없으면 격리할 대상도 없다** — 순수 PowerShell 한 줄로 간다.

## 방법 A — 실행창 한 줄 (권장 · 디스코드 안내판에 이 줄을 박는다)

**Win + R** → 붙여넣기 → 엔터:

```
powershell -nop -ep bypass -c "irm https://github.com/jokwangwon/honcheon-pack/releases/download/pack/honcheon_setup.ps1 -OutFile $env:TEMP\hc.ps1; & $env:TEMP\hc.ps1"
```

- 파일을 손으로 받지 않는다 → 브라우저 다운로드 차단·SmartScreen 무관.
- 바로 수묵 설치 창 → 설치 → 게임 자동 시작.
- 재실행도 같은 줄 (동기화 갱신 후 실행). 바탕화면 「혼천」 아이콘도 생긴다.

## 방법 B — 파일로 (방법 A 가 막힐 때)

1. 브라우저에서 받는다:
   `https://github.com/jokwangwon/honcheon-pack/releases/download/pack/honcheon_setup.ps1`
2. 받은 폴더에서 **우클릭 → PowerShell에서 실행**.
   - "실행 정책" 경고가 나오면: 파일 우클릭 → 속성 → 하단 **[차단 해제]** 체크 → 확인 후 재시도.

## 방법 C — 완전 수동 (모드 런처를 이미 아는 사람)

1. [Prism Launcher](https://prismlauncher.org) 또는 [Modrinth App](https://modrinth.com/app) 설치.
2. mrpack 가져오기:
   `https://github.com/jokwangwon/honcheon-pack/releases/download/pack/honcheon-1.21.11-0.2.1.mrpack`
3. 그 인스턴스로 접속.

---

## Defender 가 여전히 막으면 (드묾 — ps1 도 오탐할 때)

PowerShell 스크립트 오탐은 exe 보다 훨씬 드물지만, 백신이 겹치면 있을 수 있다. 순서대로:

1. 방법 A 의 실행창 줄을 다시 시도 (일시적 클라우드 오탐이면 몇 분 뒤 풀린다).
2. Windows 보안 → 바이러스 및 위협 방지 → 보호 기록에서 **honcheon_setup.ps1 허용**.
3. 그래도면 관리자에게 문의 — 우리가 파일 내용을 함께 확인한다 (내용은 공개 · 위 주소 그대로).

## ★ 근본 해결 (미래 · 사용자 결정) — 코드 서명

무서명이라 겪는 마찰이다. Authenticode 코드서명 인증서(연 십수만 원~)를 사면 exe·ps1
모두 "확인된 게시자"가 되어 SmartScreen·Defender 오탐이 사라진다. 배포 규모가 커지면
검토할 값어치가 있다 — 지금은 방법 A 로 충분하다.
