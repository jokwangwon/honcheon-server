# 🗡 Honcheon Server (혼천)

> **혼천(混天)**: 무림의 균형이 무너진 시대, 네 세력이 충돌하는 강호 속으로 당신을 초대합니다.

---

## 📘 소개 (Introduction)

**Honcheon** is a scalable Minecraft-based **Wuxia RPG server project**.  
It begins as a single-server deployment and is designed to evolve into a **multi-server architecture** using modular components such as BungeeCord or Velocity.

Inspired by Korean martial arts webtoons and novels like *Return of the Mount Hua Sect*, the game features a dynamic world where justice, chaos, ambition, and blood cultism collide.

---

## ⚔ 주요 특징 (Key Features)

- 🔹 **네 대세력 구도**: 정파, 사파, 마교, 혈교
- ⚔ **PvP 중심의 실시간 전투 시스템**
- 📈 **무공 숙련도 + 경지(이류 → 생사경)** 성장 구조
- 🌀 **문파별 스킬, 심법, 오의** 체계
- 🎮 **4슬롯 스킬 세팅 + 실시간 회피/카운터/경공**
- 🧱 **유동적 서버 구조**: 단일 서버 기반 → Bungee 기반 다중 서버 확장 가능

---

## 🏗 폴더 구조 요약

```plaintext
honcheon-server/
├── core/             # 공통 유틸 및 로직 모듈
├── server-main/      # 메인 서버 (단일 서버 시 진입점)
├── server-faction/   # 문파별 콘텐츠 서버 (확장 대비)
├── server-hub/       # 허브/로비 서버 (멀티서버 연동)
├── config/           # 스킬, 심법, 경지 등 게임 설정
├── docs/             # 세계관 및 스토리 요약
├── scripts/          # 실행 스크립트 (start-single.sh 등)
├── docker/           # (선택) Docker 기반 다중 서버 구성
└── resources/        # 텍스처, 언어 등 리소스 파일

##🚀 실행 방법 (How to Run)
###✅ 단일 서버 (개발/테스트용)
bash scripts/start-single.sh
###🔁 다중 서버 (운영 확장 시)
bash scripts/start-bungee.sh
각 서버에는 개별 설정(server.properties, 포트 번호 등)을 지정해야 합니다.

📚 문서 및 기획 참고
docs/story_summary.md — 세계관 및 스토리 개요

config/ 폴더 내 .yml — 문파/스킬/경지 등 게임 밸런스 자료

🧩 기술 스택 (Tech Stack)
Minecraft Java Edition (1.xx.x)

Spigot / Paper / BungeeCord (확장 예정)

Java 17+

(Optional) Gradle / Docker / YAML-based config management

📜 라이선스
본 프로젝트는 MIT License를 따릅니다.

👤 제작
개발 및 기획: @jokwangwon (Solo Dev)

문의: GitHub Issues 또는 Discussions 활용

“강호의 운명은 당신의 검끝에 달려 있습니다.”
지금, 혼천의 세계로 뛰어드십시오. 🐉


---

## 💡 비고

- 서버 버전(`1.xx.x`)은 실제 사용하는 마인크래프트 버전에 맞게 수정하세요.
- 다국어 지원이 필요할 경우 `README.ko.md`, `README.en.md`로 분리 가능
- `스크린샷`, `아키텍처 다이어그램`, `문파 무공표` 등이 생기면 이미지도 추가 가능

필요하다면 Markdown 파일로 바로 내려드릴 수도 있습니다!
