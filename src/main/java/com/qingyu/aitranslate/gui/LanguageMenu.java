package com.qingyu.aitranslate.gui;

import com.qingyu.aitranslate.AiTranslatePlugin;
import com.qingyu.aitranslate.lang.Language;
import com.qingyu.aitranslate.util.SkullUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 语言选择菜单：一个箱子界面，
 * 每个语言一个国旗头颅（未配置头颅时用染色方块占位），点击即切换自己的语言。
 * 第 0 格为「自动检测」（跟随客户端语言）。
 */
public class LanguageMenu implements Listener {

    private static final String TITLE = "§0AI 翻译 · 选择你的语言";

    private final AiTranslatePlugin plugin;
    private final NamespacedKey keyLang;

    public LanguageMenu(AiTranslatePlugin plugin) {
        this.plugin = plugin;
        this.keyLang = new NamespacedKey(plugin, "lang");
    }

    private static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public void open(Player player) {
        var lm = plugin.getLanguageManager();
        List<Language> langs = lm.ordered();
        int needed = langs.size() + 1;
        int rows = Math.min(6, Math.max(1, (int) Math.ceil(needed / 9.0)));
        Inventory inv = Bukkit.createInventory(new MenuHolder(), rows * 9,
                LegacyComponentSerializer.legacySection().deserialize(TITLE));

        Language current = lm.resolveFor(player);
        boolean auto = !lm.hasOverride(player.getUniqueId());

        // 第 0 格：自动检测
        ItemStack autoItem = new ItemStack(Material.COMPASS);
        ItemMeta autoMeta = autoItem.getItemMeta();
        if (autoMeta != null) {
            autoMeta.displayName(LegacyComponentSerializer.legacySection()
                    .deserialize(auto ? "§a§l自动检测 §7（跟随客户端语言） §a✔" : "§e§l自动检测 §7（跟随客户端语言）"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(deserialize("§7自动按你客户端的语言设置翻译公屏"));
            lore.add(deserialize("§7你当前客户端语言: §f" + player.getLocale()));
            lore.add(deserialize(" "));
            lore.add(deserialize("§b§l点击选择"));
            autoMeta.lore(lore);
            autoMeta.getPersistentDataContainer().set(keyLang, PersistentDataType.STRING, "AUTO");
            autoItem.setItemMeta(autoMeta);
        }
        inv.setItem(0, autoItem);

        // 每个语言一格
        int slot = 1;
        for (Language lang : langs) {
            if (slot >= inv.getSize()) break;
            ItemStack item = SkullUtil.iconFor(lang);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                boolean curr = !auto && current != null && current.getId().equals(lang.getId());
                meta.displayName(LegacyComponentSerializer.legacySection()
                        .deserialize((curr ? "§a§l" : "§b§l") + lang.getDisplay() + (curr ? " §a✔" : "")));
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(deserialize("§7语言代码: §f" + String.join(", ", lang.getLocales())));
                lore.add(deserialize(" "));
                lore.add(deserialize("§b§l点击选择"));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(keyLang, PersistentDataType.STRING, lang.getId());
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String langId = meta.getPersistentDataContainer().get(keyLang, PersistentDataType.STRING);
        if (langId == null) return;

        if (langId.equals("AUTO")) {
            plugin.getLanguageManager().setOverride(player.getUniqueId(), null);
            player.sendMessage(deserialize("§b§l[AI翻译] §r已切换为 §a自动检测§r（跟随客户端语言: " + player.getLocale() + "）"));
        } else {
            plugin.getLanguageManager().setOverride(player.getUniqueId(), langId);
            Language lang = plugin.getLanguageManager().getLanguages().get(langId);
            player.sendMessage(deserialize("§b§l[AI翻译] §r语言已切换为 §a" + (lang != null ? lang.getDisplay() : langId)));
        }
        player.closeInventory();
    }

    private static net.kyori.adventure.text.Component deserialize(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }
}
