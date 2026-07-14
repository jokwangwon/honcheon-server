package com.honcheon.bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 가문 한 채와 그 구성원을 다루는 저장소 포트. */
interface HouseStore {
    List<HouseEntry> housesOf(String family) throws Exception;

    long createHouse(String family, String name, String region, String state, int day)
            throws Exception;

    void setHouse(long characterId, long houseId) throws Exception;

    Optional<HouseEntry> house(long houseId) throws Exception;

    Long houseOfCharacter(long characterId) throws Exception;

    List<Map<String, Object>> houseMembers(long houseId) throws Exception;
}
