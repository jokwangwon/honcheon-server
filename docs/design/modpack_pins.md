# 혼천 클라이언트 모드팩 핀 — Minecraft 1.21.11 Fabric

조사일: 2026-07-23 · 출처: Modrinth API v2 (`game_versions=["1.21.11"]&loaders=["fabric"]`) + meta.fabricmc.net

## 로더

| 항목 | 버전 | 비고 |
|---|---|---|
| Fabric Loader | **0.19.3** | meta.fabricmc.net 최신 stable |

## 모드 (전부 1.21.11 Fabric 지원 확인됨)

| 모드 | 1.21.11 지원 | 핀 버전 | 유형 | URL | 의존성 |
|---|---|---|---|---|---|
| Fabric API | O | `0.141.5+1.21.11` | release | https://modrinth.com/mod/fabric-api | — |
| Sodium | O | `mc1.21.11-0.8.13-fabric` | release | https://modrinth.com/mod/sodium | — |
| Iris | O | `1.10.7+1.21.11-fabric` | release | https://modrinth.com/mod/iris | **Sodium 필수** |
| Xaero's Minimap | O | `fabric-1.21.11-26.4.2` | release | https://modrinth.com/mod/xaeros-minimap | Fabric API 필수 (Open Parties and Claims 선택) |
| Xaero's World Map | O | `fabric-1.21.11-1.44.2` | release | https://modrinth.com/mod/xaeros-world-map | Fabric API 필수 (Open Parties and Claims 선택) |
| Emotecraft | O (베타만) | `3.2.0-b.build.159+1.21.11-fabric` | **beta** | https://modrinth.com/mod/emotecraft | **Fabric API + Player Animation Library 필수** (선택: Bendable Cuboids, fabric-permissions-api, Searchables) |
| Player Animation Library | O | `1.1.8` | release | https://modrinth.com/mod/player-animation-library | — |
| WI Zoom | O | `1.7-MC1.21.11` | release | https://modrinth.com/mod/wi-zoom | Fabric API 필수 |
| Wavey Capes | O | `1.10.2` | release | https://modrinth.com/mod/wavey-capes (slug: `wavey-capes`, `waveycapes` 아님) | Fabric API 필수 |
| Caxton | O | `0.9.0-beta.3+1.21.11-FABRIC` | release(태그) — 버전명은 beta | https://modrinth.com/mod/caxton | Fabric API 필수 |

주의:
- **Emotecraft**는 1.21.11용이 베타 채널뿐이다 (안정 release 최신은 2.5.8+1.21.4 — 1.21.4용). 3.2.0-b 계열이 1.21.11 대응 최신.
- Xaero 두 모드는 같은 날짜(26.4.2 / 1.44.2) 짝으로 맞춰 설치할 것.
- Iris ← Sodium 순서: Sodium 없이 Iris는 기동 불가.

## 셰이더팩 후보 3종 (수묵/무협 무드 — 부드러운 조명, Iris 호환, 1.21.11 게임버전 태그 확인)

| 순위 | 셰이더팩 | 최신 버전 | 특징 (한 줄) | URL |
|---|---|---|---|---|
| 1 | **Complementary Reimagined** | `r5.8.1` (2026-05-21) | 바닐라 감성을 유지하는 절제된 부드러운 조명 — 채도가 낮고 형광끼 없음, 성능·호환성 업계 표준급. 수묵 무드에 가장 안전한 기본값 | https://modrinth.com/shader/complementary-reimagined |
| 2 | **Photon** | `v1.3b` (2026-04-14) | 물리 기반의 차분하고 사실적인 톤 — 안개·연무 표현이 좋아 산수화 원경 느낌을 살리기 좋다, 중간 성능대 | https://modrinth.com/shader/photon-shader |
| 3 | **BSL** | `10.1.3` (2026-04-20) | 따뜻하고 회화적인 클래식 — 기본 프리셋은 다소 채도가 있으니 saturation/bloom을 낮춰 쓰면 먹빛에 근접, 설정 폭이 넓다 | https://modrinth.com/shader/bsl-shaders |

탈락: Makeup Ultra Fast (`9.5c`) — 성능은 최상이지만 조명이 밋밋해 무드 연출력이 부족. 저사양 폴백용으로만 고려.

## Emotecraft 서버(Paper) 플러그인

| 항목 | 버전 | 유형 | 지원 | URL |
|---|---|---|---|---|
| Emotecraft (Paper/Folia) | `3.2.0-b.build.159+1.21.11-paper` | beta | Paper 1.21.11 | https://modrinth.com/plugin/emotecraft (동일 프로젝트의 paper 로더 버전) |

- 클라이언트 fabric 빌드(`build.159+1.21.11-fabric`)와 **같은 build.159** 이므로 프로토콜 짝이 맞는다.
- 더 최신 paper 빌드(3.3.0/3.4.0-b.build.160~161)는 MC 26.x 전용 — 1.21.11에는 build.159가 최신.
