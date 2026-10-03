package cn.aqcraft.aitranslate.chat;

import cn.aqcraft.aitranslate.AiTranslatePlugin;
import cn.aqcraft.aitranslate.lang.Language;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 公屏聊天监听：
 * 一条消息 -> 按「每个观众自己的语言」并发翻译 -> 各观众看到各自语言的译文。
 * 发送者自己始终看到原文；非玩家观众（控制台）也看原文。
 * 渲染使用事件自带的 ChatRenderer，尽量保留原有的名字/前缀渲染。
 */
public class ChatListener implements Listener {

    private final AiTranslatePlugin plugin;

    public ChatListener(AiTranslatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        var cfg = plugin.getConfigManager();
        if (!cfg.isEnabled() || !plugin.getTranslator().hasUsableProvider()) return;

        Player sender = event.getPlayer();
        Component original = event.message();

        Language defaultLang = plugin.getLanguageManager().getDefaultLanguage();
        boolean skipDefault = cfg.skipDefaultLanguage() && defaultLang != null;

        // 1. 收集观众需要的语言（排除发送者，de-dup）
        //    菜单/命令手动选择过语言的观众：永远全量翻译成所选语言，不受跳过设置影响；
        //    跳过仅作用于“跟随客户端语言”的默认语言观众（省费用）。
        Map<String, Language> needed = new HashMap<>();
        for (Audience audience : event.viewers()) {
            if (!(audience instanceof Player viewer)) continue;
            if (viewer.getUniqueId().equals(sender.getUniqueId())) continue;
            Language lang = plugin.getLanguageManager().resolveFor(viewer);
            if (lang == null) continue;
            boolean explicit = plugin.getLanguageManager().hasOverride(viewer.getUniqueId());
            if (skipDefault && !explicit && lang.getId().equals(defaultLang.getId())) continue;
            needed.putIfAbsent(lang.getId(), lang);
        }
        if (needed.isEmpty()) return; // 没有需要翻译的观众

        // 2. 并发翻译（每条消息最多等 max-wait-ms，超时未完成的观众看原文）
        Map<String, Component> translated = new ConcurrentHashMap<>();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[needed.size()];
        int i = 0;
        for (Language lang : needed.values()) {
            futures[i++] = CompletableFuture.runAsync(() -> {
                Component result = plugin.getComponentTranslator().translate(original, lang);
                if (result != null) translated.put(lang.getId(), result);
            }, plugin.getTranslatePool());
        }
        try {
            CompletableFuture.allOf(futures).get(cfg.getMaxWaitMs(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}

        // 3. 按观众的各自语言渲染（renderer 是同步的，只读已翻译好的结果）
        //    包一层服务器当前 renderer，保留其他插件设置的名字/前缀等聊天格式
        ChatRenderer inner = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component msg = message;
            if (viewer instanceof Player target && !target.getUniqueId().equals(source.getUniqueId())) {
                Language lang = plugin.getLanguageManager().resolveFor(target);
                if (lang != null) {
                    Component tr = translated.get(lang.getId());
                    if (tr != null) msg = tr;
                }
            }
            return inner.render(source, sourceDisplayName, msg, viewer);
        });
    }
}
