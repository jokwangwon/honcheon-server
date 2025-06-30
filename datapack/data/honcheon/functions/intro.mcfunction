# 혼천 서버 데이터팩 소개 함수

# 플레이어에게 환영 메시지 표시
tellraw @a {"text":"=== 혼천 서버에 오신 것을 환영합니다! ===","color":"aqua","bold":true}
tellraw @a {"text":"동방 신선 세계에서 심법을 연마하세요.","color":"yellow"}
tellraw @a {"text":"","color":"white"}

# 문파 소개
tellraw @a {"text":"【 주요 문파 】","color":"gold","bold":true}
tellraw @a {"text":"• 청운문 (靑雲門) - 바람과 구름의 심법","color":"aqua"}
tellraw @a {"text":"• 화염문 (火焰門) - 불과 열의 심법","color":"red"}
tellraw @a {"text":"• 태산문 (泰山門) - 대지와 산의 심법","color":"gray"}
tellraw @a {"text":"• 수월문 (水月門) - 물과 달의 심법","color":"blue"}
tellraw @a {"text":"","color":"white"}

# 기본 명령어 안내
tellraw @a {"text":"【 기본 명령어 】","color":"gold","bold":true}
tellraw @a {"text":"• /faction - 문파 관련 명령어","color":"white"}
tellraw @a {"text":"• /cultivation - 심법 수련 명령어","color":"white"}
tellraw @a {"text":"• /skill - 스킬 관련 명령어","color":"white"}
tellraw @a {"text":"","color":"white"}

# 게임 규칙 안내
tellraw @a {"text":"【 게임 규칙 】","color":"gold","bold":true}
tellraw @a {"text":"• 다른 플레이어를 존중하세요","color":"green"}
tellraw @a {"text":"• 문파 간 협력을 장려합니다","color":"green"}
tellraw @a {"text":"• 심법 수련을 통해 성장하세요","color":"green"}
tellraw @a {"text":"","color":"white"}

# 도움말 안내
tellraw @a {"text":"도움이 필요하시면 /help 명령어를 사용하세요.","color":"yellow","italic":true} 