package com.qingyu.aitranslate.translate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存翻译缓存。键 = (语言id, 原文)，值 = 译文。
 * 每语言一个带容量上限的简单 LRU Map。
 */
public class TranslationCache {

    private final Map<String, Map<String, String>> byLang;
    private final int maxPerLang;

    public TranslationCache(int maxPerLang) {
        this.maxPerLang = maxPerLang;
        this.byLang = new ConcurrentHashMap<>();
    }

    private Map<String, String> langMap(String lang) {
        return byLang.computeIfAbsent(lang, k ->
                new LinkedHashMap<>(maxPerLang, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > maxPerLang;
                    }
                });
    }

    public String get(String lang, String source) {
        Map<String, String> m = byLang.get(lang);
        if (m == null) return null;
        synchronized (m) {
            return m.get(source);
        }
    }

    public void put(String lang, String source, String translated) {
        Map<String, String> m = langMap(lang);
        synchronized (m) {
            m.put(source, translated);
        }
    }

    public void clear() {
        byLang.clear();
    }

    public int size() {
        int n = 0;
        for (Map<String, String> m : byLang.values()) n += m.size();
        return n;
    }
}
