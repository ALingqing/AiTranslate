package cn.aqcraft.aitranslate.chat;

import cn.aqcraft.aitranslate.config.ConfigManager;
import cn.aqcraft.aitranslate.lang.Language;
import cn.aqcraft.aitranslate.translate.Translator;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归翻译聊天组件：
 * - 普通文本节点 -> 逐节点翻译（保留颜色 / 格式 / children 结构）
 * - 原版翻译键（TranslatableComponent）-> 保留 key（客户端会自动本地化），只翻译参数
 * - 物品 hover（ShowItem）-> 翻译物品自定义名与介绍（lore），
 *   同时兼容 1.20.5+ 的 components 结构与旧版 tag.display 结构
 */
public class ComponentTranslator {

    /** SNBT 中一个形如 key:"value" 或 key:[...] 的字段（值只捕获引号字符串或方括号数组） */
    private static final Pattern SNBT_FIELD =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_.:]*):(\\[[^\\[\\]]*\\]|\"(?:\\\\.|[^\"\\\\])*\")");
    /** SNBT / JSON 里的双引号字符串字面量 */
    private static final Pattern QUOTED_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    private final Translator translator;
    private final ConfigManager cfg;

    public ComponentTranslator(Translator translator, ConfigManager cfg) {
        this.translator = translator;
        this.cfg = cfg;
    }

    public Component translate(Component source, Language lang) {
        return translateComponent(source, lang);
    }

    // ===== 组件递归 =====

    private Component translateComponent(Component c, Language lang) {
        Style style = translateStyle(c.style(), lang);
        Component rebuilt;
        if (c instanceof TextComponent tc) {
            rebuilt = Component.text(translateText(tc.content(), lang));
        } else if (c instanceof TranslatableComponent tc) {
            List<Component> args = new ArrayList<>(tc.args().size());
            for (Component arg : tc.args()) args.add(translateComponent(arg, lang));
            rebuilt = Component.translatable(tc.key()).args(args);
        } else {
            rebuilt = c;
        }
        rebuilt = rebuilt.style(style);
        List<Component> children = c.children();
        if (!children.isEmpty()) {
            List<Component> newChildren = new ArrayList<>(children.size());
            for (Component child : children) newChildren.add(translateComponent(child, lang));
            rebuilt = rebuilt.children(newChildren);
        }
        return rebuilt;
    }

    private Style translateStyle(Style style, Language lang) {
        if (style == null || style.isEmpty() || !cfg.translateItems()) return style;
        HoverEvent<?> hover = style.hoverEvent();
        if (hover == null || hover.action() != HoverEvent.Action.SHOW_ITEM) return style;
        if (!(hover.value() instanceof HoverEvent.ShowItem showItem)) return style;
        BinaryTagHolder nbt = showItem.nbt();
        if (nbt == null) return style;
        String newSnbt = translateSnbt(nbt.string(), lang);
        if (newSnbt == null) return style;
        HoverEvent<HoverEvent.ShowItem> newHover = HoverEvent.showItem(
                HoverEvent.ShowItem.of(showItem.item(), showItem.count(), BinaryTagHolder.of(newSnbt)));
        return style.hoverEvent(newHover);
    }

    // ===== 物品 SNBT =====

    /**
     * 翻译物品 NBT 的 SNBT 文本中名称 / 介绍字段。只处理 custom_name / item_name / Name / Lore 等
     * 键，值是 JSON 文本组件或 JSON 数组。未发现可翻译字段时返回 null。
     */
    private String translateSnbt(String snbt, Language lang) {
        if (snbt == null || snbt.isEmpty()) return null;
        StringBuilder out = new StringBuilder(snbt.length());
        Matcher m = SNBT_FIELD.matcher(snbt);
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String key = m.group(1);
            if (!isNameLoreKey(key)) continue;
            String raw = m.group(2);
            String newValue = translateSnbtValue(raw, lang);
            if (newValue != null && !newValue.equals(raw)) {
                out.append(snbt, last, m.start()).append(key).append(':').append(newValue);
                last = m.end();
                changed = true;
            }
        }
        if (!changed) return null;
        out.append(snbt, last, snbt.length());
        return out.toString();
    }

    private static boolean isNameLoreKey(String key) {
        return key.equals("minecraft:custom_name") || key.equals("minecraft:item_name")
                || key.equals("minecraft:lore") || key.equals("Name") || key.equals("Lore")
                || key.endsWith("display.Name") || key.endsWith("display.Lore");
    }

    /**
     * 翻译一个 SNBT 字段值：单个 JSON 字符串或 JSON 字符串数组。
     * 返回新值（带引号/括号），未变化时返回 null。
     */
    private String translateSnbtValue(String raw, Language lang) {
        if (raw.startsWith("[")) {
            Matcher m = QUOTED_STRING.matcher(raw);
            StringBuilder sb = new StringBuilder(raw.length());
            int last = 0;
            boolean changed = false;
            while (m.find()) {
                sb.append(raw, last, m.start());
                String json = unescapeJson(m.group());
                String tr = translateJson(json, lang);
                if (tr != null && !tr.equals(json)) {
                    sb.append(quoteJson(tr));
                    changed = true;
                } else {
                    sb.append(m.group());
                }
                last = m.end();
            }
            sb.append(raw, last, raw.length());
            return changed ? sb.toString() : null;
        }
        String json = unescapeJson(raw);
        String tr = translateJson(json, lang);
        return (tr != null && !tr.equals(json)) ? quoteJson(tr) : null;
    }

    private static String quoteJson(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unescapeJson(String quoted) {
        // 去掉首尾引号并把 \" 还原为 "，\\ 还原为 \
        String inner = quoted.substring(1, quoted.length() - 1);
        return inner.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** JSON 文本组件 -> 翻译 -> JSON；解析失败返回 null（保持原样） */
    private String translateJson(String json, Language lang) {
        if (json == null || json.isEmpty()) return null;
        try {
            Component component = GsonComponentSerializer.gson().deserialize(json);
            Component translated = translateComponent(component, lang);
            if (translated.equals(component)) return null;
            return GsonComponentSerializer.gson().serialize(translated);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 文本 =====

    /** 翻译单段文本；命中跳过规则或翻译失败时返回原文 */
    public String translateText(String text, Language lang) {
        if (text == null || text.isEmpty()) return text;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || isTrivial(trimmed)) return text;
        Pattern skip = lang.getSkipPattern();
        if (skip != null && skip.matcher(trimmed).matches()) return text;
        String translated = translator.translate(text, lang);
        return (translated == null || translated.isEmpty()) ? text : translated;
    }

    private static boolean isTrivial(String s) {
        if (s.matches("[\\d\\s\\p{P}\\p{S}]+")) return true; // 纯数字 / 标点 / 符号
        return s.matches("(?i)^https?://\\S+$");             // 纯链接
    }
}
