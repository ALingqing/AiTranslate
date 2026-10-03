package cn.aqcraft.aitranslate.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 配置文件管理：把 jar 内的默认 config.yml 复制到插件目录并加载。
 */
public class ConfigManager {

    private final File pluginDir;
    private YamlConfiguration config;

    public ConfigManager(File pluginDir) {
        this.pluginDir = pluginDir;
    }

    public void load() {
        File file = new File(pluginDir, "config.yml");
        if (!file.exists()) {
            pluginDir.mkdirs();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) java.nio.file.Files.copy(in, file.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
        // 以 jar 内默认值为底，保证新增字段有默认值
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(def);
            }
        } catch (IOException ignored) {}
    }

    public YamlConfiguration get() { return config; }

    public String getOpenAiBaseUrl() { return config.getString("openai.base-url", "https://ai.furry.vg/v1"); }
    public String getOpenAiApiKey() { return config.getString("openai.api-key", ""); }
    public String getOpenAiModel() { return config.getString("openai.model", "gpt-4o-mini"); }
    public int getOpenAiTimeoutMs() { return config.getInt("openai.timeout-ms", 15000); }
    public int getMaxChars() { return config.getInt("openai.max-chars", 400); }

    public boolean isEnabled() { return config.getBoolean("behavior.enabled", true); }
    public boolean keepFormatting() { return config.getBoolean("behavior.keep-formatting", true); }
    public boolean translateItems() { return config.getBoolean("behavior.translate-items", true); }
    public boolean showOriginalBrackets() { return config.getBoolean("behavior.show-original-brackets", false); }
    /** 目标语言 == 默认语言时跳过翻译（显示原文）；默认关闭，中文玩家也能看到外语消息的译文 */
    public boolean skipDefaultLanguage() { return config.getBoolean("behavior.skip-default-language", false); }
    public long getMaxWaitMs() { return Math.max(1000L, config.getLong("behavior.max-wait-ms", 8000L)); }

    public int getCacheMaxSizePerLanguage() { return config.getInt("cache.max-size-per-language", 2000); }
    public int getMaxParallelRequests() { return config.getInt("threads.max-parallel-requests", 4); }

    /** 翻译源尝试顺序（openai / baidu / youdao） */
    public java.util.List<String> getProviderOrder() {
        java.util.List<String> list = config.getStringList("providers.order");
        return list.isEmpty() ? java.util.List.of("openai") : list;
    }

    public int getProviderTimeoutMs() { return Math.max(1000, config.getInt("providers.timeout-ms", 5000)); }
    public String getBaiduAppid() { return config.getString("baidu.appid", ""); }
    public String getBaiduSecret() { return config.getString("baidu.secret", ""); }
    public String getYoudaoAppid() { return config.getString("youdao.appid", ""); }
    public String getYoudaoSecret() { return config.getString("youdao.secret", ""); }

    public String getDefaultLanguage() { return config.getString("default-language", "zh_cn"); }
}
