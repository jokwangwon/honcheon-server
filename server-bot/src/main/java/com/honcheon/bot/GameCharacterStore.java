package com.honcheon.bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 캐릭터 생애, 시트, 전장 예치를 다루는 저장소 포트. */
interface GameCharacterStore {
    Optional<Map<String, Object>> findCharacter(String discordId) throws Exception;

    List<Map<String, Object>> activeCharacters() throws Exception;

    long createCharacter(String discordId, String name, Map<String, Object> sheet, int wallet)
            throws Exception;

    Optional<Map<String, Object>> findCharacterById(long id) throws Exception;

    void updateCharacter(long id, Map<String, Object> sheet, int wallet,
                         String realm, String status, String location) throws Exception;

    void killCharacter(long id, int day) throws Exception;

    Optional<Map<String, Object>> lastDeadCharacter(String discordId) throws Exception;

    void setLineage(long id, long ancestorId) throws Exception;

    int bankBalance(long characterId, String branch) throws Exception;

    int bankMove(long characterId, String branch, int delta) throws Exception;

    void setHeir(long characterId, String branch, String heir) throws Exception;

    Optional<String> heirHint(long characterId, String branch) throws Exception;
}
