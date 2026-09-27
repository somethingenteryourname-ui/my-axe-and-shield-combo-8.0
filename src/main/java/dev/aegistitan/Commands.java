package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Commands implements TabExecutor {

    private static final Map<String, int[]> PRESETS = Map.of(
            "small", new int[]{6, 5},
            "medium", new int[]{10, 7},
            "large", new int[]{14, 10},
            "huge", new int[]{22, 14},
            "massive", new int[]{32, 20});

    private static final Map<String, Double> AXE_PRESETS = Map.of(
            "normal", 1.0,
            "big", 2.0,
            "giant", 4.0,
            "colossal", 7.0,
            "mountain", 10.0);

    private static final Map<String, Double> TRIDENT_PRESETS = Map.of(
            "normal", 1.0,
            "big", 3.0,
            "giant", 6.0,
            "colossal", 12.0,
            "leviathan", 30.0);

    private static final Component PREFIX = Component.text("[Aegis] ", TextColor.color(0x3FE0FF));

    private final AegisTitan plugin;
    private final Items items;
    private final WallManager walls;
    private final TitanAxe axe;
    private final TitanTrident trident;

    Commands(AegisTitan plugin, Items items, WallManager walls, TitanAxe axe, TitanTrident trident) {
        this.plugin = plugin;
        this.items = items;
        this.walls = walls;
        this.axe = axe;
        this.trident = trident;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "getshield" -> give(sender, args, items.createShield(), "Aegis Wall Shield");
            case "getaxe" -> give(sender, args, items.createAxe(), "Titan Cleaver");
            case "shieldsize" -> size(sender, args);
            case "axesize" -> axeSize(sender, args);
            case "axecooldown" -> axeCooldown(sender, args);
            case "gettrident" -> give(sender, args, items.createTrident(), "Leviathan Trident");
            case "tridentsize" -> tridentSize(sender, args);
            case "tridentcooldown" -> tridentCooldown(sender, args);
            case "aegistitan" -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    plugin.reloadConfig();
                    msg(sender, "Config reloaded.", NamedTextColor.GREEN);
                } else {
                    msg(sender, "Usage: /aegistitan reload", NamedTextColor.GRAY);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void give(CommandSender sender, String[] args, ItemStack item, String name) {
        Player target;
        if (args.length >= 1) {
            if (!sender.hasPermission("aegistitan.admin")) {
                msg(sender, "You can only give this to yourself.", NamedTextColor.RED);
                return;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                msg(sender, "Player " + args[0] + " isn't online.", NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg(sender, "Console must name a player.", NamedTextColor.RED);
            return;
        }
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        target.playSound(target.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 0.8f);
        msg(target, "You received the " + name + "!", NamedTextColor.AQUA);
        if (!target.equals(sender)) {
            msg(sender, "Gave the " + name + " to " + target.getName() + ".", NamedTextColor.GREEN);
        }
    }

    private void size(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can do that.", NamedTextColor.RED);
            return;
        }
        int w;
        int h;
        if (args.length == 0) {
            int[] now = walls.getSize(player);
            msg(player, "Your wall is " + now[0] + " wide \u00D7 " + now[1] + " tall.", NamedTextColor.AQUA);
            msg(player, "Change it: /shieldsize <width> <height>  or  /shieldsize <small|medium|large|huge|massive>",
                    NamedTextColor.GRAY);
            msg(player, "Limits: " + walls.minSize() + "\u2013" + walls.maxWidth() + " wide, "
                    + walls.minSize() + "\u2013" + walls.maxHeight() + " tall.", NamedTextColor.GRAY);
            return;
        } else if (args.length == 1) {
            int[] preset = PRESETS.get(args[0].toLowerCase(Locale.ROOT));
            if (preset == null) {
                msg(player, "Unknown size. Try small, medium, large, huge, massive, or /shieldsize <width> <height>.",
                        NamedTextColor.RED);
                return;
            }
            w = preset[0];
            h = preset[1];
        } else {
            try {
                w = Integer.parseInt(args[0]);
                h = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                msg(player, "Width and height must be whole numbers.", NamedTextColor.RED);
                return;
            }
        }
        int[] used = walls.setSize(player, w, h);
        msg(player, "Shield wall set to " + used[0] + " wide \u00D7 " + used[1] + " tall.", NamedTextColor.AQUA);
        if (used[0] != w || used[1] != h) {
            msg(player, "(Adjusted to fit the server's size limits.)", NamedTextColor.GRAY);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
    }

    private void axeSize(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can do that.", NamedTextColor.RED);
            return;
        }
        String max = trim(axe.maxScale());
        if (args.length == 0) {
            msg(player, "Your Titan Cleaver slam is size " + trim(axe.getScale(player)) + "x.", NamedTextColor.GOLD);
            msg(player, "Change it: /axesize <1-" + max + ">  or  /axesize <normal|big|giant|colossal|mountain>",
                    NamedTextColor.GRAY);
            return;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        Double wanted = AXE_PRESETS.get(arg);
        if (wanted == null) {
            try {
                wanted = Double.parseDouble(arg.endsWith("x") ? arg.substring(0, arg.length() - 1) : arg);
            } catch (NumberFormatException e) {
                msg(player, "Use a number from 1 to " + max + ", or normal, big, giant, colossal, mountain.",
                        NamedTextColor.RED);
                return;
            }
        }
        if (wanted.isNaN() || wanted.isInfinite()) {
            msg(player, "That isn't a real size.", NamedTextColor.RED);
            return;
        }
        double used = axe.setScale(player, wanted);
        msg(player, "Titan Cleaver slam set to size " + trim(used) + "x.", NamedTextColor.GOLD);
        if (used != wanted) {
            msg(player, "(Adjusted to the server's limit of 1\u2013" + max + ".)", NamedTextColor.GRAY);
        }
        if (used >= 7) {
            msg(player, "Warning: at this size the slam can split mountains in half.", NamedTextColor.RED);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, (float) Math.max(0.5, 1.2 - used * 0.07));
    }

    private void axeCooldown(CommandSender sender, String[] args) {
        if (args.length == 0) {
            int now = axe.cooldownSeconds();
            msg(sender, "Titan Slam cooldown is " + (now == 0 ? "off" : now + "s") + ".", NamedTextColor.GOLD);
            msg(sender, "Change it: /axecooldown <seconds>  (0 or off = no cooldown)", NamedTextColor.GRAY);
            return;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        int seconds;
        if (arg.equals("off") || arg.equals("none")) {
            seconds = 0;
        } else {
            try {
                seconds = (int) Math.round(Double.parseDouble(arg.endsWith("s") ? arg.substring(0, arg.length() - 1) : arg));
            } catch (NumberFormatException e) {
                msg(sender, "Use a number of seconds, like /axecooldown 10 (or 0 for none).", NamedTextColor.RED);
                return;
            }
        }
        if (seconds < 0 || seconds > 86400) {
            msg(sender, "Pick something between 0 and 86400 seconds.", NamedTextColor.RED);
            return;
        }
        axe.setCooldownSeconds(seconds);
        String shown = seconds == 0 ? "nothing \u2014 slam away!" : seconds + " seconds.";
        msg(sender, "Titan Slam cooldown set to " + shown, NamedTextColor.GOLD);
        if (sender instanceof Player p) {
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
        }
    }

    private void tridentSize(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can do that.", NamedTextColor.RED);
            return;
        }
        String max = trim(trident.maxScale());
        if (args.length == 0) {
            msg(player, "Your Leviathan Trident is size " + trim(trident.getScale(player)) + "x.", NamedTextColor.AQUA);
            msg(player, "Change it: /tridentsize <1-" + max + ">  or  /tridentsize <normal|big|giant|colossal|leviathan>",
                    NamedTextColor.GRAY);
            return;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        Double wanted = TRIDENT_PRESETS.get(arg);
        if (wanted == null) {
            try {
                wanted = Double.parseDouble(arg.endsWith("x") ? arg.substring(0, arg.length() - 1) : arg);
            } catch (NumberFormatException e) {
                msg(player, "Use a number from 1 to " + max + ", or normal, big, giant, colossal, leviathan.",
                        NamedTextColor.RED);
                return;
            }
        }
        if (wanted.isNaN() || wanted.isInfinite()) {
            msg(player, "That isn't a real size.", NamedTextColor.RED);
            return;
        }
        double used = trident.setScale(player, wanted);
        msg(player, "Leviathan Trident set to size " + trim(used) + "x.", NamedTextColor.AQUA);
        if (used != wanted) {
            msg(player, "(Adjusted to the server's limit of 1\u2013" + max + ".)", NamedTextColor.GRAY);
        }
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, (float) Math.max(0.5, 1.2 - used * 0.07));
    }

    private void tridentCooldown(CommandSender sender, String[] args) {
        if (args.length == 0) {
            int now = trident.cooldownSeconds();
            msg(sender, "Leviathan Ride cooldown is " + (now == 0 ? "off" : now + "s") + ".", NamedTextColor.AQUA);
            msg(sender, "Change it: /tridentcooldown <seconds>  (0 or off = no cooldown)", NamedTextColor.GRAY);
            return;
        }
        Integer seconds = parseSeconds(sender, args[0]);
        if (seconds == null) {
            return;
        }
        trident.setCooldownSeconds(seconds);
        String shown = seconds == 0 ? "nothing \u2014 ride away!" : seconds + " seconds.";
        msg(sender, "Leviathan Ride cooldown set to " + shown, NamedTextColor.AQUA);
    }

    private Integer parseSeconds(CommandSender sender, String raw) {
        String arg = raw.toLowerCase(Locale.ROOT);
        int seconds;
        if (arg.equals("off") || arg.equals("none")) {
            seconds = 0;
        } else {
            try {
                seconds = (int) Math.round(Double.parseDouble(arg.endsWith("s") ? arg.substring(0, arg.length() - 1) : arg));
            } catch (NumberFormatException e) {
                msg(sender, "Use a number of seconds, like 10 (or 0 for none).", NamedTextColor.RED);
                return null;
            }
        }
        if (seconds < 0 || seconds > 86400) {
            msg(sender, "Pick something between 0 and 86400 seconds.", NamedTextColor.RED);
            return null;
        }
        return seconds;
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("shieldsize")) {
            if (args.length == 1) {
                options.addAll(List.of("small", "medium", "large", "huge", "massive", "8", "12", "16", "24"));
            } else if (args.length == 2) {
                options.addAll(List.of("6", "8", "10", "14", "20"));
            }
        } else if (name.equals("axesize") && args.length == 1) {
            options.addAll(List.of("normal", "big", "giant", "colossal", "mountain", "1", "3", "5", "10"));
        } else if (name.equals("tridentsize") && args.length == 1) {
            options.addAll(List.of("normal", "big", "giant", "colossal", "leviathan", "1", "5", "10", "20", "30"));
        } else if ((name.equals("axecooldown") || name.equals("tridentcooldown")) && args.length == 1) {
            options.addAll(List.of("off", "5", "10", "15", "30", "60"));
        } else if ((name.equals("getshield") || name.equals("getaxe") || name.equals("gettrident")) && args.length == 1
                && sender.hasPermission("aegistitan.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
        } else if (name.equals("aegistitan") && args.length == 1) {
            options.add("reload");
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(PREFIX.append(Component.text(text, color)));
    }
}
