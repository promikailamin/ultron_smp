package minecraft.heads;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.io.IOException;
import java.io.InputStream;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Heads adds obtainable heads for every mob in the game. */
public final class HeadsPlugin extends JavaPlugin {

    private HeadTextures textures;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!reloadTextures()) {
            return;
        }

        getServer().getPluginManager().registerEvents(new HeadsGui(this, 0), this);
        getServer().getPluginManager().registerEvents(new HeadDropListener(this), this);

        HeadsCommand executor = new HeadsCommand(this);
        PluginCommand command = getCommand("heads");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("Heads enabled with " + textures.size() + " mob head textures.");
        if (!textures.missing().isEmpty()) {
            getLogger().warning("No specific texture for: " + String.join(", ", textures.missing()));
        }

        if (Boolean.getBoolean("heads.selftest")) {
            getServer().getScheduler().runTask(this, this::runSelfTest);
        }
    }

    public HeadTextures textures() {
        return textures;
    }

    public boolean reloadTextures() {
        try (InputStream stream = getResource("heads-textures.tsv")) {
            if (stream == null) {
                getLogger().severe("heads-textures.tsv is missing from the plugin jar.");
                getServer().getPluginManager().disablePlugin(this);
                return false;
            }
            textures = HeadTextures.load(stream);
            return true;
        } catch (IOException exception) {
            getLogger().severe("Could not load heads-textures.tsv: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private void runSelfTest() {
        int checked = 0;
        int failures = 0;
        for (EntityType type : textures.types()) {
            ItemStack head = HeadFactory.create(textures, type);
            if (head == null || head.getType() == Material.AIR) {
                failures++;
                getLogger().warning("SELFTEST missing head for " + type.getKey());
                continue;
            }
            checked++;
            if (head.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                PlayerProfile profile = meta == null ? null : meta.getPlayerProfile();
                if (profile == null || !profile.hasProperty("textures")) {
                    failures++;
                    getLogger().warning("SELFTEST missing texture for " + type.getKey());
                }
            }
        }
        if (failures == 0) {
            getLogger().info("HEADS SELFTEST PASS types=" + checked
                    + " fallbacks=" + textures.missing().size());
        } else {
            getLogger().warning("HEADS SELFTEST FAIL types=" + checked + " failures=" + failures);
        }
    }
}
