package minecraft.heads;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/** Creates a player-skull item that renders the head texture of any mob. */
public final class HeadFactory {

    public static final NamespacedKey MOB_KEY = new NamespacedKey("heads", "mob");

    private HeadFactory() {
    }

    public static ItemStack create(HeadTextures textures, EntityType type) {
        Material vanilla = vanillaMaterial(type);
        if (vanilla != null) {
            ItemStack item = new ItemStack(vanilla);
            decorate(item, type);
            return item;
        }

        String value = textures.textureFor(type);
        if (value == null) {
            return null;
        }

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            PlayerProfile profile = Bukkit.createProfile(
                    UUID.nameUUIDFromBytes(("Heads:" + type.getKey()).getBytes(StandardCharsets.UTF_8)),
                    type.getKey().getKey());
            profile.setProperty(new ProfileProperty("textures", value));
            meta.setPlayerProfile(profile);
        });
        decorate(item, type);
        return item;
    }

    private static void decorate(ItemStack item, EntityType type) {
        String name = HeadTextures.displayName(type) + " Head";
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(MOB_KEY, PersistentDataType.STRING, type.name());
            meta.displayName(Component.text(name)
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Mob head", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Power: " + HeadAbilities.description(type), NamedTextColor.AQUA)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(type.getKey().toString(), NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        });
    }

    /**
     * Resolves which mob a head belongs to. New heads carry a persistent data
     * tag; older heads fall back to their vanilla material or skull profile.
     */
    public static EntityType mobType(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String stored = meta.getPersistentDataContainer().get(MOB_KEY, PersistentDataType.STRING);
            if (stored != null && !stored.isBlank()) {
                try {
                    return EntityType.valueOf(stored.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    for (EntityType type : EntityType.values()) {
                        if (type.getKey().toString().equalsIgnoreCase(stored)) {
                            return type;
                        }
                    }
                }
            }
        }

        EntityType vanilla = mobFromMaterial(item.getType());
        if (vanilla != null) {
            return vanilla;
        }

        if (meta instanceof SkullMeta skull) {
            PlayerProfile profile = skull.getPlayerProfile();
            if (profile != null && profile.getName() != null) {
                return HeadsCommand.resolve(profile.getName());
            }
        }
        return null;
    }

    public static Material vanillaMaterial(EntityType type) {
        return switch (type) {
            case ZOMBIE -> Material.ZOMBIE_HEAD;
            case PIGLIN -> Material.PIGLIN_HEAD;
            case CREEPER -> Material.CREEPER_HEAD;
            case SKELETON -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            default -> null;
        };
    }

    private static EntityType mobFromMaterial(Material material) {
        return switch (material) {
            case ZOMBIE_HEAD -> EntityType.ZOMBIE;
            case PIGLIN_HEAD -> EntityType.PIGLIN;
            case CREEPER_HEAD -> EntityType.CREEPER;
            case SKELETON_SKULL -> EntityType.SKELETON;
            case WITHER_SKELETON_SKULL -> EntityType.WITHER_SKELETON;
            case DRAGON_HEAD -> EntityType.ENDER_DRAGON;
            default -> null;
        };
    }
}
