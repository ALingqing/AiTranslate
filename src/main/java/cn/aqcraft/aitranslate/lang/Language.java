package cn.aqcraft.aitranslate.lang;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 一种可翻译的目标语言。
 * 每种语言带专属 AI 提示词、显示名、国旗头颅/占位材质、以及匹配的客户端 locale。
 */
public class Language {

    private final String id;
    private final String display;
    private final String flagName;      // 菜单占位材质（如 RED_CONCRETE）
    private final String skull;         // 国旗头颅纹理（URL 或 base64 value，可为空）
    private final List<String> locales; // 匹配的客户端 locale
    private final String prompt;        // 专属 AI 提示词（含 {text} 占位符）
    private final Pattern skipPattern;  // 整条消息命中该正则则跳过翻译（可为 null）

    public Language(String id, String display, String flagName, String skull,
                    List<String> locales, String prompt, Pattern skipPattern) {
        this.id = id;
        this.display = display;
        this.flagName = flagName;
        this.skull = skull;
        this.locales = locales;
        this.prompt = prompt;
        this.skipPattern = skipPattern;
    }

    public String getId() { return id; }
    public String getDisplay() { return display; }
    public String getFlagName() { return flagName; }
    public String getSkull() { return skull; }
    public boolean hasSkull() { return skull != null && !skull.isEmpty(); }
    public List<String> getLocales() { return locales; }
    public String getPrompt() { return prompt; }
    public Pattern getSkipPattern() { return skipPattern; }
}
