package minecraft.heads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** Applies the power of the mob head a player is wearing. */
public final class HeadPowers implements Listener {

    private static final int EFFECT_DURATION = 60;
    private static final int COW_GRASS_SECONDS = 5;
    private static final long COW_HEAL_INTERVAL_MS = 2000L;
    private static final long COMBAT_TARGET_MS = 10000L;
    private static final String SERVANT_TAG = "heads_servant";

    private final HeadsPlugin plugin;
    private final Map<UUID, Set<UUID>> servants = new HashMap<>();
    private final Map<UUID, UUID> servantOwner = new HashMap<>();
    private final Map<UUID, Long> creeperReadyAt = new HashMap<>();
    private final Map<UUID, Long> pigReadyAt = new HashMap<>();
    private final Map<UUID, Long> enderReadyAt = new HashMap<>();
    private final Map<UUID, Long> blastImmuneUntil = new HashMap<>();
    private final Map<UUID, CombatTarget> combatTargets = new HashMap<>();
    private final Map<UUID, Integer> cowGrassSeconds = new HashMap<>();
    private final Map<UUID, Long> cowNextHeal = new HashMap<>();
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
        for (UUID owner : new ArrayList<>(servants.keySet())) {
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                releaseServants(player);
            } else {
                releaseServants(owner);
            }
        }
        servants.clear();
        servantOwner.clear();
        creeperReadyAt.clear();
        pigReadyAt.clear();
        enderReadyAt.clear();
        blastImmuneUntil.clear();
        combatTargets.clear();
        cowGrassSeconds.clear();
        cowNextHeal.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        blastImmuneUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        combatTargets.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        creeperReadyAt.entrySet().removeIf(entry -> entry.getValue() + 60000L <= now);
        pigReadyAt.entrySet().removeIf(entry -> entry.getValue() + 60000L <= now);
        enderReadyAt.entrySet().removeIf(entry -> entry.getValue() + 60000L <= now);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            EntityType head = wornHead(player);
            if (head == null) {
                cowGrassSeconds.remove(id);
                cowNextHeal.remove(id);
                if (servants.containsKey(id)) {
                    releaseServants(player);
                }
                continue;
            }

            applyProfile(player, head);

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

    private LivingEntity resolveCombatTarget(Player owner, long now) {
        CombatTarget combat = combatTargets.get(owner.getUniqueId());
        if (combat == null || combat.expiresAt() < now) {
            combatTargets.remove(owner.getUniqueId());
            return null;
        }
        Entity entity = Bukkit.getEntity(combat.entity());
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()
                || living.equals(owner) || isServant(living)) {
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
                    || entity instanceof Player || isServant(entity)) {
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

        boolean handled = switch (head) {
            case CREEPER -> {
                creeperBlast(player);
                yield true;
            }
            case PIG -> {
                commandPigs(player);
                yield true;
            }
            case ENDERMAN -> {
                enderTeleport(player);
                yield true;
            }
            default -> false;
        };
        if (handled) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    private void creeperBlast(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long ready = creeperReadyAt.getOrDefault(id, 0L);
        if (now < ready) {
            long seconds = Math.max(1L, (ready - now + 999L) / 1000L);
            player.sendActionBar(Component.text("Creeper power recharging: " + seconds + "s",
                    NamedTextColor.GRAY));
            return;
        }
        double cooldown = Math.max(1.0, plugin.getConfig().getDouble("powers.creeper-cooldown", 10.0));
        creeperReadyAt.put(id, now + (long) (cooldown * 1000L));
        float power = (float) Math.max(0.5, plugin.getConfig().getDouble("powers.creeper-power", 3.0));
        boolean breakBlocks = plugin.getConfig().getBoolean("powers.creeper-break-blocks", false);
        blastImmuneUntil.put(id, now + 3000L);
        player.getWorld().createExplosion(player.getLocation(), power, false, breakBlocks, player);
        player.sendActionBar(Component.text("Creeper blast!", NamedTextColor.RED));
    }

    private void commandPigs(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long ready = pigReadyAt.getOrDefault(id, 0L);
        if (now < ready) {
            long seconds = Math.max(1L, (ready - now + 999L) / 1000L);
            player.sendActionBar(Component.text("Pig command recharging: " + seconds + "s",
                    NamedTextColor.GRAY));
            return;
        }
        double cooldown = Math.max(1.0, plugin.getConfig().getDouble("powers.pig-cooldown", 5.0));
        pigReadyAt.put(id, now + (long) (cooldown * 1000L));

        double radius = Math.max(4.0, plugin.getConfig().getDouble("powers.pig-radius", 16.0));
        int max = Math.max(1, plugin.getConfig().getInt("powers.pig-max-servants", 12));
        List<Pig> nearby = new ArrayList<>();
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius)) {
            if (entity instanceof Pig pig && pig.isValid() && !pig.isDead()) {
                nearby.add(pig);
            }
        }
        nearby.sort(Comparator.comparingDouble(pig -> pig.getLocation().distanceSquared(player.getLocation())));

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

    private void enderTeleport(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long ready = enderReadyAt.getOrDefault(id, 0L);
        if (now < ready) {
            long seconds = Math.max(1L, (ready - now + 999L) / 1000L);
            player.sendActionBar(Component.text("Ender power recharging: " + seconds + "s",
                    NamedTextColor.GRAY));
            return;
        }
        double cooldown = Math.max(1.0, plugin.getConfig().getDouble("powers.enderman-cooldown", 5.0));
        enderReadyAt.put(id, now + (long) (cooldown * 1000L));

        Location origin = player.getLocation();
        for (int i = 0; i < 12; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double distance = 4.0 + Math.random() * 8.0;
            Location candidate = origin.clone().add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
            candidate.setY(origin.getWorld().getHighestBlockYAt(candidate) + 1.0);
            candidate.setYaw(origin.getYaw());
            candidate.setPitch(origin.getPitch());
            if (isSafe(candidate, player)) {
                player.teleport(candidate);
                player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL,
                        origin.add(0.0, 1.0, 0.0), 24, 0.4, 0.7, 0.4, 0.1);
                player.sendActionBar(Component.text("Ender teleport!", NamedTextColor.DARK_PURPLE));
                return;
            }
        }
        player.sendActionBar(Component.text("No safe teleport spot found.", NamedTextColor.GRAY));
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
                && !isServant(victim)) {
            combatTargets.put(player.getUniqueId(), new CombatTarget(victim.getUniqueId(),
                    now + COMBAT_TARGET_MS));
            orderServants(player, victim);
        }

        if (event.getEntity() instanceof Player player) {
            LivingEntity attacker = attacker(event);
            if (attacker != null && !attacker.getUniqueId().equals(player.getUniqueId())
                    && !isServant(attacker)) {
                combatTargets.put(player.getUniqueId(), new CombatTarget(attacker.getUniqueId(),
                        now + COMBAT_TARGET_MS));
                orderServants(player, attacker);
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

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Pig pig) {
            removeServant(pig.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        releaseServants(id);
        combatTargets.remove(id);
        creeperReadyAt.remove(id);
        pigReadyAt.remove(id);
        enderReadyAt.remove(id);
        blastImmuneUntil.remove(id);
        cowGrassSeconds.remove(id);
        cowNextHeal.remove(id);
    }
}
