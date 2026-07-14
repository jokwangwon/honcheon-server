package com.honcheon.bot;

import java.util.Optional;

/** 부트스트랩과 스케줄러가 세계 메타데이터를 읽는 최소 포트. */
interface WorldMetaReader {
    Optional<String> getMeta(String key) throws Exception;
}
