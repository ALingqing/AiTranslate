package cn.aqcraft.aitranslate.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cn.aqcraft.aitranslate.config.ConfigManager;
import cn.aqcraft.aitranslate.lang.Language;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多翻译源翻译器：按 providers.order 顺序依次尝试（openai / baidu / youdao），
 * 前一个失败（或未配置）自动切换到下一个。用 JDK HttpClient 直接调用，不依赖第三方库。
 * 通过信号量限制并发请求数，避免被各平台限流。
 */
public class Translator {

    private final ConfigManager cfg;
    private final TranslationCache cache;
    private final HttpClient http;
    private final Semaphore semaphore;
    private final boolean debug;

    /** 最近一次翻译失败的原因（供 /aitr test 与日志展示），成功时为 null */
    private volatile String lastError;
    /** 最近一次成功的翻译源名称（openai / baidu / youdao） */
    private volatile String lastProvider;
    private final java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastFailLogAt = 0L;

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

        String result = callChain(source, lang);
        if (result != null && !result.isEmpty()) {
            cache.put(key, source, result);
        }
        return result;
    }

    /** 按配置顺序依次尝试翻译源，返回第一个成功的结果；失败原因记录在 lastError */
    private String callChain(String source, Language lang) {
        for (String raw : cfg.getProviderOrder()) {
            String provider = raw.trim().toLowerCase();
            if (!isProviderReady(provider)) continue; // 未配置的源直接跳过
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "被中断";
                return null;
            }
            try {
                String result = switch (provider) {
                    case "openai" -> callOpenAi(source, lang);
                    case "baidu" -> callBaidu(source, lang);
                    case "youdao" -> callYoudao(source, lang);
                    default -> null;
                };
                if (result != null && !result.isEmpty()) {
                    lastProvider = provider;
                    lastError = null;
                    failCount.set(0);
                    return result;
                }
            } finally {
                semaphore.release();
            }
        }
        return null;
    }

    // ===== OpenAI 兼容接口 =====

    private String callOpenAi(String source, Language lang) {
        String apiKey = cfg.getOpenAiApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            lastError = "openai.api-key 未配置";
            return null;
        }

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
                noteFailure("[openai] HTTP " + resp.statusCode() + " " + snippet(resp.body()));
                return null;
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                noteFailure("[openai] 响应中没有 choices（检查模型 ID 是否正确）");
                return null;
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                noteFailure("[openai] 响应格式异常：缺少 message.content");
                return null;
            }
            String content = message.get("content").getAsString();
            if (content == null || content.isEmpty()) {
                noteFailure("[openai] 响应 message.content 为空");
                return null;
            }
            lastError = null;
            failCount.set(0);
            return content.trim();
        } catch (Exception e) {
            noteFailure("[openai] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    // ===== 百度翻译开放平台 =====

    private String callBaidu(String source, Language lang) {
        String appid = cfg.getBaiduAppid();
        String secret = cfg.getBaiduSecret();
        if (!notEmpty(appid) || !notEmpty(secret)) return null; // 未配置：跳过
        String to = baiduLang(lang.getId());
        if (to == null) {
            lastError = "[baidu] 不支持该语言: " + lang.getId();
            return null;
        }
        String salt = String.valueOf(System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(10000));
        String sign = md5(appid + source + salt + secret);
        String form = "q=" + enc(source) + "&from=auto&to=" + enc(to)
                + "&appid=" + enc(appid) + "&salt=" + enc(salt) + "&sign=" + enc(sign);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://fanyi-api.baidu.com/api/trans/vip/translate"))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .timeout(Duration.ofMillis(cfg.getProviderTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                noteFailure("[baidu] HTTP " + resp.statusCode() + " " + snippet(resp.body()));
                return null;
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (json.has("error_code")) {
                noteFailure("[baidu] " + json.get("error_code").getAsString() + " " + str(json, "error_msg"));
                return null;
            }
            JsonArray arr = json.getAsJsonArray("trans_result");
            if (arr == null || arr.size() == 0) {
                noteFailure("[baidu] 响应中没有 trans_result");
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (com.google.gson.JsonElement e : arr) {
                if (e.isJsonObject() && e.getAsJsonObject().has("dst")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(e.getAsJsonObject().get("dst").getAsString());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            noteFailure("[baidu] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    // ===== 有道智云 =====

    private String callYoudao(String source, Language lang) {
        String appid = cfg.getYoudaoAppid();
        String secret = cfg.getYoudaoSecret();
        if (!notEmpty(appid) || !notEmpty(secret)) return null; // 未配置：跳过
        String to = youdaoLang(lang.getId());
        if (to == null) {
            lastError = "[youdao] 不支持该语言: " + lang.getId();
            return null;
        }
        String salt = UUID.randomUUID().toString();
        String curtime = String.valueOf(System.currentTimeMillis() / 1000L);
        String sign = sha256(appid + truncate(source) + salt + curtime + secret);
        String form = "q=" + enc(source) + "&from=auto&to=" + enc(to)
                + "&appKey=" + enc(appid) + "&salt=" + enc(salt) + "&sign=" + enc(sign)
                + "&signType=v3&curtime=" + enc(curtime);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://openapi.youdao.com/api"))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .timeout(Duration.ofMillis(cfg.getProviderTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                noteFailure("[youdao] HTTP " + resp.statusCode() + " " + snippet(resp.body()));
                return null;
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String code = str(json, "errorCode");
            if (code != null && !"0".equals(code)) {
                noteFailure("[youdao] errorCode=" + code);
                return null;
            }
            JsonArray arr = json.getAsJsonArray("translation");
            if (arr == null || arr.size() == 0) {
                noteFailure("[youdao] 响应中没有 translation");
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (com.google.gson.JsonElement e : arr) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getAsString());
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            noteFailure("[youdao] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static String truncate(String q) {
        int len = q.length();
        if (len <= 20) return q;
        return q.substring(0, 10) + len + q.substring(len - 10);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String md5(String s) {
        return hex("MD5", s);
    }

    private static String sha256(String s) {
        return hex("SHA-256", s);
    }

    private static String hex(String algo, String s) {
        try {
            byte[] bytes = MessageDigest.getInstance(algo).digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 项目语言 id -> 百度语言码；不支持返回 null */
    private static String baiduLang(String id) {
        return switch (id) {
            case "zh_cn" -> "zh";
            case "zh_tw" -> "cht";
            case "en_us" -> "en";
            case "ja_jp" -> "jp";
            case "ko_kr" -> "kor";
            case "fr_fr" -> "fra";
            case "es_es" -> "spa";
            case "pt_br" -> "pt";
            case "it_it" -> "it";
            case "de_de" -> "de";
            case "ru_ru" -> "ru";
            case "ar_sa" -> "ara";
            case "th_th" -> "th";
            case "vi_vn" -> "vie";
            case "nl_nl" -> "nl";
            case "pl_pl" -> "pl";
            case "el_gr" -> "el";
            case "bg_bg" -> "bul";
            case "et_ee" -> "est";
            case "da_dk" -> "dan";
            case "fi_fi" -> "fin";
            case "cs_cz" -> "cs";
            case "ro_ro" -> "rom";
            case "sl_si" -> "slo";
            case "sv_se" -> "swe";
            case "hu_hu" -> "hu";
            default -> null;
        };
    }

    /** 项目语言 id -> 有道语言码；不支持返回 null */
    private static String youdaoLang(String id) {
        return switch (id) {
            case "zh_cn" -> "zh-CHS";
            case "zh_tw" -> "zh-CHT";
            case "en_us" -> "en";
            case "ja_jp" -> "ja";
            case "ko_kr" -> "ko";
            case "fr_fr" -> "fr";
            case "es_es" -> "es";
            case "pt_br" -> "pt";
            case "it_it" -> "it";
            case "de_de" -> "de";
            case "ru_ru" -> "ru";
            case "ar_sa" -> "ar";
            case "th_th" -> "th";
            case "vi_vn" -> "vi";
            case "nl_nl" -> "nl";
            case "pl_pl" -> "pl";
            case "id_id" -> "id";
            case "hi_in" -> "hi";
            default -> null;
        };
    }

    /** 最近一次翻译失败的原因（成功时为 null） */
    public String getLastError() { return lastError; }

    /** 最近一次成功的翻译源名称（openai / baidu / youdao） */
    public String getLastProvider() { return lastProvider; }

    /** /aitr test 专用：绕过缓存同步走一次完整翻译源链；失败返回 null（原因见 getLastError） */
    public String testCall(String source, Language lang) {
        lastError = null;
        return callChain(source, lang);
    }

    /** 指定翻译源是否已配置可用 */
    public boolean isProviderReady(String provider) {
        return switch (provider == null ? "" : provider.trim().toLowerCase()) {
            case "openai" -> !isApiKeyMissing();
            case "baidu" -> notEmpty(cfg.getBaiduAppid()) && notEmpty(cfg.getBaiduSecret());
            case "youdao" -> notEmpty(cfg.getYoudaoAppid()) && notEmpty(cfg.getYoudaoSecret());
            default -> false;
        };
    }

    /** 是否至少有一个可用的翻译源（没有任何可用源时插件跳过翻译） */
    public boolean hasUsableProvider() {
        for (String p : cfg.getProviderOrder()) {
            if (isProviderReady(p)) return true;
        }
        return false;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private void noteFailure(String reason) {
        lastError = reason;
        int c = failCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (c <= 5 || now - lastFailLogAt > 60_000L) {
            lastFailLogAt = now;
            Bukkit.getLogger().warning("[AiTranslate] 翻译失败: " + reason);
        }
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 180 ? t.substring(0, 180) + "..." : t;
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
            if (resp.statusCode() != 200) {
                lastError = "HTTP " + resp.statusCode() + " " + snippet(resp.body());
                return out;
            }
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
