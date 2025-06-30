#!/bin/bash

# 혼천 서버 다중 서버 실행 스크립트 (BungeeCord/Velocity)

echo "=== 혼천 서버 다중 서버 모드 시작 ==="
echo "동방 신선 세계를 배경으로 한 마인크래프트 네트워크"
echo ""

# Java 버전 확인
echo "Java 버전 확인 중..."
java -version
if [ $? -ne 0 ]; then
    echo "오류: Java가 설치되지 않았습니다."
    exit 1
fi

# BungeeCord JAR 파일 확인
BUNGEE_JAR="bungee/BungeeCord.jar"
if [ ! -f "$BUNGEE_JAR" ]; then
    echo "오류: $BUNGEE_JAR 파일을 찾을 수 없습니다."
    echo "BungeeCord 또는 Velocity JAR 파일을 bungee 디렉토리에 다운로드하세요."
    exit 1
fi

# 서버 디렉토리 생성
mkdir -p hub
mkdir -p main
mkdir -p faction

echo "서버 디렉토리를 생성했습니다."

# 허브 서버 설정
if [ ! -f "hub/server.properties" ]; then
    echo "허브 서버 설정 파일을 생성합니다..."
    cat > hub/server.properties << EOF
# 혼천 서버 허브 설정
server-port=25566
server-ip=
max-players=50
gamemode=adventure
difficulty=peaceful
pvp=false
spawn-protection=0
view-distance=8
simulation-distance=8
motd=혼천 서버 - 허브 (동방 신선 세계의 중심지)
enable-command-block=true
EOF
fi

# 메인 서버 설정
if [ ! -f "main/server.properties" ]; then
    echo "메인 서버 설정 파일을 생성합니다..."
    cat > main/server.properties << EOF
# 혼천 서버 메인 월드 설정
server-port=25567
server-ip=
max-players=30
gamemode=survival
difficulty=normal
pvp=true
spawn-protection=16
view-distance=10
simulation-distance=10
motd=혼천 서버 - 메인 월드 (동방 신선 세계)
enable-command-block=true
EOF
fi

# 문파 서버 설정
if [ ! -f "faction/server.properties" ]; then
    echo "문파 서버 설정 파일을 생성합니다..."
    cat > faction/server.properties << EOF
# 혼천 서버 문파 전용 설정
server-port=25568
server-ip=
max-players=20
gamemode=survival
difficulty=normal
pvp=true
spawn-protection=16
view-distance=10
simulation-distance=10
motd=혼천 서버 - 문파 전용 (동방 신선 세계)
enable-command-block=true
EOF
fi

# BungeeCord 설정 파일 생성
if [ ! -f "bungee/config.yml" ]; then
    echo "BungeeCord 설정 파일을 생성합니다..."
    cat > bungee/config.yml << EOF
# 혼천 서버 BungeeCord 설정
groups:
  md_5:
    - admin
disabled_commands:
  - disabledcommandhere
servers:
  hub:
    motd1: "혼천 서버 - 허브"
    motd2: "동방 신선 세계의 중심지"
    address: localhost:25566
    restricted: false
  main:
    motd1: "혼천 서버 - 메인 월드"
    motd2: "동방 신선 세계"
    address: localhost:25567
    restricted: false
  faction:
    motd1: "혼천 서버 - 문파 전용"
    motd2: "동방 신선 세계"
    address: localhost:25568
    restricted: false
listeners:
  - query_port: 25577
    motd1: "혼천 서버"
    motd2: "동방 신선 세계"
    tab_list: GLOBAL_PING
    query_enabled: false
    proxy_protocol: false
    forced_hosts:
      pvp.md-5.net: pvp
    ping_passthrough: false
    priorities:
      - hub
      - main
      - faction
    bind_local_address: true
    host: 0.0.0.0:25565
    max_players: 1
    tab_size: 60
    force_default_server: false
connection_throttle: 4000
timeout: 30000
player_limit: -1
ip_forward: false
online_mode: true
EOF
fi

# 서버 시작
echo ""
echo "다중 서버를 시작합니다..."
echo "허브 서버: 포트 25566"
echo "메인 서버: 포트 25567"
echo "문파 서버: 포트 25568"
echo "프록시: 포트 25565"
echo ""

# JVM 옵션 설정
JVM_OPTS="-Xms512M -Xmx1G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

# BungeeCord 실행
echo "BungeeCord를 시작합니다..."
cd bungee
java $JVM_OPTS -jar BungeeCord.jar nogui &

echo ""
echo "다중 서버가 시작되었습니다."
echo "각 서버를 개별적으로 시작하려면 해당 디렉토리에서 start_server.sh를 실행하세요." 