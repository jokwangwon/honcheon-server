package com.honcheon.bot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 초기화 업무가 요구하는 백업, 범위 삭제, 감사 기록 포트. */
interface ResetStore extends TransactionRunner {
    Optional<String> discordOfMc(String mcUuid) throws Exception;

    List<Long> characterIdsOf(String discordId) throws Exception;

    List<String> mcUuidsOf(long characterId) throws Exception;

    String snapshotFileName();

    String restoreInstructions(Path snapshot);

    void snapshotTo(Path target) throws Exception;

    boolean hasTable(String table) throws Exception;

    List<Map<String, Object>> rowsOf(String table, String column, Object value, String extraWhere)
            throws Exception;

    int deleteRows(String table, String column, Object value, String extraWhere) throws Exception;

    void logEvent(String type, String actorType, String actorId, String targetType,
                  String targetId, Map<String, Object> data) throws Exception;
}
