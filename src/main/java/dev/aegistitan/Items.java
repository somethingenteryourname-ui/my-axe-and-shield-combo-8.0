package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Creates and recognises the two special items. */
final class Items {

    private final NamespacedKey shieldKey;
    private final NamespacedKey axeKey;
    private final NamespacedKey tridentKey;

    Items(AegisTitan plugin) {
        this.shieldKey = new NamespacedKey(plugin, "aegis_shield");
        this.axeKey = new NamespacedKey(plugin, "titan_cleaver");
        this.tridentKey = new NamespacedKey(plugin, "leviathan_trident");
    }

    ItemStack createShield() {
        ItemStack item = new ItemStack(Material.SHIELD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Aegis Wall Shield", TextColor.color(0x3FE0FF), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Raise it to project a massive wall", NamedTextColor.GRAY),
                line("of energy wherever you look.", NamedTextColor.GRAY),
                Component.empty(),
                line("\u2022 Blocks every attack and projectile", NamedTextColor.AQUA),
                line("\u2022 Nobody can walk through it", NamedTextColor.AQUA),
                line("\u2022 Unbreakable", NamedTextColor.AQUA),
                line("\u2022 Resize it with /shieldsize", NamedTextColor.AQUA),
                Component.empty(),
                line("Only the Titan Cleaver can cut it down.", NamedTextColor.DARK_RED)));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack createAxe() {
        ItemStack item = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Titan Cleaver", TextColor.color(0xFF7A1A), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Forged to split mountains.", NamedTextColor.GRAY),
                Component.empty(),
                line("Sneak + Right Click: TITAN SLAM", NamedTextColor.GOLD),
                line("Summons a colossal axe that smashes", NamedTextColor.YELLOW),
                line("the ground and sends out a shockwave.", NamedTextColor.YELLOW),
                line("Make it bigger with /axesize", NamedTextColor.YELLOW),
                Component.empty(),
                line("Cuts Aegis Walls clean in half.", NamedTextColor.DARK_RED)));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.getPersistentDataContainer().set(axeKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack createTrident() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Leviathan Trident", TextColor.color(0x4FE3D0), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("The spear of the deep, reforged for war.", NamedTextColor.GRAY),
                Component.empty(),
                line("Throw: LEVIATHAN RIDE", NamedTextColor.DARK_AQUA),
                line("Summons a colossal trident that you ride", NamedTextColor.AQUA),
                line("to your target. Its landing shatters the", NamedTextColor.AQUA),
                line("ground and destroys anything it hits.", NamedTextColor.AQUA),
                line("Make it bigger with /tridentsize", NamedTextColor.AQUA),
                Component.empty(),
                line("Only an Aegis Wall can stop it.", NamedTextColor.DARK_RED)));
        meta.addEnchant(Enchantment.IMPALING, 5, true);
        meta.addEnchant(Enchantment.LOYALTY, 3, true);
        meta.addEnchant(Enchantment.CHANNELING, 1, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.getPersistentDataContainer().set(tridentKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    boolean isTrident(ItemStack item) {
        return item != null && item.getType() == Material.TRIDENT && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(tridentKey, PersistentDataType.BYTE);
    }

    boolean isShield(ItemStack item) {
        return item != null && item.getType() == Material.SHIELD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE);
    }

    boolean isAxe(ItemStack item) {
        return item != null && item.getType() == Material.NETHERITE_AXE && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(axeKey, PersistentDataType.BYTE);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
