package minecraft.heads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Applies the power of the mob head a player is wearing. */
public final class HeadPowers implements Listener {

    private static final int EFFECT_DURATION = 60;
    private static final int COW_GRASS_SECONDS = 5;
    private static final long COW_HEAL_INTERVAL_MS = 2000L;
    private static final long COMBAT_TARGET_MS = 10000L;
    private static final String SERVANT_TAG = "heads_servant";
    private static final String MINION_TAG = "heads_minion";
    private static final String SNOWBALL_TAG = "heads_snowball";
    private static final String FIRE_CHARGE_TAG = "heads_fire_charge";
    private static final String WITHER_SKULL_TAG = "heads_wither_skull";

    private final HeadsPlugin plugin;
    private final Map<UUID, Set<UUID>> servants = new HashMap<>();
    private final Map<UUID, UUID> servantOwner = new HashMap<>();
    private final Map<UUID, Set<UUID>> minions = new HashMap<>();
    private final Map<UUID, UUID> minionOwner = new HashMap<>();
    private final Map<UUID, BukkitTask> minionTasks = new HashMap<>();
    private final Map<UUID, EnumMap<HeadAbility, Long>> abilityReadyAt = new HashMap<>();
    private final Map<UUID, Long> blastImmuneUntil = new HashMap<>();
    private final Map<UUID, CombatTarget> combatTargets = new HashMap<>();
    private final Map<UUID, Integer> cowGrassSeconds = new HashMap<>();
    private final Map<UUID, Long> cowNextHeal = new HashMap<>();
    private final Map<UUID, Boolean> flightRestore = new HashMap<>();
    private BukkitTask task;

    private record CombatTarget(UUID entity, long expiresAt) {
    }

    public HeadPowers(HeadsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            releaseServants(player);
            revokeFlight(player);
            clearMinions(player.getUniqueId());
        }
        for (UUID owner : new ArrayList<>(servants.keySet())) {
            releaseServants(owner);
        }
        for (UUID owner : new ArrayList<>(minionTasks.keySet())) {
            clearMinions(owner);
        }
        servants.clear();
        servantOwner.clear();
        minions.clear();
        minionOwner.clear();
        minionTasks.clear();
        abilityReadyAt.clear();
        blastImmuneUntil.clear();
        combatTargets.clear();
        cowGrassSeconds.clear();
        cowNextHeal.clear();
        flightRestore.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        blastImmuneUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        combatTargets.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        abilityReadyAt.entrySet().removeIf(entry -> entry.getValue().values().stream()
                .allMatch(until -> until <= now));

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            EntityType head = wornHead(player);
            if (head == null) {
                cowGrassSeconds.remove(id);
                cowNextHeal.remove(id);
                revokeFlight(player);
                if (servants.containsKey(id)) {
                    releaseServants(player);
                }
                if (minions.containsKey(id)) {
                    clearMinions(id);
                }
                continue;
            }

            applyProfile(player, head);
            List<HeadAbility> abilities = HeadAbilities.abilities(head);

            if (abilities.contains(HeadAbility.FLIGHT)
                    && plugin.getConfig().getBoolean("powers.flight-enabled", true)) {
                grantFlight(player);
            } else {
                revokeFlight(player);
            }

            if (abilities.contains(HeadAbility.SUMMON_MINIONS)) {
                tickMinions(player, now);
            } else if (minions.containsKey(id)) {
                clearMinions(id);
            }

            if (head == EntityType.COW) {
                tickCow(player, now);
            } else {
                cowGrassSeconds.remove(id);
                cowNextHeal.remove(id);
            }

