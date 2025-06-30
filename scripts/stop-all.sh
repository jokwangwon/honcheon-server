#!/bin/bash

# 혼천 서버 전체 종료 스크립트

echo "=== 혼천 서버 전체 종료 ==="
echo ""

# Java 프로세스 확인
echo "실행 중인 Java 프로세스를 확인합니다..."
JAVA_PROCESSES=$(ps aux | grep java | grep -v grep)

if [ -z "$JAVA_PROCESSES" ]; then
    echo "실행 중인 Java 프로세스가 없습니다."
    exit 0
fi

echo "실행 중인 Java 프로세스:"
echo "$JAVA_PROCESSES"
echo ""

# 종료 확인
read -p "모든 서버를 종료하시겠습니까? (y/N): " confirm
if [[ $confirm != [yY] && $confirm != [yY][eE][sS] ]]; then
    echo "종료가 취소되었습니다."
    exit 0
fi

echo ""
echo "서버를 종료합니다..."

# BungeeCord 프로세스 종료
echo "BungeeCord 프로세스를 종료합니다..."
pkill -f "BungeeCord.jar"
sleep 2

# Spigot/Paper 서버 프로세스 종료
echo "Spigot/Paper 서버 프로세스를 종료합니다..."
pkill -f "server.jar"
sleep 2

# 남은 Java 프로세스 강제 종료
echo "남은 Java 프로세스를 강제 종료합니다..."
pkill -9 -f "java.*minecraft"
sleep 1

# 종료 확인
REMAINING_PROCESSES=$(ps aux | grep java | grep -v grep)
if [ -z "$REMAINING_PROCESSES" ]; then
    echo "모든 서버가 성공적으로 종료되었습니다."
else
    echo "경고: 일부 프로세스가 여전히 실행 중입니다:"
    echo "$REMAINING_PROCESSES"
    echo ""
    read -p "강제로 종료하시겠습니까? (y/N): " force_kill
    if [[ $force_kill == [yY] || $force_kill == [yY][eE][sS] ]]; then
        pkill -9 -f java
        echo "모든 Java 프로세스를 강제 종료했습니다."
    fi
fi

echo ""
echo "혼천 서버 전체 종료가 완료되었습니다." 