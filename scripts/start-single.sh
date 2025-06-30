#!/bin/bash

# 혼천 서버 단일 서버 실행 스크립트

echo "=== 혼천 서버 단일 모드 시작 ==="
echo "동방 신선 세계를 배경으로 한 마인크래프트 서버"
echo ""

# Java 버전 확인
echo "Java 버전 확인 중..."
java -version
if [ $? -ne 0 ]; then
    echo "오류: Java가 설치되지 않았습니다."
    exit 1
fi

# 서버 JAR 파일 확인
SERVER_JAR="server.jar"
if [ ! -f "$SERVER_JAR" ]; then
    echo "오류: $SERVER_JAR 파일을 찾을 수 없습니다."
    echo "Paper 또는 Spigot 서버 JAR 파일을 다운로드하여 이 디렉토리에 server.jar로 이름을 변경하세요."
    exit 1
fi

# 서버 설정 파일 확인
if [ ! -f "server.properties" ]; then
    echo "서버 설정 파일이 없습니다. 기본 설정으로 생성합니다..."
    cat > server.properties << EOF
# 혼천 서버 기본 설정 (단일 모드)
server-port=25565
server-ip=
max-players=20
gamemode=survival
difficulty=normal
pvp=true
spawn-protection=16
view-distance=10
simulation-distance=10
motd=혼천 서버 - 동방 신선 세계 (단일 모드)
enable-command-block=true
EOF
fi

# 플러그인 디렉토리 생성
if [ ! -d "plugins" ]; then
    mkdir plugins
    echo "plugins 디렉토리를 생성했습니다."
fi

# 데이터팩 디렉토리 생성
if [ ! -d "world/datapacks" ]; then
    mkdir -p world/datapacks
    echo "데이터팩 디렉토리를 생성했습니다."
fi

# 혼천 데이터팩 복사
if [ -d "datapack" ]; then
    cp -r datapack/* world/datapacks/
    echo "혼천 데이터팩을 복사했습니다."
fi

# 서버 시작
echo ""
echo "단일 서버를 시작합니다..."
echo "메모리: 2GB 할당"
echo "포트: 25565"
echo ""

# JVM 옵션 설정
JVM_OPTS="-Xms1G -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

# 서버 실행
java $JVM_OPTS -jar $SERVER_JAR nogui

echo ""
echo "단일 서버가 종료되었습니다." 