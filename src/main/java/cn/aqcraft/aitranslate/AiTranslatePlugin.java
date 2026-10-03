package cn.aqcraft.aitranslate;

import cn.aqcraft.aitranslate.chat.ChatListener;
import cn.aqcraft.aitranslate.chat.ComponentTranslator;
import cn.aqcraft.aitranslate.config.ConfigManager;
import cn.aqcraft.aitranslate.gui.LanguageMenu;
import cn.aqcraft.aitranslate.lang.Language;
import cn.aqcraft.aitranslate.lang.LanguageManager;
import cn.aqcraft.aitranslate.translate.TranslationCache;
import cn.aqcraft.aitranslate.translate.Translator;
import cn.aqcraft.aitranslate.util.SkullUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 清屿 AI 翻译插件主类。
 *
 * 功能：
 * - 公屏聊天按「每个玩家自己的语言」翻译（默认跟随客户端语言，可用菜单覆盖）
 * - 翻译聊天中的物品自定义名称与 lore（hover 展示）
 * - OpenAI 兼容接口（默认 S3AI: https://ai.furry.vg/v1）
 */
public class AiTranslatePlugin extends JavaPlugin {

    private static final String PREFIX = "§b§l[AI翻译] §r";

    private ConfigManager configManager;
    private LanguageManager languageManager;
    private TranslationCache cache;
    private Translator translator;
    private ComponentTranslator componentTranslator;
    private LanguageMenu languageMenu;
    private ExecutorService translatePool;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(getDataFolder());
        configManager.load();

        languageManager = new LanguageManager(configManager.getDefaultLanguage());
        languageManager.loadFromFile(getDataFolder());

        cache = new TranslationCache(configManager.getCacheMaxSizePerLanguage());
        translator = new Translator(configManager, cache);
        componentTranslator = new ComponentTranslator(translator, configManager);

