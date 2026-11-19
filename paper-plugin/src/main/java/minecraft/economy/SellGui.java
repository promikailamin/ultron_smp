package minecraft.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.craftbukkit.inventory.CraftInventoryCustom;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;

public final class SellGui {
   public static void open(ServerPlayer player) {
      EconomyData.bindServer(player.level().getServer());
      MenuProvider provider = new SimpleMenuProvider(
         (containerId, playerInv, p) -> new SellGui.SellBinMenu(containerId, playerInv, (ServerPlayer)p), Component.literal("§6Sell Chest (Drop Items Below)")
      );
      player.openMenu(provider);
      player.playSound(SoundEvents.CHEST_OPEN, 1.0F, 1.0F);
   }

   private SellGui() {
   }

   public static final class SellBinMenu extends AbstractContainerMenu {
      private final ServerPlayer player;
      private final SimpleContainer dropArea;
      private final SimpleContainer navBar;
      private final SimpleContainer viewContainer;
      private InventoryView bukkitView;

      public SellBinMenu(int containerId, Inventory playerInv, ServerPlayer player) {
         super(MenuType.GENERIC_9x6, containerId);
         this.player = player;
         this.dropArea = new SimpleContainer(45);
         this.navBar = new SimpleContainer(9);
         this.viewContainer = new SimpleContainer(54) {
            public boolean isEmpty() {
               return SellBinMenu.this.dropArea.isEmpty() && SellBinMenu.this.navBar.isEmpty();
            }

            public ItemStack getItem(int slot) {
               return slot < 45 ? SellBinMenu.this.dropArea.getItem(slot) : SellBinMenu.this.navBar.getItem(slot - 45);
            }

            public ItemStack removeItem(int slot, int amount) {
               return slot < 45
                  ? SellBinMenu.this.dropArea.removeItem(slot, amount)
                  : SellBinMenu.this.navBar.removeItem(slot - 45, amount);
            }

            public ItemStack removeItemNoUpdate(int slot) {
               return slot < 45
                  ? SellBinMenu.this.dropArea.removeItemNoUpdate(slot)
                  : SellBinMenu.this.navBar.removeItemNoUpdate(slot - 45);
            }

            public void setItem(int slot, ItemStack stack) {
               if (slot < 45) {
                  SellBinMenu.this.dropArea.setItem(slot, stack);
               } else {
                  SellBinMenu.this.navBar.setItem(slot - 45, stack);
               }
            }

            public void setChanged() {
               SellBinMenu.this.dropArea.setChanged();
               SellBinMenu.this.navBar.setChanged();
            }

            public void clearContent() {
               SellBinMenu.this.dropArea.clearContent();
               SellBinMenu.this.navBar.clearContent();
            }
         };

         for (int i = 0; i < 45; i++) {
            this.addSlot(new Slot(this.dropArea, i, 8 + i % 9 * 18, 18 + i / 9 * 18));
         }

         for (int j = 0; j < 9; j++) {
            this.addSlot(new Slot(this.navBar, j, 8 + j * 18, 108) {
               {
                  Objects.requireNonNull(SellBinMenu.this);
               }

               public boolean mayPickup(Player p) {
                  return false;
               }

               public boolean mayPlace(ItemStack stack) {
                  return false;
               }
            });
         }

         int invTop = 139;

         for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
               this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, invTop + row * 18));
            }
         }

         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, invTop + 58));
         }

         this.refreshNavBar();
      }

      private void refreshNavBar() {
         long balance = EconomyData.getBalance(this.player.getUUID());
         this.navBar
            .setItem(
               0, makeNavButton("orange_stained_glass_pane", "§6« Back to Shop", List.of("§7Return to the main shop menu", "§7(Sells chest items on exit)"))
            );
         this.navBar.setItem(1, makeNavButton("gray_stained_glass_pane", " ", List.of()));
         this.navBar
            .setItem(2, makeNavButton("gold_block", "§e§lSell Entire Inventory", List.of("§7Instantly sell all sellable", "§7items from your inventory")));
         this.navBar
            .setItem(
               3,
               makeNavButton(
                  "book",
                  "§e§lHow to Sell",
                  List.of(
                     "§71. Place items in the empty chest above.",
                     "§72. Close the chest (ESC/Exit)",
                     "§7   OR click §a[✔ SELL NOW]§7.",
                     "§aItems in chest are sold automatically!",
                     "§8Non-sellable items are returned safely."
                  )
               )
            );
         ItemStack head = EconomyGui.playerHeadBalanceItem(this.player, balance);
         this.navBar.setItem(4, head);
         this.navBar
            .setItem(5, makeNavButton("emerald_block", "§a§l✔ SELL NOW", List.of("§7Click to sell items immediately,", "§7or simply close the chest!")));
         this.navBar.setItem(6, makeNavButton("gray_stained_glass_pane", " ", List.of()));
         this.navBar.setItem(7, makeNavButton("gray_stained_glass_pane", " ", List.of()));
         this.navBar.setItem(8, makeNavButton("red_stained_glass_pane", "§c§l✖ Exit & Sell", List.of("§7Closes chest and sells all", "§7items placed inside")));
      }

      public boolean stillValid(Player p) {
         return true;
      }

      @Override
      public InventoryView getBukkitView() {
         if (this.bukkitView == null) {
            this.bukkitView = new CraftInventoryView(
               this.player.getBukkitEntity(),
               new CraftInventoryCustom(null, InventoryType.CHEST, this.viewContainer),
               this
            );
         }

         return this.bukkitView;
      }

      public ItemStack quickMoveStack(Player p, int slotIndex) {
         Slot slot = (Slot)this.slots.get(slotIndex);
         if (slot != null && slot.hasItem()) {
            ItemStack sourceStack = slot.getItem();
            ItemStack copy = sourceStack.copy();
            if (slotIndex >= 54) {
               if (!this.moveItemStackTo(sourceStack, 0, 45, false)) {
                  return ItemStack.EMPTY;
               }
            } else {
               if (slotIndex >= 45) {
                  return ItemStack.EMPTY;
               }

               if (!this.moveItemStackTo(sourceStack, 54, 90, true)) {
                  return ItemStack.EMPTY;
               }
            }

            if (sourceStack.isEmpty()) {
               slot.set(ItemStack.EMPTY);
            } else {
               slot.setChanged();
            }

            return copy;
         } else {
            return ItemStack.EMPTY;
         }
      }

      public void clicked(int slotIndex, int button, ContainerInput input, Player p) {
         if (slotIndex >= 45 && slotIndex < 54) {
            int navSlot = slotIndex - 45;
            this.handleNavClick(navSlot);
            this.broadcastFullState();
         } else {
            super.clicked(slotIndex, button, input, p);
         }
      }

      private void handleNavClick(int navSlot) {
         switch (navSlot) {
            case 0:
               this.sellDropArea(true);
               this.player.level().getServer().execute(() -> ShopGui.openMainMenu(this.player));
            case 1:
            case 3:
            case 4:
            case 6:
            case 7:
            default:
               break;
            case 2:
               this.sellPlayerInventory();
               break;
            case 5:
               this.sellDropArea(true);
               break;
            case 8:
               this.player.closeContainer();
         }
      }

      private void sellDropArea(boolean notifyIfEmpty) {
         long totalEarned = 0L;
         int totalCount = 0;

         for (int i = 0; i < 45; i++) {
            ItemStack stack = this.dropArea.getItem(i);
            if (!stack.isEmpty()) {
               Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
               String itemId = key != null ? key.getPath() : "";
               EconomyData.ShopEntry entry = EconomyData.get(itemId);
               if (entry != null && entry.sellable() && !"enchanted_book".equals(entry.id)) {
                  int count = stack.getCount();
                  long earned = entry.sell * count;
                  totalEarned += earned;
                  totalCount += count;
                  this.dropArea.setItem(i, ItemStack.EMPTY);
               }
            }
         }

         if (totalEarned > 0L) {
            EconomyData.add(this.player.getUUID(), totalEarned);
            this.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
            long newBal = EconomyData.getBalance(this.player.getUUID());
            if (newBal < 0L) {
               this.player
                  .sendSystemMessage(
                     Component.literal(
                        "§a✔ Sold §f"
                           + totalCount
                           + " items§a for §6+"
                           + EconomyData.formatMoney(totalEarned)
                           + " §a (Paid toward loan)! Remaining Debt: §c"
                           + EconomyData.formatMoney(-newBal)
                           + "."
                     )
                  );
            } else {
               this.player
                  .sendSystemMessage(
                     Component.literal(
                        "§a✔ Sold §f"
                           + totalCount
                           + " items§a from sell chest for §6+"
                           + EconomyData.formatMoney(totalEarned)
                           + " §a! New Balance: §e"
                           + EconomyData.formatMoney(newBal)
                           + "."
                     )
                  );
            }
         } else if (notifyIfEmpty) {
            this.player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
            this.player.sendSystemMessage(Component.literal("§cNo sellable items in the chest!"));
         }

         this.refreshNavBar();
         this.broadcastFullState();
      }

      private void sellPlayerInventory() {
         long totalEarned = 0L;
         int totalCount = 0;
         Inventory inv = this.player.getInventory();

         for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
               Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
               String itemId = key != null ? key.getPath() : "";
               EconomyData.ShopEntry entry = EconomyData.get(itemId);
               if (entry != null && entry.sellable() && !"enchanted_book".equals(entry.id)) {
                  int count = stack.getCount();
                  long earned = entry.sell * count;
                  totalEarned += earned;
                  totalCount += count;
                  inv.setItem(i, ItemStack.EMPTY);
               }
            }
         }

         if (totalEarned > 0L) {
            EconomyData.add(this.player.getUUID(), totalEarned);
            this.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
            long newBal = EconomyData.getBalance(this.player.getUUID());
            this.player
               .sendSystemMessage(
                  Component.literal(
                     "§a✔ Sold §f"
                        + totalCount
                        + " items§a from your inventory for §6+"
                        + EconomyData.formatMoney(totalEarned)
                        + " §a! New Balance: §e"
                        + EconomyData.formatMoney(newBal)
                        + "."
                  )
               );
         } else {
            this.player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
            this.player.sendSystemMessage(Component.literal("§cNo sellable items in your inventory!"));
         }

         this.refreshNavBar();
         this.broadcastFullState();
      }

      public void removed(Player p) {
         this.sellDropArea(false);
         this.clearContainer(p, this.dropArea);
         super.removed(p);
      }

      private static ItemStack makeNavButton(String itemId, String title, List<String> loreLines) {
         Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + itemId)).orElse(null);
         if (item == null) {
            item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:gray_stained_glass_pane")).orElse(null);
         }

         if (item == null) {
            return ItemStack.EMPTY;
         } else {
            ItemStack stack = new ItemStack(item, 1);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(s -> s.withItalic(false)));
            if (!loreLines.isEmpty()) {
               List<Component> lore = new ArrayList<>();

               for (String line : loreLines) {
                  lore.add(Component.literal(line).withStyle(s -> s.withItalic(false)));
               }

               stack.set(DataComponents.LORE, new ItemLore(lore));
            }

            return stack;
         }
      }
   }
}
