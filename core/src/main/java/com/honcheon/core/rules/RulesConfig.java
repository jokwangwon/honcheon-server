package com.honcheon.core.rules;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 룰 엔진 공용 YAML 로더.
 * config/*.yml 이 단일 진실 원천이다 — 엔진은 수치를 하드코딩하지 않는다.
 */
public final class RulesConfig {

    private RulesConfig() {
    }

    public static Map<String, Object> load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("설정 파일을 읽을 수 없습니다: " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            throw new IllegalArgumentException("설정 섹션이 없습니다: " + key);
        }
        return (Map<String, Object>) value;
    }

    public static int intValue(Object value) {
        return ((Number) value).intValue();
    }
}
