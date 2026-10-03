package cn.aqcraft.aitranslate.lang;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语言管理与玩家偏好。
 * 玩家偏好：默认 AUTO（跟随客户端语言 getLocale），也可在菜单里指定某一语言覆盖。
 */
public class LanguageManager {

    private final Map<String, Language> languages = new LinkedHashMap<>();
    private final String defaultLang;
    private Language defaultLanguage;

    /** 玩家 UUID -> 语言 id（null 或 "AUTO" 表示跟随客户端语言） */
    private final Map<UUID, String> playerOverride = new ConcurrentHashMap<>();

    public LanguageManager(String defaultLang) {
        this.defaultLang = defaultLang;
    }

    /** 从插件目录加载 languages.yml（不存在则从 jar 复制） */
    public void loadFromFile(File pluginDir) {
        languages.clear();
        File file = new File(pluginDir, "languages.yml");
        if (!file.exists()) {
            pluginDir.mkdirs();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("languages.yml")) {
                if (in != null) java.nio.file.Files.copy(in, file.toPath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("languages.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(def);
            }
        } catch (Exception ignored) {}

        ConfigurationSection sec = cfg.getConfigurationSection("languages");
        if (sec != null) {
            // /aitr setskull 写入的头像覆盖（不破坏 languages.yml 注释）
            java.util.Map<String, String> skullOverrides = new java.util.HashMap<>();
            File skullsFile = new File(pluginDir, "skull-urls.yml");
            if (skullsFile.exists()) {
                YamlConfiguration sc = YamlConfiguration.loadConfiguration(skullsFile);
                for (String k : sc.getKeys(false)) {
                    String v = sc.getString(k);
                    if (v != null && !v.isEmpty()) skullOverrides.put(k, v);
                }
            }
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                String display = s.getString("display", id);
                String flag = s.getString("flag-name", "GREEN_CONCRETE");
                String skull = s.getString("skull", "");
                if (skull == null || skull.isEmpty()) skull = skullOverrides.getOrDefault(id, "");
                List<String> locales = s.getStringList("locales");
                String prompt = s.getString("prompt", "Translate to " + display + ":\n{text}");
                String skipRegex = s.getString("skip-regex", "");
                java.util.regex.Pattern skipPattern = null;
                if (skipRegex != null && !skipRegex.isEmpty()) {
                    try {
                        skipPattern = java.util.regex.Pattern.compile(skipRegex);
                    } catch (Exception ex) {
                        skipPattern = null; // 非法正则忽略
                    }
                }
                languages.put(id, new Language(id, display, flag, skull, locales, prompt, skipPattern));
            }
        }
        defaultLanguage = languages.get(defaultLang);
        if (defaultLanguage == null && !languages.isEmpty()) {
            defaultLanguage = languages.values().iterator().next();
        }
    }

    public Map<String, Language> getLanguages() { return languages; }
    public Language getDefaultLanguage() { return defaultLanguage; }

    /** 按 locale 精确/前缀匹配语言，找不到返回 null */
    public Language matchLocale(String locale) {
        if (locale == null) return null;
        String lower = locale.toLowerCase();
        // 精确匹配
        for (Language lang : languages.values()) {
            if (lang.getLocales().stream().anyMatch(l -> l.equalsIgnoreCase(lower))) return lang;
        }
        // 前缀匹配（如 zh_CN -> zh_cn；如果 locale 是 "zh" 单独）
        int us = lower.indexOf('_');
        String langPart = us >= 0 ? lower.substring(0, us) : lower;
        for (Language lang : languages.values()) {
            for (String l : lang.getLocales()) {
                int lu = l.indexOf('_');
                String lp = lu >= 0 ? l.substring(0, lu) : l;
                if (langPart.equalsIgnoreCase(lp) && !langPart.isEmpty()) return lang;
            }
        }
        return null;
    }

    // ===== 玩家偏好 =====

    public void setOverride(UUID uuid, String langId) {
        if (langId == null || langId.equalsIgnoreCase("AUTO")) {
            playerOverride.remove(uuid);
        } else {
            playerOverride.put(uuid, langId);
        }
    }

    /** 是否指定了语言（非 AUTO） */
    public boolean hasOverride(UUID uuid) {
        return playerOverride.containsKey(uuid);
    }

    public String getOverride(UUID uuid) {
        return playerOverride.get(uuid);
    }

    /**
     * 解析某玩家最终应使用的语言：
     * 若有菜单指定 -> 用之；否则跟随客户端语言 getLocale() 匹配；再兜底 default。
     */
    public Language resolveFor(Player player) {
        String override = playerOverride.get(player.getUniqueId());
        if (override != null) {
            Language lang = languages.get(override);
            if (lang != null) return lang;
        }
        Language matched = matchLocale(player.getLocale());
        if (matched != null) return matched;
        return defaultLanguage;
    }

    /** 解析某 locale 对应的语言，供测试/非玩家场景 */
    public Language resolveForLocale(String locale) {
        Language matched = matchLocale(locale);
        return matched != null ? matched : defaultLanguage;
    }

    public List<Language> ordered() {
        return new ArrayList<>(languages.values());
    }
}
