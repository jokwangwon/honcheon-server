# 혼천 서버 데이터팩 소개 함수
# (레거시) 마인크래프트 확장 단계용 — 세력 구성은 docs/design/text_rpg_design.md 기준

# 플레이어에게 환영 메시지 표시
tellraw @a {"text":"=== 혼천 서버에 오신 것을 환영합니다! ===","color":"aqua","bold":true}
tellraw @a {"text":"혼돈에 빠진 강호에서 자기만의 삶을 선택하세요.","color":"yellow"}
tellraw @a {"text":"","color":"white"}

# 세력 소개
tellraw @a {"text":"【 강호의 세력 】","color":"gold","bold":true}
tellraw @a {"text":"• 정파 - 구파일방과 오대세가의 연합","color":"aqua"}
tellraw @a {"text":"• 사파 - 하오문, 녹림, 장강수로채, 살막","color":"red"}
tellraw @a {"text":"• 관군 - 치안을 지키는 질서의 세력","color":"gray"}
tellraw @a {"text":"• 상단 - 이익을 좇아 움직이는 경제 세력","color":"green"}
tellraw @a {"text":"","color":"white"}

# 기본 명령어 안내
tellraw @a {"text":"【 기본 명령어 】","color":"gold","bold":true}
tellraw @a {"text":"• /faction - 세력 관련 명령어","color":"white"}
tellraw @a {"text":"• /cultivation - 경지 수련 명령어","color":"white"}
tellraw @a {"text":"• /skill - 무공 관련 명령어","color":"white"}
tellraw @a {"text":"","color":"white"}

# 게임 규칙 안내
tellraw @a {"text":"【 게임 규칙 】","color":"gold","bold":true}
tellraw @a {"text":"• 다른 플레이어를 존중하세요","color":"green"}
tellraw @a {"text":"• 당신의 행동은 세계에 흔적을 남깁니다","color":"green"}
tellraw @a {"text":"• 강해지는 것만이 유일한 길은 아닙니다","color":"green"}
tellraw @a {"text":"","color":"white"}

# 도움말 안내
tellraw @a {"text":"도움이 필요하시면 /help 명령어를 사용하세요.","color":"yellow","italic":true}
