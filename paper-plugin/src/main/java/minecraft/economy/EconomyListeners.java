package minecraft.economy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public final class EconomyListeners implements Listener {
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      Player bukkitPlayer = event.getPlayer();
      if (!(bukkitPlayer instanceof CraftPlayer craftPlayer)) {
         return;
      }

      Block block = event.getBlock();
      BlockPos pos;
      if (block instanceof CraftBlock craftBlock) {
         pos = craftBlock.getPosition();
      } else {
         pos = new BlockPos(block.getX(), block.getY(), block.getZ());
      }

      ServerPlayer player = craftPlayer.getHandle();
      ServerLevel level = ((CraftWorld)block.getWorld()).getHandle();
      if (SellerAxe.handleChestBreak(player, level, pos)) {
         event.setCancelled(true);
      }
   }
}