        int threads = Math.max(2, configManager.getMaxParallelRequests() + 1);
        translatePool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "AiTranslate-Worker");
            t.setDaemon(true);
            return t;
        });

        languageMenu = new LanguageMenu(this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
        Bukkit.getPluginManager().registerEvents(languageMenu, this);

        var command = getCommand("aitr");
        if (command != null) command.setExecutor(this::onCommand);

        if (translator.isApiKeyMissing()) {
            getLogger().warning("尚未配置 openai.api-key，翻译不会生效！请在 config.yml 填入 Key 后 /aitr reload");
        } else {
            getLogger().info("已启用 | 模型: " + configManager.getOpenAiModel()
                    + " | 语言数: " + languageManager.getLanguages().size());
        }
    }

    @Override
    public void onDisable() {
        if (translatePool != null) translatePool.shutdownNow();
    }

    /** 重载 config.yml 与 languages.yml（缓存与玩家选择保留） */
    public void reloadAll() {
        configManager.load();
        languageManager.loadFromFile(getDataFolder());
        translator = new Translator(configManager, cache);
        componentTranslator = new ComponentTranslator(translator, configManager);
    }

    // ===== 命令 =====

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(PREFIX + "用法: /" + label + " <lang|auto|reload|models|setskull|status|test>");
                return true;
            }
            if (!player.hasPermission("aitr.use")) {
                player.sendMessage(PREFIX + "§c你没有权限使用该命令");
                return true;
            }
            languageMenu.open(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "lang" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + "该命令只能在游戏内使用");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(PREFIX + "用法: /" + label + " lang <语言id>");
                    player.sendMessage(PREFIX + "可用: " + String.join(", ", languageManager.getLanguages().keySet()));
                    return true;
                }
                String id = args[1];
                if (!languageManager.getLanguages().containsKey(id)) {
                    player.sendMessage(PREFIX + "§c未知语言: " + id);
                    player.sendMessage(PREFIX + "可用: " + String.join(", ", languageManager.getLanguages().keySet()));
                    return true;
                }
                languageManager.setOverride(player.getUniqueId(), id);
                player.sendMessage(PREFIX + "语言已切换为 §a" + languageManager.getLanguages().get(id).getDisplay());
                return true;
            }
            case "auto" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + "该命令只能在游戏内使用");
                    return true;
                }
                languageManager.setOverride(player.getUniqueId(), null);
                player.sendMessage(PREFIX + "已切换为 §a自动检测§r（跟随客户端语言: " + player.getLocale() + "）");
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("aitr.reload")) {
                    sender.sendMessage(PREFIX + "§c你没有权限执行该命令");
                    return true;
                }
                reloadAll();
                sender.sendMessage(PREFIX + "配置已重载 | 模型: " + configManager.getOpenAiModel()
                        + " | 语言数: " + languageManager.getLanguages().size());
                if (translator.isApiKeyMissing()) sender.sendMessage(PREFIX + "§c警告: openai.api-key 仍为空！");
                return true;
            }
            case "models" -> {
                if (!sender.hasPermission("aitr.reload")) {
                    sender.sendMessage(PREFIX + "§c你没有权限执行该命令");
                    return true;
                }
                sender.sendMessage(PREFIX + "正在查询 " + configManager.getOpenAiBaseUrl() + "/models ...");
                final Translator snapshot = translator;
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    List<String> models = snapshot.listModels();
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (models.isEmpty()) {
                            sender.sendMessage(PREFIX + "§c查询失败或无可用模型（检查 api-key / 网络）");
                        } else {
                            sender.sendMessage(PREFIX + "可用模型 (" + models.size() + "):");
                            for (String m : models) sender.sendMessage("§7 - §f" + m);
                        }
                    });
                });
                return true;
            }
            case "setskull" -> {
                if (!sender.hasPermission("aitr.reload")) {
                    sender.sendMessage(PREFIX + "§c你没有权限执行该命令");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + "该命令只能在游戏内使用");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(PREFIX + "用法: 手持国旗头颅输入 /" + label + " setskull <语言id>");
                    return true;
                }
                String id = args[1];
                if (!languageManager.getLanguages().containsKey(id)) {
                    player.sendMessage(PREFIX + "§c未知语言: " + id);
                    player.sendMessage(PREFIX + "可用: " + String.join(", ", languageManager.getLanguages().keySet()));
                    return true;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                String url = SkullUtil.readSkinUrl(hand);
                if (url == null) {
                    player.sendMessage(PREFIX + "§c请手持一个带自定义纹理的玩家头颅再执行");
                    return true;
                }
                File file = new File(getDataFolder(), "skull-urls.yml");
                YamlConfiguration cfg = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
                cfg.set(id, url);
                try {
                    cfg.save(file);
                } catch (Exception e) {
                    player.sendMessage(PREFIX + "§c保存失败: " + e.getMessage());
                    return true;
                }
                languageManager.loadFromFile(getDataFolder());
                player.sendMessage(PREFIX + "已将 §a" + id + "§r 的国旗头颅设为: §7" + url);
                return true;
            }
            case "status" -> {
                sender.sendMessage(PREFIX + "§b状态");
                sender.sendMessage("§7- 启用: §f" + configManager.isEnabled()
                        + "§7 | api-key: " + (translator.isApiKeyMissing() ? "§c未配置" : "§a已配置"));
                sender.sendMessage("§7- 接口: §f" + configManager.getOpenAiBaseUrl());
                sender.sendMessage("§7- 模型: §f" + configManager.getOpenAiModel());
                sender.sendMessage("§7- 默认语言: §f" + configManager.getDefaultLanguage()
                        + "§7 | 跳过默认语言: §f" + configManager.skipDefaultLanguage());
                if (sender instanceof Player player) {
                    Language lang = languageManager.resolveFor(player);
                    sender.sendMessage("§7- 你的客户端: §f" + player.getLocale() + "§7 -> 解析语言: §f"
                            + (lang == null ? "无" : lang.getDisplay() + " (" + lang.getId() + ")")
                            + "§7 | 菜单覆盖: §f" + (languageManager.hasOverride(player.getUniqueId()) ? "是" : "否"));
                }
                sender.sendMessage("§7- 缓存: §f" + cache.size() + " §7条 | 并发上限: §f" + configManager.getMaxParallelRequests());
                return true;
            }
            case "test" -> {
                if (!sender.hasPermission("aitr.reload")) {
                    sender.sendMessage(PREFIX + "§c你没有权限执行该命令");
                    return true;
                }
                String text = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : "Hello, welcome to the server!";
                Language target = (sender instanceof Player player)
                        ? languageManager.resolveFor(player) : languageManager.getDefaultLanguage();
                if (target == null) {
                    sender.sendMessage(PREFIX + "§c语言库为空，无法测试");
                    return true;
                }
                final Translator snapshot = translator;
                sender.sendMessage(PREFIX + "§7测试翻译 -> §f" + target.getDisplay() + " (" + target.getId() + ") §7...");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    long t0 = System.currentTimeMillis();
                    String result = snapshot.testCall(text, target);
                    long ms = System.currentTimeMillis() - t0;
                    String err = snapshot.getLastError();
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(PREFIX + "§7原文: §f" + text);
                        if (result != null && !result.isEmpty()) {
                            sender.sendMessage(PREFIX + "§7译文: §a" + result + " §8(" + ms + "ms)");
                        } else {
                            sender.sendMessage(PREFIX + "§c翻译失败: " + (err == null ? "未知原因" : err));
                        }
                    });
                });
                return true;
            }
            default -> {
                sender.sendMessage(PREFIX + "命令: /" + label + " <lang|auto|reload|models|setskull|status|test>");
                return true;
            }
        }
    }

    // ===== Getters =====

    public ConfigManager getConfigManager() { return configManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public Translator getTranslator() { return translator; }
    public ComponentTranslator getComponentTranslator() { return componentTranslator; }
    public ExecutorService getTranslatePool() { return translatePool; }
    public TranslationCache getCache() { return cache; }
}
