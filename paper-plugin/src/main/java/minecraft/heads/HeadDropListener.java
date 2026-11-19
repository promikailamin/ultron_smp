package minecraft.heads;

import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/** Adds a mob head to the drops when a mob is killed. */
public final class HeadDropListener implements Listener {

    private final HeadsPlugin plugin;

    public HeadDropListener(HeadsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || !plugin.textures().has(entity.getType())) {
            return;
        }

        boolean charged = killedByChargedCreeper(entity);
        if (!charged) {
            if (plugin.getConfig().getBoolean("require-player-kill", true)
                    && entity.getKiller() == null) {
                return;
            }
            double chance = Math.max(0.0, Math.min(1.0,
                    plugin.getConfig().getDouble("drop-chance", 1.0)));
            if (Math.random() >= chance) {
                return;
            }
        } else if (!plugin.getConfig().getBoolean("charged-creeper-always-drops", true)) {
            return;
        }

        ItemStack head = HeadFactory.create(plugin.textures(), entity.getType());
        if (head != null) {
            event.getDrops().add(head);
        }
    }

    private static boolean killedByChargedCreeper(LivingEntity entity) {
        EntityDamageEvent last = entity.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        return damager instanceof Creeper creeper && creeper.isPowered();
    }
}
