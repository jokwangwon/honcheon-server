package com.honcheon.serverhub;

import com.honcheon.core.SharedUtil;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * 혼천 서버 허브/로비 플러그인
 * BungeeCord/Velocity 대응 가능한 허브 서버 기능을 담당합니다.
 */
public class HubMain extends JavaPlugin {
    
    private static HubMain instance;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // 플러그인 활성화 메시지
        SharedUtil.log("info", "허브 서버가 활성화되었습니다!");
        getLogger().info(ChatColor.GREEN + "혼천 서버 허브가 활성화되었습니다!");
        getLogger().info(ChatColor.YELLOW + "동방 신선 세계의 중심지에 오신 것을 환영합니다.");
        
        // 설정 파일 로드
        loadConfig();
        
        // 명령어 등록
        registerCommands();
        
        // 이벤트 리스너 등록
        registerEventListeners();
        
        // 허브 시스템 초기화
        initializeHubSystems();
        
        SharedUtil.log("info", "허브 서버 초기화가 완료되었습니다!");
    }
    
    @Override
    public void onDisable() {
        // 플러그인 비활성화 메시지
        SharedUtil.log("info", "허브 서버가 비활성화되었습니다.");
        getLogger().info(ChatColor.RED + "혼천 서버 허브가 비활성화되었습니다.");
        
        // 데이터 저장
        saveData();
        
        getLogger().info(ChatColor.YELLOW + "혼천 서버 허브가 안전하게 종료되었습니다.");
    }
    
    /**
     * 설정 파일을 로드합니다.
     */
    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        SharedUtil.log("info", "허브 설정 파일이 로드되었습니다.");
    }
    
    /**
     * 명령어를 등록합니다.
     */
    private void registerCommands() {
        // 서버 이동 명령어
        getCommand("server").setExecutor(new ServerCommand());
        
        // 문파 선택 명령어
        getCommand("selectfaction").setExecutor(new FactionSelectCommand());
        
        // 도움말 명령어
        getCommand("hubhelp").setExecutor(new HubHelpCommand());
        
        SharedUtil.log("info", "허브 명령어가 등록되었습니다.");
    }
    
    /**
     * 이벤트 리스너를 등록합니다.
     */
    private void registerEventListeners() {
        // 플레이어 접속 이벤트
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);
        
        // 플레이어 퇴장 이벤트
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
        
        // 서버 간 이동 이벤트
        Bukkit.getPluginManager().registerEvents(new ServerSwitchListener(), this);
        
        SharedUtil.log("info", "허브 이벤트 리스너가 등록되었습니다.");
    }
    
    /**
     * 허브 시스템을 초기화합니다.
     */
    private void initializeHubSystems() {
        // 서버 목록 관리자 초기화
        ServerManager.getInstance().initialize();
        
        // 문파 선택 시스템 초기화
        FactionSelectionManager.getInstance().initialize();
        
        // 로비 시스템 초기화
        LobbyManager.getInstance().initialize();
        
        SharedUtil.log("info", "허브 시스템이 초기화되었습니다.");
    }
    
    /**
     * 데이터를 저장합니다.
     */
    private void saveData() {
        ServerManager.getInstance().saveData();
        FactionSelectionManager.getInstance().saveData();
        LobbyManager.getInstance().saveData();
        SharedUtil.log("info", "허브 데이터가 저장되었습니다.");
    }
    
    /**
     * 플러그인 인스턴스를 반환합니다.
     * @return HubMain 인스턴스
     */
    public static HubMain getInstance() {
        return instance;
    }
} 