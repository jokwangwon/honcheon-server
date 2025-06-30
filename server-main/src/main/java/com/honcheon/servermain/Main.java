package com.honcheon.servermain;

import com.honcheon.core.SharedUtil;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * 혼천 서버 메인 월드 플러그인
 * 동방 신선 세계를 배경으로 한 마인크래프트 서버의 메인 월드 기능을 담당합니다.
 */
public class Main extends JavaPlugin {
    
    private static Main instance;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // 플러그인 활성화 메시지
        SharedUtil.log("info", "메인 월드 서버가 활성화되었습니다!");
        getLogger().info(ChatColor.GREEN + "혼천 서버 메인 월드가 활성화되었습니다!");
        getLogger().info(ChatColor.YELLOW + "동방 신선 세계에 오신 것을 환영합니다.");
        
        // 설정 파일 로드
        loadConfig();
        
        // 명령어 등록
        registerCommands();
        
        // 이벤트 리스너 등록
        registerEventListeners();
        
        // 시스템 초기화
        initializeSystems();
        
        SharedUtil.log("info", "메인 월드 서버 초기화가 완료되었습니다!");
    }
    
    @Override
    public void onDisable() {
        // 플러그인 비활성화 메시지
        SharedUtil.log("info", "메인 월드 서버가 비활성화되었습니다.");
        getLogger().info(ChatColor.RED + "혼천 서버 메인 월드가 비활성화되었습니다.");
        
        // 데이터 저장
        saveData();
        
        getLogger().info(ChatColor.YELLOW + "혼천 서버 메인 월드가 안전하게 종료되었습니다.");
    }
    
    /**
     * 설정 파일을 로드합니다.
     */
    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        SharedUtil.log("info", "설정 파일이 로드되었습니다.");
    }
    
    /**
     * 명령어를 등록합니다.
     */
    private void registerCommands() {
        // 문파 관련 명령어
        getCommand("faction").setExecutor(new FactionCommand());
        
        // 심법 관련 명령어
        getCommand("cultivation").setExecutor(new CultivationCommand());
        
        // 스킬 관련 명령어
        getCommand("skill").setExecutor(new SkillCommand());
        
        SharedUtil.log("info", "명령어가 등록되었습니다.");
    }
    
    /**
     * 이벤트 리스너를 등록합니다.
     */
    private void registerEventListeners() {
        // 플레이어 관련 이벤트
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);
        
        // 문파 관련 이벤트
        Bukkit.getPluginManager().registerEvents(new FactionListener(), this);
        
        // 심법 관련 이벤트
        Bukkit.getPluginManager().registerEvents(new CultivationListener(), this);
        
        SharedUtil.log("info", "이벤트 리스너가 등록되었습니다.");
    }
    
    /**
     * 시스템을 초기화합니다.
     */
    private void initializeSystems() {
        // 문파 시스템 초기화
        FactionManager.getInstance().initialize();
        
        // 심법 시스템 초기화
        CultivationManager.getInstance().initialize();
        
        // 스킬 시스템 초기화
        SkillManager.getInstance().initialize();
        
        SharedUtil.log("info", "시스템이 초기화되었습니다.");
    }
    
    /**
     * 데이터를 저장합니다.
     */
    private void saveData() {
        FactionManager.getInstance().saveData();
        CultivationManager.getInstance().saveData();
        SkillManager.getInstance().saveData();
        SharedUtil.log("info", "데이터가 저장되었습니다.");
    }
    
    /**
     * 플러그인 인스턴스를 반환합니다.
     * @return Main 인스턴스
     */
    public static Main getInstance() {
        return instance;
    }
} 