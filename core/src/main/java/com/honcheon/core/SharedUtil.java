package com.honcheon.core;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * 혼천 서버 공통 유틸리티 클래스
 * 모든 모듈에서 공통으로 사용하는 기능들을 제공합니다.
 */
public class SharedUtil {
    
    private static final Logger logger = Logger.getLogger("HoncheonCore");
    
    /**
     * 메시지에 색상 코드를 적용합니다.
     * @param message 원본 메시지
     * @return 색상이 적용된 메시지
     */
    public static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    /**
     * 설정 파일을 로드합니다.
     * @param file 설정 파일
     * @return FileConfiguration 객체
     */
    public static FileConfiguration loadConfig(File file) {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                logger.severe("설정 파일을 생성할 수 없습니다: " + e.getMessage());
                return null;
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }
    
    /**
     * 설정 파일을 저장합니다.
     * @param config 저장할 설정
     * @param file 저장할 파일
     * @return 저장 성공 여부
     */
    public static boolean saveConfig(FileConfiguration config, File file) {
        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            logger.severe("설정 파일을 저장할 수 없습니다: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 로그 메시지를 출력합니다.
     * @param level 로그 레벨
     * @param message 메시지
     */
    public static void log(String level, String message) {
        String formattedMessage = "[혼천] " + message;
        switch (level.toLowerCase()) {
            case "info":
                logger.info(formattedMessage);
                break;
            case "warning":
            case "warn":
                logger.warning(formattedMessage);
                break;
            case "error":
            case "severe":
                logger.severe(formattedMessage);
                break;
            default:
                logger.info(formattedMessage);
                break;
        }
    }
    
    /**
     * 문자열이 null이거나 비어있는지 확인합니다.
     * @param str 확인할 문자열
     * @return null이거나 비어있으면 true
     */
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * 숫자 문자열인지 확인합니다.
     * @param str 확인할 문자열
     * @return 숫자이면 true
     */
    public static boolean isNumeric(String str) {
        if (isEmpty(str)) {
            return false;
        }
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
} 