package minecraft.heads;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Heads adds obtainable heads for every mob in the game. */
public final class HeadsPlugin extends JavaPlugin {

    private HeadTextures textures;
    private HeadPowers powers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!reloadTextures()) {
            return;
        }

        getServer().getPluginManager().registerEvents(new HeadsGui(this, 0), this);
        getServer().getPluginManager().registerEvents(new HeadDropListener(this), this);
        powers = new HeadPowers(this);
        getServer().getPluginManager().registerEvents(powers, this);
        powers.start();

        HeadsCommand executor = new HeadsCommand(this);
        registerCommand("heads", executor);
        registerCommand("head", executor);

        getLogger().info("Heads enabled with " + textures.size() + " mob head textures and "
                + HeadAbilities.profileCount() + " head powers.");
        if (!textures.missing().isEmpty()) {
            getLogger().warning("No specific texture for: " + String.join(", ", textures.missing()));
        }

        if (Boolean.getBoolean("heads.selftest")) {
            getServer().getScheduler().runTask(this, this::runSelfTest);
        }
    }

    @Override
    public void onDisable() {
        if (powers != null) {
            powers.stop();
            powers = null;
        }
    }

    private void registerCommand(String name, HeadsCommand executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command /" + name + " is missing from plugin.yml.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
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

        for (String name : List.of("heads", "head")) {
            if (getCommand(name) == null) {
                failures++;
                getLogger().warning("SELFTEST command /" + name + " is not registered");
            }
        }

        int guiPages = HeadsGui.pageCount(this);
        int guiHeads = 0;
        for (int page = 0; page < guiPages; page++) {
            HeadsGui gui = new HeadsGui(this, page);
            Inventory inventory = gui.createInventory();
            for (int slot = 0; slot < gui.entries().size(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType() == Material.AIR) {
                    failures++;
                    getLogger().warning("SELFTEST GUI page " + (page + 1) + " slot " + slot
                            + " is empty for " + gui.entries().get(slot).getKey());
                    continue;
                }
                guiHeads++;
            }
            if (gui.entries().isEmpty()) {
                failures++;
                getLogger().warning("SELFTEST GUI page " + (page + 1) + " has no heads");
            }
        }
        if (guiHeads != textures.size()) {
            failures++;
            getLogger().warning("SELFTEST GUI contains " + guiHeads + " heads but "
                    + textures.size() + " are available");
        }

        int powerProfiles = 0;
        int abilityCount = 0;
        Set<List<HeadAbility>> abilityCombos = new HashSet<>();
        EnumSet<HeadAbility> usedAbilities = EnumSet.noneOf(HeadAbility.class);
        for (EntityType type : textures.types()) {
            HeadAbilities.Profile profile = HeadAbilities.profileFor(type);
            if (profile == null || profile.description() == null || profile.description().isBlank()) {
                failures++;
                getLogger().warning("SELFTEST missing head power for " + type.getKey());
                continue;
            }
            if (profile.abilities().isEmpty()) {
                failures++;
                getLogger().warning("SELFTEST head has no non-effect ability for " + type.getKey());
                continue;
            }
            ItemStack head = HeadFactory.create(textures, type);
            if (HeadFactory.mobType(head) != type) {
                failures++;
                getLogger().warning("SELFTEST head power tag round-trip failed for " + type.getKey());
                continue;
            }
            abilityCount += profile.abilities().size();
            abilityCombos.add(profile.abilities());
            usedAbilities.addAll(profile.abilities());
            powerProfiles++;
        }
        if (usedAbilities.size() != HeadAbility.values().length) {
            failures++;
            getLogger().warning("SELFTEST only " + usedAbilities.size() + " of "
                    + HeadAbility.values().length + " abilities are assigned");
        }

        for (HeadAbility ability : HeadAbility.values()) {
            if (ability.active() && ability.defaultCooldown() != 0.0) {
                failures++;
                getLogger().warning("SELFTEST active ability " + ability.id()
                        + " does not default to zero cooldown");
            }
        }
        if (HeadAbilities.abilities(EntityType.PIG).stream().anyMatch(HeadAbility::active)) {
            failures++;
            getLogger().warning("SELFTEST PIG still has an active right-click ability");
        }
        if (!HeadAbilities.abilities(EntityType.PIG).contains(HeadAbility.PIG_GUARDS)) {
            failures++;
            getLogger().warning("SELFTEST PIG is missing passive pig guards");
        }
        if (!HeadAbilities.abilities(EntityType.BLAZE).contains(HeadAbility.FLIGHT)) {
            failures++;
            getLogger().warning("SELFTEST BLAZE is missing flight");
        }
        if (!HeadAbilities.abilities(EntityType.ENDER_DRAGON).contains(HeadAbility.DRAGON_BREATH)
                || HeadAbilities.abilities(EntityType.ENDER_DRAGON).contains(HeadAbility.FIRE_CHARGE)) {
            failures++;
            getLogger().warning("SELFTEST ENDER_DRAGON must use dragon breath, not fire charge");
        }
        if (!HeadAbilities.abilities(EntityType.BREEZE).contains(HeadAbility.WIND_BURST)
                || HeadAbilities.abilities(EntityType.BREEZE).contains(HeadAbility.FIRE_CHARGE)) {
            failures++;
            getLogger().warning("SELFTEST BREEZE must use wind burst, not fire charge");
        }

        if (failures == 0) {
            getLogger().info("HEADS SELFTEST PASS types=" + checked
                    + " fallbacks=" + textures.missing().size());
            getLogger().info("HEADS GUI SELFTEST PASS pages=" + guiPages + " heads=" + guiHeads);
            getLogger().info("HEADS POWERS SELFTEST PASS profiles=" + powerProfiles);
            getLogger().info("HEADS ABILITIES SELFTEST PASS abilities=" + abilityCount
                    + " unique=" + usedAbilities.size() + " combos=" + abilityCombos.size());
        } else {
            getLogger().warning("HEADS SELFTEST FAIL types=" + checked + " failures=" + failures);
        }
    }
}
