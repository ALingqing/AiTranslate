package cn.aqcraft.aitranslate.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cn.aqcraft.aitranslate.config.ConfigManager;
import cn.aqcraft.aitranslate.lang.Language;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * OpenAI 兼容 Chat Completions 翻译器。
 * 用 JDK HttpClient 直接调用，不依赖第三方库。
 * 通过信号量限制并发请求数，避免打到中转站被限流。
 */
public class Translator {

    private final ConfigManager cfg;
    private final TranslationCache cache;
    private final HttpClient http;
    private final Semaphore semaphore;
    private final boolean debug;

    public Translator(ConfigManager cfg, TranslationCache cache) {
        this.cfg = cfg;
        this.cache = cache;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        // 非公平信号量，允许多个不同语言的并发，但设上限
        this.semaphore = new Semaphore(Math.max(1, cfg.getMaxParallelRequests()));
        this.debug = false;
    }

    /**
     * 翻译一段文本到指定语言。先查缓存。
     * 同步阻塞直到结果返回（由 ChatListener 在独立线程调用）。
     * @return 译文；失败返回 null
     */
    public String translate(String source, Language lang) {
        if (source == null || source.isEmpty()) return source;
        if (source.length() > cfg.getMaxChars()) return null; // 超长不翻
        String key = lang.getId();

        String cached = cache.get(key, source);
        if (cached != null) return cached;

        String result = null;
        try {
            semaphore.acquire();
            try {
                result = callApi(source, lang);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (result != null) {
            cache.put(key, source, result);
        }
        return result;
    }

    private String callApi(String source, Language lang) {
        String apiKey = cfg.getOpenAiApiKey();
        if (apiKey == null || apiKey.isEmpty()) return null;

        String base = cfg.getOpenAiBaseUrl();
        String url = base;
        if (!base.endsWith("/")) url = base + "/";
        url += "chat/completions";

        String sysPrompt = lang.getPrompt().replace("{text}", source);

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.getOpenAiModel());
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", 600);
        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", sysPrompt);
        messages.add(sys);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", source);
        messages.add(user);

        body.add("messages", messages);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(cfg.getOpenAiTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return null;
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) return null;
            String content = message.get("content").getAsString();
            if (content == null) return null;
            return content.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public TranslationCache getCache() { return cache; }

    /** 是否还没有配置 API Key（未配置时插件不应发请求） */
    public boolean isApiKeyMissing() {
        String apiKey = cfg.getOpenAiApiKey();
        return apiKey == null || apiKey.isEmpty();
    }

    /**
     * 查询当前 Key 可用的模型列表（GET /v1/models）。
     * 文档明确要求：模型 ID 以该接口返回为准，不要猜。
     */
    public java.util.List<String> listModels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String apiKey = cfg.getOpenAiApiKey();
        if (apiKey == null || apiKey.isEmpty()) return out;
        String base = cfg.getOpenAiBaseUrl();
        String url = base.endsWith("/") ? base + "models" : base + "/models";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(cfg.getOpenAiTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return out;
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray data = json.getAsJsonArray("data");
            if (data != null) {
                for (com.google.gson.JsonElement e : data) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                        out.add(e.getAsJsonObject().get("id").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }
}
