package minecraft.economy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public final class EconomyLore {
   public static final String PREFIX = "§8[§6Eco§8] ";
   private static final String SELLER_AXE_TAG = "§6§l[Seller Axe]";
   private static long lastLoreTick = 0L;

   public static void tick(MinecraftServer server) {
      if (server != null) {
         long now = System.currentTimeMillis();
         if (now - lastLoreTick >= 500L) {
            lastLoreTick = now;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               AbstractContainerMenu menu = player.containerMenu;
               if (menu != null && !(menu instanceof EconomyGui.EcoMenu) && !(menu instanceof SellGui.SellBinMenu)) {
                  for (Slot slot : menu.slots) {
                     if (slot.hasItem()) {
                        stripOldLore(slot.getItem());
                     }
                  }

                  ItemStack carried = menu.getCarried();
                  if (!carried.isEmpty()) {
                     stripOldLore(carried);
                  }
               }
            }
         }
      }
   }

   private static void stripOldLore(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         ItemLore current = (ItemLore)stack.get(DataComponents.LORE);
         if (current != null) {
            List<Component> lines = new ArrayList<>(current.lines());
            boolean removed = lines.removeIf(
               c -> {
                  String txt = c.getString();
                  return txt.startsWith("§8[§6Eco§8] ")
                     || txt.contains("[Eco]")
                     || txt.contains("[Seller Axe]")
                     || txt.contains("Break a chest to instantly sell")
                     || txt.contains("Works on single and double chests");
               }
            );
            if (removed) {
               if (lines.isEmpty()) {
                  stack.remove(DataComponents.LORE);
               } else {
                  stack.set(DataComponents.LORE, new ItemLore(lines));
               }
            }
         }
      }
   }

   private EconomyLore() {
   }
}
