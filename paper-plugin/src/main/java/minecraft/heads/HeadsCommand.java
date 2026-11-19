package minecraft.heads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Handles /heads and its aliases. */
public final class HeadsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("give", "list", "reload");

    private final HeadsPlugin plugin;

    public HeadsCommand(HeadsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Usage: /" + label + " <mob> [player]", NamedTextColor.YELLOW));
                return true;
            }
            if (!player.hasPermission("heads.use")) {
                player.sendMessage(Component.text("You do not have permission to use this command.",
                        NamedTextColor.RED));
                return true;
            }
            new HeadsGui(plugin, 0).open(player);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "list" -> {
                sendList(sender);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("heads.admin")) {
                    sender.sendMessage(Component.text("You do not have permission to reload Heads.",
                            NamedTextColor.RED));
                    return true;
                }
                plugin.reloadConfig();
                plugin.reloadTextures();
                sender.sendMessage(Component.text("Heads reloaded: " + plugin.textures().size()
                        + " mob heads available.", NamedTextColor.GREEN));
                return true;
            }
            case "give" -> {
                return handleGive(sender, label, args);
            }
            default -> {
                return giveToSelf(sender, label, args[0]);
            }
        }
    }

    private boolean handleGive(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(Component.text("Usage: /" + label + " give <mob> [player]",
                    NamedTextColor.YELLOW));
            return true;
        }
        if (args.length == 2) {
            return giveToSelf(sender, label, args[1]);
        }

        if (!sender.hasPermission("heads.admin")) {
            sender.sendMessage(Component.text("You do not have permission to give heads to other players.",
                    NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
            return true;
        }
        EntityType type = resolve(args[2]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown mob: " + args[2] + ". Use /" + label
                    + " list to see all mobs.", NamedTextColor.RED));
            return true;
        }
        return give(sender, target, type);
    }

    private boolean giveToSelf(CommandSender sender, String label, String input) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can receive a head directly. Use /" + label
                    + " give <player> <mob>.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("heads.give")) {
            player.sendMessage(Component.text("You do not have permission to get heads.",
                    NamedTextColor.RED));
            return true;
        }
        EntityType type = resolve(input);
        if (type == null) {
            player.sendMessage(Component.text("Unknown mob: " + input + ". Use /" + label
                    + " list to see all mobs.", NamedTextColor.RED));
            return true;
        }
        return give(sender, player, type);
    }

    private boolean give(CommandSender sender, Player target, EntityType type) {
        ItemStack head = HeadFactory.create(plugin.textures(), type);
        if (head == null) {
            sender.sendMessage(Component.text("No head texture is available for " + type.getKey(),
                    NamedTextColor.RED));
            return true;
        }
        target.getInventory().addItem(head).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        String name = HeadTextures.displayName(type) + " Head";
        if (sender == target) {
            sender.sendMessage(Component.text("You received a " + name + ".", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Gave a " + name + " to " + target.getName() + ".",
                    NamedTextColor.GREEN));
            target.sendMessage(Component.text("You received a " + name + ".", NamedTextColor.GREEN));
        }
        return true;
    }

    private void sendList(CommandSender sender) {
        List<EntityType> types = plugin.textures().types();
        sender.sendMessage(Component.text("Available mob heads (" + types.size() + "):",
                NamedTextColor.GOLD));
        StringBuilder line = new StringBuilder();
        for (EntityType type : types) {
            String key = type.getKey().getKey();
            if (line.length() + key.length() + 2 > 90) {
                sender.sendMessage(Component.text(line.toString(), NamedTextColor.GRAY));
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(key);
        }
        if (line.length() > 0) {
            sender.sendMessage(Component.text(line.toString(), NamedTextColor.GRAY));
        }
    }

    public static EntityType resolve(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace('-', '_')
                .replace(' ', '_');
        try {
            return EntityType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Fall through to key/alias matching.
        }
        for (EntityType type : EntityType.values()) {
            if (type.getKey().getKey().equals(normalized)) {
                return type;
            }
        }
        return switch (normalized) {
            case "pig_zombie", "zombie_pigman" -> EntityType.ZOMBIFIED_PIGLIN;
            case "mushroom_cow" -> EntityType.MOOSHROOM;
            case "snowman" -> EntityType.SNOW_GOLEM;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            for (EntityType type : plugin.textures().types()) {
                options.add(type.getKey().getKey());
            }
            return filter(options, args[0]);
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    options.add(player.getName());
                }
                for (EntityType type : plugin.textures().types()) {
                    options.add(type.getKey().getKey());
                }
                return filter(options, args[1]);
            }
            if (args.length == 3) {
                List<String> options = new ArrayList<>();
                for (EntityType type : plugin.textures().types()) {
                    options.add(type.getKey().getKey());
                }
                return filter(options, args[2]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