            if (head == EntityType.PIG) {
                tickPig(player, now);
            } else if (servants.containsKey(id)) {
                releaseServants(player);
            }
        }
    }

    private void applyProfile(Player player, EntityType head) {
        HeadAbilities.Profile profile = HeadAbilities.profileFor(head);
        if (profile == null) {
            return;
        }
        for (HeadAbilities.Effect effect : profile.effects()) {
            PotionEffectType type = effect.type();
            PotionEffect current = player.getPotionEffect(type);
            if (current != null && current.getAmplifier() > effect.amplifier()) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, EFFECT_DURATION,
                    effect.amplifier(), true, false, true));
        }
    }

    private void grantFlight(Player player) {
        UUID id = player.getUniqueId();
        if (flightRestore.containsKey(id)) {
            return;
        }
        flightRestore.put(id, player.getAllowFlight());
        player.setAllowFlight(true);
    }

    private void revokeFlight(Player player) {
        Boolean wasAllowed = flightRestore.remove(player.getUniqueId());
        if (wasAllowed == null) {
            return;
        }
        GameMode mode = player.getGameMode();
        if (!wasAllowed && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void tickCow(Player player, long now) {
        UUID id = player.getUniqueId();
        Location below = player.getLocation().add(0.0, -0.1, 0.0);
        boolean onGrass = below.getBlock().getType() == Material.GRASS_BLOCK;
        int seconds = onGrass ? cowGrassSeconds.getOrDefault(id, 0) + 1 : 0;
        cowGrassSeconds.put(id, seconds);

        if (seconds < COW_GRASS_SECONDS) {
            return;
        }
        long next = cowNextHeal.getOrDefault(id, 0L);
        if (now < next) {
            return;
        }
        cowNextHeal.put(id, now + COW_HEAL_INTERVAL_MS);
        if (player.getHealth() < player.getAttribute(Attribute.MAX_HEALTH).getValue()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH,
                    1, 0, true, false, true));
        }
    }

    private void tickPig(Player owner, long now) {
        UUID ownerId = owner.getUniqueId();
        Set<UUID> owned = servants.get(ownerId);
        if (owned == null || owned.isEmpty()) {
            return;
        }

        LivingEntity target = resolveCombatTarget(owner, now);
        if (target == null) {
            target = findHostile(owner);
        }

        double followRadius = Math.max(2.0, plugin.getConfig().getDouble("powers.pig-follow-radius", 4.0));
        double searchRadius = Math.max(4.0, plugin.getConfig().getDouble("powers.pig-hostile-radius", 12.0));
        if (target != null && target.getWorld() != owner.getWorld()) {
            target = null;
        }
        if (target != null && target.getLocation().distanceSquared(owner.getLocation())
                > searchRadius * searchRadius * 2.25) {
            target = null;
        }

        List<UUID> remove = new ArrayList<>();
        for (UUID servantId : new ArrayList<>(owned)) {
            Entity entity = Bukkit.getEntity(servantId);
            if (!(entity instanceof Pig pig) || !pig.isValid() || pig.isDead()) {
                remove.add(servantId);
                continue;
            }
            if (pig.getWorld() != owner.getWorld()) {
                pig.teleport(owner.getLocation());
                continue;
            }

            double ownerDistance = pig.getLocation().distance(owner.getLocation());
            if (ownerDistance > 32.0) {
                Location near = owner.getLocation().clone().add(
                        (Math.random() - 0.5) * 2.0, 0.0, (Math.random() - 0.5) * 2.0);
                pig.teleport(near);
                ownerDistance = pig.getLocation().distance(owner.getLocation());
            }

            if (target != null && target.isValid() && !target.isDead()) {
                pig.setTarget(target);
                if (!pig.getPathfinder().moveTo(target, 1.25)) {
                    pig.lookAt(target);
                }
                if (pig.getLocation().distanceSquared(target.getLocation()) <= 6.25) {
                    target.damage(3.0, pig);
                    pig.swingHand(EquipmentSlot.HAND);
                }
            } else {
                pig.setTarget(null);
                if (ownerDistance > followRadius) {
                    if (!pig.getPathfinder().moveTo(owner.getLocation(), 1.15)) {
                        pig.lookAt(owner);
                    }
                } else if (pig.getPathfinder().hasPath()) {
                    pig.getPathfinder().stopPathfinding();
                }
            }
        }

        for (UUID servantId : remove) {
            removeServant(servantId);
        }
    }

    private void tickMinions(Player owner, long now) {
        Set<UUID> owned = minions.get(owner.getUniqueId());
        if (owned == null || owned.isEmpty()) {
            return;
        }

        LivingEntity target = resolveCombatTarget(owner, now);
        if (target == null) {
            target = findHostile(owner);
        }
        if (target != null && target.getWorld() != owner.getWorld()) {
            target = null;
        }

        List<UUID> remove = new ArrayList<>();
        for (UUID minionId : new ArrayList<>(owned)) {
            Entity entity = Bukkit.getEntity(minionId);
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) {
                remove.add(minionId);
                continue;
            }
            if (mob.getWorld() != owner.getWorld()) {
                mob.teleport(owner.getLocation());
                continue;
            }
            double distance = mob.getLocation().distance(owner.getLocation());
            if (distance > 24.0) {
                mob.teleport(owner.getLocation().clone().add(
                        (Math.random() - 0.5) * 3.0, 0.0, (Math.random() - 0.5) * 3.0));
                distance = 0.0;
            }
            if (target != null && target.isValid() && !target.isDead()) {
                mob.setTarget(target);
                mob.getPathfinder().moveTo(target, 1.2);
            } else {
                if (mob.getTarget() instanceof Player || isAlly(mob.getTarget())) {
                    mob.setTarget(null);
                }
                if (distance > 4.0) {
                    mob.getPathfinder().moveTo(owner.getLocation(), 1.1);
                }
            }
        }

        for (UUID minionId : remove) {
            owned.remove(minionId);
            minionOwner.remove(minionId);
            Entity entity = Bukkit.getEntity(minionId);
            if (entity != null && entity.getScoreboardTags().contains(MINION_TAG)) {
                entity.remove();
            }
        }
    }

    private LivingEntity resolveCombatTarget(Player owner, long now) {
        CombatTarget combat = combatTargets.get(owner.getUniqueId());
        if (combat == null || combat.expiresAt() < now) {
            combatTargets.remove(owner.getUniqueId());
            return null;
        }
        Entity entity = Bukkit.getEntity(combat.entity());
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()
                || living.equals(owner) || isAlly(living)) {
            combatTargets.remove(owner.getUniqueId());
            return null;
        }
        return living;
    }

    private LivingEntity findHostile(Player owner) {
        double radius = Math.max(4.0, plugin.getConfig().getDouble("powers.pig-hostile-radius", 12.0));
        LivingEntity nearest = null;
        double nearestDistance = radius * radius;
        for (Entity entity : owner.getWorld().getNearbyEntities(owner.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof Enemy enemy) || !enemy.isValid() || enemy.isDead()
                    || entity instanceof Player || isAlly(entity)) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(owner.getLocation());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = enemy;
            }
        }
        return nearest;
    }

    private void releaseServants(Player owner) {
        releaseServants(owner.getUniqueId());
    }

    private void releaseServants(UUID ownerId) {
        Set<UUID> owned = servants.remove(ownerId);
        if (owned == null) {
            return;
        }
        for (UUID servantId : owned) {
            UUID currentOwner = servantOwner.remove(servantId);
            if (currentOwner != null && !currentOwner.equals(ownerId)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(servantId);
            if (entity instanceof Pig pig) {
                pig.setGlowing(false);
                pig.setTarget(null);
                pig.removeScoreboardTag(SERVANT_TAG);
                pig.setPersistent(false);
            }
        }
    }

    private void removeServant(UUID servantId) {
        servantOwner.remove(servantId);
        for (Set<UUID> owned : servants.values()) {
            owned.remove(servantId);
        }
        Entity entity = Bukkit.getEntity(servantId);
        if (entity instanceof Pig pig) {
            pig.setGlowing(false);
            pig.removeScoreboardTag(SERVANT_TAG);
        }
    }

    private boolean isServant(Entity entity) {
        return entity != null && servantOwner.containsKey(entity.getUniqueId());
    }

    private boolean isMinion(Entity entity) {
        return entity instanceof LivingEntity && entity.getScoreboardTags().contains(MINION_TAG);
    }

    private boolean isAlly(Entity entity) {
        return isServant(entity) || isMinion(entity);
    }

    private static EntityType wornHead(Player player) {
        return HeadFactory.mobType(player.getInventory().getHelmet());
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        EntityType head = wornHead(player);
        if (head == null) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null && block.getType().asBlockType().isInteractable()) {
                return;
            }
        } else if (action != Action.RIGHT_CLICK_AIR) {
            return;
        }

        boolean handled = false;
        for (HeadAbility ability : HeadAbilities.abilities(head)) {
            if (ability.active()) {
                handled |= activate(player, ability, head);
            }
        }
        if (handled) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    private boolean activate(Player player, HeadAbility ability, EntityType head) {
        long now = System.currentTimeMillis();
        long ready = readyAt(player, ability);
        if (ready > now) {
            long seconds = Math.max(1L, (ready - now + 999L) / 1000L);
            player.sendActionBar(Component.text(ability.displayName() + " recharging: "
                    + seconds + "s", NamedTextColor.GRAY));
            return true;
        }
        if (perform(player, ability, head)) {
            markCooldown(player, ability, now);
        }
        return true;
    }

    private boolean perform(Player player, HeadAbility ability, EntityType head) {
        return switch (ability) {
            case EXPLOSION -> {
                creeperBlast(player);
                yield true;
            }
            case SUMMON_PIGS -> {
                commandPigs(player);
                yield true;
            }
            case TELEPORT -> enderTeleport(player);
            case FIRE_CHARGE -> {
                fireCharge(player);
                yield true;
            }
            case SHOOT_ARROW -> {
                shootArrow(player);
                yield true;
            }
            case THROW_SNOWBALL -> {
                throwSnowball(player);
                yield true;
            }
            case SONIC_BOOM -> {
                sonicBoom(player);
                yield true;
            }
            case WITHER_SKULL -> {
                witherSkull(player);
                yield true;
            }
            case POISON_CLOUD -> {
                poisonCloud(player);
                yield true;
            }
            case WEB -> webTrap(player);
            case INVISIBILITY -> {
                invisibility(player);
                yield true;
            }
            case SHIELD -> {
                shield(player);
                yield true;
            }
            case LIGHTNING -> {
                lightning(player);
                yield true;
            }
            case FIRE_AURA -> {
                fireAura(player);
                yield true;
            }
            case WATER_BURST -> {
                waterBurst(player);
                yield true;
            }
            case PULL -> {
                pull(player);
                yield true;
            }
            case HEAL -> {
                heal(player);
                yield true;
            }
            case DASH -> {
                dash(player);
                yield true;
            }
            case LEAP -> {
                leap(player);
                yield true;
            }
            case SUMMON_MINIONS -> summonMinions(player, head);
            default -> false;
        };
    }

    private long readyAt(Player player, HeadAbility ability) {
        EnumMap<HeadAbility, Long> cooldowns = abilityReadyAt.get(player.getUniqueId());
        return cooldowns == null ? 0L : cooldowns.getOrDefault(ability, 0L);
    }

    private void markCooldown(Player player, HeadAbility ability, long now) {
        double seconds = plugin.getConfig().getDouble(ability.cooldownPath(),
                ability.defaultCooldown());
        long until = now + (long) (Math.max(0.0, seconds) * 1000L);
        abilityReadyAt.computeIfAbsent(player.getUniqueId(),
                key -> new EnumMap<>(HeadAbility.class)).put(ability, until);
    }

    private double cfg(String path, double fallback) {
        return plugin.getConfig().getDouble(path, fallback);
    }

    private int cfgInt(String path, int fallback) {
        return plugin.getConfig().getInt(path, fallback);
    }

    private boolean cfgBool(String path, boolean fallback) {
        return plugin.getConfig().getBoolean(path, fallback);
    }

    private void creeperBlast(Player player) {
        long now = System.currentTimeMillis();
        double power = (float) Math.max(0.5, cfg("powers.creeper-power", 3.0));
        blastImmuneUntil.put(player.getUniqueId(), now + 3000L);
        player.getWorld().createExplosion(player.getLocation(), (float) power,
                false, false, player);
        player.sendActionBar(Component.text("Entity-only creeper blast!",
                NamedTextColor.RED));
    }

    private void commandPigs(Player player) {
        UUID id = player.getUniqueId();
        double radius = Math.max(4.0, cfg("powers.pig-radius", 16.0));
        int max = Math.max(1, cfgInt("powers.pig-max-servants", 12));
        List<Pig> nearby = new ArrayList<>();
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                radius, radius, radius)) {
            if (entity instanceof Pig pig && pig.isValid() && !pig.isDead()) {
                nearby.add(pig);
            }
        }
        nearby.sort(Comparator.comparingDouble(
                pig -> pig.getLocation().distanceSquared(player.getLocation())));

        Set<UUID> owned = servants.computeIfAbsent(id, key -> new HashSet<>());
        int added = 0;
        for (Pig pig : nearby) {
            if (added >= max) {
                break;
            }
            UUID pigId = pig.getUniqueId();
            UUID currentOwner = servantOwner.get(pigId);
            if (currentOwner != null && !currentOwner.equals(id)) {
                continue;
            }
            owned.add(pigId);
            servantOwner.put(pigId, id);
            pig.addScoreboardTag(SERVANT_TAG);
            pig.setGlowing(true);
            pig.setPersistent(true);
            added++;
        }

        if (added == 0) {
            player.sendActionBar(Component.text("No nearby pigs answered the call.",
                    NamedTextColor.GRAY));
        } else {
            player.sendActionBar(Component.text(added + " pigs now protect you!",
                    NamedTextColor.GREEN));
        }
    }

    private boolean enderTeleport(Player player) {
        double radius = Math.max(4.0, cfg("powers.teleport-radius", 12.0));
        Location origin = player.getLocation();
        for (int i = 0; i < 16; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double distance = 4.0 + Math.random() * (radius - 4.0);
            Location candidate = origin.clone().add(Math.cos(angle) * distance, 0.0,
                    Math.sin(angle) * distance);
            candidate.setY(origin.getWorld().getHighestBlockYAt(candidate) + 1.0);
            candidate.setYaw(origin.getYaw());
            candidate.setPitch(origin.getPitch());
            if (isSafe(candidate, player)) {
                player.teleport(candidate);
                player.getWorld().spawnParticle(Particle.PORTAL,
                        origin.add(0.0, 1.0, 0.0), 24, 0.4, 0.7, 0.4, 0.1);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.0f);
                player.sendActionBar(Component.text("Ender teleport!",
                        NamedTextColor.DARK_PURPLE));
                return true;
            }
        }
        player.sendActionBar(Component.text("No safe teleport spot found.",
                NamedTextColor.GRAY));
        return false;
    }

    private static boolean isSafe(Location location, Player player) {
        if (!location.getBlock().getType().isAir()
                && !location.getBlock().isPassable()) {
            return false;
        }
        Location above = location.clone().add(0.0, 1.0, 0.0);
        if (!above.getBlock().isPassable()) {
            return false;
        }
        Material below = location.clone().add(0.0, -1.0, 0.0).getBlock().getType();
        if (below == Material.LAVA || below == Material.WATER
                || below == Material.FIRE || below == Material.CACTUS
                || below == Material.MAGMA_BLOCK || below == Material.POWDER_SNOW) {
            return false;
        }
        return player.getWorld().getWorldBorder().isInside(location);
    }

    private void fireCharge(Player player) {
        Location eye = player.getEyeLocation();
        Fireball fireball = player.getWorld().spawn(eye, Fireball.class);
        fireball.setShooter(player);
        fireball.setDirection(player.getLocation().getDirection().normalize()
                .multiply(Math.max(0.5, cfg("powers.fire-charge-speed", 1.8))));
        fireball.setYield((float) Math.max(0.0, cfg("powers.fire-charge-yield", 0.0)));
        fireball.setIsIncendiary(cfgBool("powers.fire-charge-incendiary", false));
        fireball.addScoreboardTag(FIRE_CHARGE_TAG);
        player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.0f);
        player.sendActionBar(Component.text("Fire charge thrown!",
                NamedTextColor.GOLD));
    }

    private void shootArrow(Player player) {
        Arrow arrow = player.getWorld().spawn(player.getEyeLocation(), Arrow.class);
        arrow.setShooter(player);
        arrow.setVelocity(player.getLocation().getDirection().normalize()
                .multiply(Math.max(0.5, cfg("powers.arrow-speed", 2.5))));
        arrow.setDamage(Math.max(0.5, cfg("powers.arrow-damage", 4.0)));
        arrow.setCritical(true);
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.0f);
        player.sendActionBar(Component.text("Arrow shot!", NamedTextColor.YELLOW));
    }

    private void throwSnowball(Player player) {
        Snowball snowball = player.getWorld().spawn(player.getEyeLocation(), Snowball.class);
        snowball.setShooter(player);
        snowball.addScoreboardTag(SNOWBALL_TAG);
        snowball.setVelocity(player.getLocation().getDirection().normalize()
                .multiply(Math.max(0.5, cfg("powers.snowball-speed", 1.6))));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1.0f);
        player.sendActionBar(Component.text("Freezing snowball!",
                NamedTextColor.AQUA));
    }

    private void witherSkull(Player player) {
        WitherSkull skull = player.getWorld().spawn(player.getEyeLocation(), WitherSkull.class);
        skull.setShooter(player);
        skull.setDirection(player.getLocation().getDirection().normalize()
                .multiply(Math.max(0.5, cfg("powers.wither-skull-speed", 1.2))));
        skull.setCharged(false);
        skull.setYield(0.0f);
        skull.addScoreboardTag(WITHER_SKULL_TAG);
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.0f);
        player.sendActionBar(Component.text("Wither skull launched!",
                NamedTextColor.DARK_GRAY));
    }

    private void sonicBoom(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        double range = Math.max(2.0, cfg("powers.sonic-boom-range", 10.0));
        double damage = Math.max(0.0, cfg("powers.sonic-boom-damage", 10.0));
        int hits = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                range, range, range)) {
            if (!(entity instanceof LivingEntity target) || target.equals(player)
                    || isAlly(target)) {
                continue;
            }
            Vector offset = target.getLocation().toVector()
                    .subtract(player.getLocation().toVector());
            double distance = offset.length();
            if (distance < 0.5 || distance > range) {
                continue;
            }
            Vector normalized = offset.clone().normalize();
            if (normalized.dot(direction) < 0.45) {
                continue;
            }
            target.damage(damage, player);
            target.setVelocity(target.getVelocity().add(normalized.multiply(1.8)).setY(0.5));
            hits++;
        }
        player.getWorld().spawnParticle(Particle.SONIC_BOOM,
                player.getEyeLocation().add(direction.multiply(2.0)), 1);
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.0f);
        player.sendActionBar(Component.text("Sonic boom! (" + hits + " hit"
                + (hits == 1 ? "" : "s") + ")", NamedTextColor.DARK_AQUA));
    }

    private void poisonCloud(Player player) {
        double radius = Math.max(2.0, cfg("powers.poison-radius", 5.0));
        int hits = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(player)
                    || isAlly(target)) {
                continue;
            }
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                    100, 1, true, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    100, 0, true, true, true));
            hits++;
        }
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0.0, 1.0, 0.0),
                30, radius / 2.0, 0.8, radius / 2.0, 0.02);
        player.sendActionBar(Component.text("Poison cloud! (" + hits + " hit"
                + (hits == 1 ? "" : "s") + ")", NamedTextColor.GREEN));
    }

    private boolean webTrap(Player player) {
        Vector forward = player.getLocation().getDirection().clone().setY(0.0);
        if (forward.lengthSquared() < 0.01) {
            forward = new Vector(0.0, 0.0, 1.0);
        }
        Block target = player.getLocation().add(forward.normalize().multiply(1.5)).getBlock();
        if (target.getType() != Material.AIR) {
            target = player.getLocation().getBlock();
        }
        if (target.getType() != Material.AIR || !target.isPassable()) {
            player.sendActionBar(Component.text("No open space for a web.",
                    NamedTextColor.GRAY));
            return false;
        }
        Material previous = target.getType();
        final Block webBlock = target;
        target.setType(Material.COBWEB, false);
        long ticks = (long) Math.max(1.0, cfg("powers.web-duration", 5.0) * 20.0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (webBlock.getType() == Material.COBWEB) {
                webBlock.setType(previous, false);
            }
        }, ticks);
        player.playSound(player.getLocation(), Sound.BLOCK_COBWEB_PLACE, 0.8f, 1.0f);
        player.sendActionBar(Component.text("Web trap placed!", NamedTextColor.WHITE));
        return true;
    }

    private void invisibility(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                200, 0, true, false, true));
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0.0, 1.0, 0.0),
                20, 0.4, 0.6, 0.4, 0.02);
        player.sendActionBar(Component.text("You fade from sight.",
                NamedTextColor.LIGHT_PURPLE));
    }

    private void shield(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                160, 2, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                160, 1, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                80, 0, true, false, true));
        player.sendActionBar(Component.text("Protective shell active!",
                NamedTextColor.AQUA));
    }

    private void lightning(Player player) {
        Location target = player.getLocation().add(
                player.getLocation().getDirection().setY(0.0).normalize().multiply(6.0));
        Block block = player.getTargetBlockExact((int) Math.max(4.0,
                cfg("powers.lightning-range", 12.0)));
        if (block != null) {
            target = block.getLocation().add(0.5, 1.0, 0.5);
        }
        player.getWorld().strikeLightningEffect(target);
        double damage = Math.max(0.0, cfg("powers.lightning-damage", 8.0));
        int hits = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(target, 3.0, 3.0, 3.0)) {
            if (!(entity instanceof LivingEntity victim) || victim.equals(player)
                    || isAlly(victim)) {
                continue;
            }
            victim.damage(damage, player);
            victim.setFireTicks(Math.max(victim.getFireTicks(), 40));
            hits++;
        }
        player.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.2f);
        player.sendActionBar(Component.text("Lightning strike! (" + hits + " hit"
                + (hits == 1 ? "" : "s") + ")", NamedTextColor.YELLOW));
    }

    private void fireAura(Player player) {
        double radius = Math.max(2.0, cfg("powers.fire-aura-radius", 5.0));
        int hits = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(player)
                    || isAlly(target)) {
                continue;
            }
            target.setFireTicks(Math.max(target.getFireTicks(), 80));
            target.damage(Math.max(0.0, cfg("powers.fire-aura-damage", 2.0)), player);
            hits++;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                100, 0, true, false, true));
        player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0.0, 1.0, 0.0),
                40, radius / 2.0, 0.8, radius / 2.0, 0.02);
        player.sendActionBar(Component.text("Fire aura! (" + hits + " ignited)",
                NamedTextColor.GOLD));
    }

    private void waterBurst(Player player) {
        double radius = Math.max(2.0, cfg("powers.water-burst-radius", 6.0));
        double power = Math.max(0.2, cfg("powers.water-burst-power", 0.9));
        int hits = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(player)
                    || isAlly(target)) {
                continue;
            }
            Vector push = target.getLocation().toVector()
                    .subtract(player.getLocation().toVector());
            if (push.lengthSquared() < 0.01) {
                push = player.getLocation().getDirection();
            }
            target.setVelocity(push.normalize().multiply(power).setY(0.35));
            hits++;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,
                120, 0, true, false, true));
        player.getWorld().spawnParticle(Particle.SPLASH, player.getLocation().add(0.0, 1.0, 0.0),
                40, radius / 2.0, 0.8, radius / 2.0, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 1.0f, 0.8f);
        player.sendActionBar(Component.text("Tidal burst! (" + hits + " pushed)",
                NamedTextColor.BLUE));
    }

    private void pull(Player player) {
        double radius = Math.max(2.0, cfg("powers.pull-radius", 10.0));
        double power = Math.max(0.2, cfg("powers.pull-power", 1.1));
        int hits = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(player)
                    || isAlly(target)) {
                continue;
            }
            Vector toward = player.getLocation().toVector()
                    .subtract(target.getLocation().toVector());
            if (toward.lengthSquared() < 0.01) {
                continue;
            }
            target.setVelocity(toward.normalize().multiply(power).setY(0.35));
            hits++;
        }
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0.0, 1.0, 0.0),
                30, radius / 2.0, 0.8, radius / 2.0, 0.05);
        player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 1.2f);
        player.sendActionBar(Component.text("Whirlpool! (" + hits + " pulled)",
                NamedTextColor.DARK_AQUA));
    }

    private void heal(Player player) {
        double amount = Math.max(1.0, cfg("powers.heal-amount", 6.0));
        double max = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.min(max, player.getHealth() + amount));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                60, 0, true, false, true));
        player.getWorld().spawnParticle(Particle.HEART,
                player.getLocation().add(0.0, 1.5, 0.0), 8, 0.4, 0.4, 0.4, 0.0);
        player.sendActionBar(Component.text("Healing power!", NamedTextColor.GREEN));
    }

    private void dash(Player player) {
        Vector direction = player.getLocation().getDirection().clone().setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(0.0, 0.0, 1.0);
        }
        double power = Math.max(0.2, cfg("powers.dash-power", 1.2));
        player.setVelocity(direction.normalize().multiply(power)
                .setY(Math.max(player.getVelocity().getY(), 0.25)));
        player.setFallDistance(0.0f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(),
                12, 0.2, 0.1, 0.2, 0.05);
        player.sendActionBar(Component.text("Dash!", NamedTextColor.WHITE));
    }

    private void leap(Player player) {
        Vector direction = player.getLocation().getDirection().clone().setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(0.0, 0.0, 1.0);
        }
        double horizontal = Math.max(0.0, cfg("powers.leap-horizontal", 0.5));
        double power = Math.max(0.2, cfg("powers.leap-power", 1.0));
        player.setVelocity(direction.normalize().multiply(horizontal).setY(power));
        player.setFallDistance(0.0f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(),
                12, 0.2, 0.1, 0.2, 0.05);
        player.sendActionBar(Component.text("Leap!", NamedTextColor.AQUA));
    }

    private boolean summonMinions(Player player, EntityType head) {
        UUID ownerId = player.getUniqueId();
        clearMinions(ownerId);

        int count = Math.max(1, cfgInt("powers.minions-count", 2));
        Set<UUID> spawned = minions.computeIfAbsent(ownerId, key -> new HashSet<>());
        LivingEntity hostile = findHostile(player);
        int added = 0;
        for (int i = 0; i < count; i++) {
            Location location = player.getLocation().clone().add(
                    (Math.random() - 0.5) * 4.0, 0.0, (Math.random() - 0.5) * 4.0);
            location.setY(player.getWorld().getHighestBlockYAt(location) + 1.0);
            try {
                Entity entity = player.getWorld().spawnEntity(location, head);
                entity.setPersistent(false);
                entity.addScoreboardTag(MINION_TAG);
                if (entity instanceof Mob mob && hostile != null) {
                    mob.setTarget(hostile);
                }
                minionOwner.put(entity.getUniqueId(), ownerId);
                spawned.add(entity.getUniqueId());
                added++;
            } catch (IllegalArgumentException exception) {
                break;
            }
        }

        if (added == 0) {
            minions.remove(ownerId);
            player.sendActionBar(Component.text("No minions could answer.",
                    NamedTextColor.GRAY));
            return false;
        }

        double lifetime = Math.max(5.0, cfg("powers.minions-lifetime", 20.0));
        BukkitTask removal = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            minionTasks.remove(ownerId);
            removeMinions(ownerId);
        }, (long) (lifetime * 20.0));
        minionTasks.put(ownerId, removal);
        player.sendActionBar(Component.text(added + " loyal minions summoned!",
                NamedTextColor.DARK_GREEN));
        return true;
    }

    private void clearMinions(UUID ownerId) {
        BukkitTask removal = minionTasks.remove(ownerId);
        if (removal != null) {
            removal.cancel();
        }
        removeMinions(ownerId);
    }

    private void removeMinions(UUID ownerId) {
        Set<UUID> owned = minions.remove(ownerId);
        if (owned == null) {
            return;
        }
        for (UUID minionId : owned) {
            minionOwner.remove(minionId);
            Entity entity = Bukkit.getEntity(minionId);
            if (entity != null && entity.getScoreboardTags().contains(MINION_TAG)) {
                entity.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }
        long immuneUntil = blastImmuneUntil.getOrDefault(player.getUniqueId(), 0L);
        if (immuneUntil >= System.currentTimeMillis()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();
        if (event.getDamager() instanceof Player player
                && event.getEntity() instanceof LivingEntity victim
                && !victim.getUniqueId().equals(player.getUniqueId())
                && !isAlly(victim)) {
            applyKnockback(player, victim);
            combatTargets.put(player.getUniqueId(), new CombatTarget(victim.getUniqueId(),
                    now + COMBAT_TARGET_MS));
            orderServants(player, victim);
        }

        if (event.getEntity() instanceof Player player) {
            LivingEntity attacker = attacker(event);
            if (attacker != null && !attacker.getUniqueId().equals(player.getUniqueId())
                    && !isAlly(attacker)) {
                applyThorns(player, attacker);
                combatTargets.put(player.getUniqueId(), new CombatTarget(attacker.getUniqueId(),
                        now + COMBAT_TARGET_MS));
                orderServants(player, attacker);
            }
        }
    }

    private void applyKnockback(Player player, LivingEntity victim) {
        EntityType head = wornHead(player);
        if (head == null || !HeadAbilities.abilities(head).contains(HeadAbility.KNOCKBACK)) {
            return;
        }
        double strength = cfg("powers.knockback-strength", 1.8);
        if (head == EntityType.IRON_GOLEM) {
            strength = cfg("powers.iron-golem-knockback", 3.5);
        }
        Vector direction = victim.getLocation().toVector()
                .subtract(player.getLocation().toVector());
        if (direction.lengthSquared() < 0.01) {
            direction = player.getLocation().getDirection();
        }
        direction = direction.normalize().multiply(strength);
        direction.setY(Math.max(0.4, strength * 0.35));
        victim.setVelocity(victim.getVelocity().add(direction));
    }

    private void applyThorns(Player player, LivingEntity attacker) {
        EntityType head = wornHead(player);
        if (head == null || !HeadAbilities.abilities(head).contains(HeadAbility.THORNS)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (readyAt(player, HeadAbility.THORNS) > now) {
            return;
        }
        markCooldown(player, HeadAbility.THORNS, now);

        Vector direction = attacker.getLocation().toVector()
                .subtract(player.getLocation().toVector());
        if (direction.lengthSquared() < 0.01) {
            direction = player.getLocation().getDirection();
        }
        direction = direction.normalize()
                .multiply(Math.max(0.1, cfg("powers.thorns-knockback", 0.9)));
        direction.setY(0.4);
        attacker.setVelocity(attacker.getVelocity().add(direction));
        if (!(attacker instanceof Player)) {
            attacker.damage(Math.max(0.0, cfg("powers.thorns-damage", 2.0)), player);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof LivingEntity victim)) {
            return;
        }
        Projectile projectile = event.getEntity();
        if (projectile instanceof Snowball snowball
                && snowball.getScoreboardTags().contains(SNOWBALL_TAG)) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    100, 2, true, true, true));
            victim.setFreezeTicks(Math.max(victim.getFreezeTicks(), 100));
            if (snowball.getShooter() instanceof Player shooter) {
                victim.damage(Math.max(0.0, cfg("powers.snowball-damage", 2.0)), shooter);
            }
            return;
        }
        if (projectile instanceof Fireball fireball
                && fireball.getScoreboardTags().contains(FIRE_CHARGE_TAG)) {
            if (fireball.getShooter() instanceof Player shooter) {
                victim.damage(Math.max(0.0, cfg("powers.fire-charge-damage", 5.0)), shooter);
                victim.setFireTicks(Math.max(victim.getFireTicks(),
                        cfgInt("powers.fire-charge-fire-ticks", 60)));
                victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation(),
                        12, 0.3, 0.4, 0.3, 0.02);
            }
            return;
        }
        if (projectile instanceof WitherSkull skull
                && skull.getScoreboardTags().contains(WITHER_SKULL_TAG)) {
            if (skull.getShooter() instanceof Player shooter) {
                victim.damage(Math.max(0.0, cfg("powers.wither-skull-damage", 8.0)), shooter);
                victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        100, 1, true, true, true));
            }
        }
    }

    private static LivingEntity attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity living) {
            return living;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        return null;
    }

    private void orderServants(Player owner, LivingEntity target) {
        Set<UUID> owned = servants.get(owner.getUniqueId());
        if (owned == null) {
            return;
        }
        for (UUID servantId : owned) {
            Entity entity = Bukkit.getEntity(servantId);
            if (entity instanceof Pig pig && pig.isValid() && !pig.isDead()
                    && pig.getWorld() == target.getWorld()) {
                pig.setTarget(target);
                pig.getPathfinder().moveTo(target, 1.25);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!isMinion(event.getEntity())) {
            return;
        }
        Entity target = event.getTarget();
        if (target instanceof Player || isAlly(target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Pig pig) {
            removeServant(pig.getUniqueId());
        }
        if (isMinion(event.getEntity())) {
            UUID minionId = event.getEntity().getUniqueId();
            minionOwner.remove(minionId);
            for (Set<UUID> owned : minions.values()) {
                owned.remove(minionId);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        releaseServants(id);
        revokeFlight(player);
        clearMinions(id);
        combatTargets.remove(id);
        abilityReadyAt.remove(id);
        blastImmuneUntil.remove(id);
        cowGrassSeconds.remove(id);
        cowNextHeal.remove(id);
    }
}
