package minecraft.economy;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.item.component.ResolvableProfile;
import org.bukkit.craftbukkit.inventory.CraftInventoryCustom;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;

public final class EconomyGui {
   public static MenuType<?> menuType(int rows) {
      return switch (rows) {
         case 1 -> MenuType.GENERIC_9x1;
         case 2 -> MenuType.GENERIC_9x2;
         default -> MenuType.GENERIC_9x3;
         case 4 -> MenuType.GENERIC_9x4;
         case 5 -> MenuType.GENERIC_9x5;
         case 6 -> MenuType.GENERIC_9x6;
      };
   }

   public static void open(ServerPlayer player, String title, int rows, Consumer<EconomyGui.GuiBuilder> setup) {
      EconomyGui.GuiBuilderImpl b = new EconomyGui.GuiBuilderImpl(player, rows);
      setup.accept(b);
      SimpleContainer backing = b.build();
      Consumer<ServerPlayer>[] handlers = b.handlers;
      MenuProvider provider = new SimpleMenuProvider((id, inv, p) -> new EconomyGui.EcoMenu(id, inv, rows, backing, handlers), Component.literal(title));
      player.openMenu(provider);
      player.playSound(SoundEvents.CHEST_OPEN, 1.0F, 1.0F);
   }

   private static ItemStack glass(ChatFormatting color) {
      return namedGlass(color, " ");
   }

   private static ItemStack namedGlass(ChatFormatting color, String name) {
      String id = colorToGlassId(color);
      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + id)).orElse(null);
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false)));
         return stack;
      }
   }

   public static ItemStack playerHeadBalanceItem(ServerPlayer player, long balance) {
      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:player_head")).orElse(null);
      if (item == null) {
         item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:gold_nugget")).orElse(null);
      }

      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         if (player != null && player.getGameProfile() != null) {
            try {
               stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
            } catch (Throwable var8) {
            }
         }

         String playerName = player != null ? player.getName().getString() : "Player";
         if (balance < 0L) {
            long debt = -balance;
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§l" + playerName + "'s Head §8| §cIn Debt").withStyle(s -> s.withItalic(false)));
            stack.set(
               DataComponents.LORE,
               new ItemLore(
                  List.of(
                     Component.literal("§cBalance: §4-" + EconomyData.formatMoney(debt)).withStyle(s -> s.withItalic(false)),
                     Component.literal("§4Active Loan: §c" + EconomyData.formatMoney(debt)).withStyle(s -> s.withItalic(false)),
                     Component.literal("§8-------------------------").withStyle(s -> s.withItalic(false)),
                     Component.literal("§7Status: §cLoan Payment Pending").withStyle(s -> s.withItalic(false)),
                     Component.literal("§7Sales will automatically pay off debt.").withStyle(s -> s.withItalic(false))
                  )
               )
            );
         } else {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + playerName + "'s Head §8| §eProfile").withStyle(s -> s.withItalic(false)));
            stack.set(
               DataComponents.LORE,
               new ItemLore(
                  List.of(
                     Component.literal("§7Player: §f" + playerName).withStyle(s -> s.withItalic(false)),
                     Component.literal("§7Balance: §6" + EconomyData.formatMoney(balance)).withStyle(s -> s.withItalic(false)),
                     Component.literal("§8-------------------------").withStyle(s -> s.withItalic(false)),
                     Component.literal("§aAccount Active & Verified").withStyle(s -> s.withItalic(false))
                  )
               )
            );
         }

         return stack;
      }
   }

   private static String colorToGlassId(ChatFormatting color) {
      if (color == null) {
         return "gray_stained_glass_pane";
      } else {
         return switch (color) {
            case GRAY -> "gray_stained_glass_pane";
            case BLACK, DARK_GRAY -> "black_stained_glass_pane";
            case GREEN -> "lime_stained_glass_pane";
            case DARK_GREEN -> "green_stained_glass_pane";
            case RED, DARK_RED -> "red_stained_glass_pane";
            case YELLOW, GOLD -> "yellow_stained_glass_pane";
            case AQUA, DARK_AQUA -> "cyan_stained_glass_pane";
            case BLUE, DARK_BLUE -> "blue_stained_glass_pane";
            case LIGHT_PURPLE, DARK_PURPLE -> "purple_stained_glass_pane";
            case WHITE -> "white_stained_glass_pane";
            default -> "gray_stained_glass_pane";
         };
      }
   }

   public static final class EcoMenu extends AbstractContainerMenu {
      final int rows;
      final SimpleContainer backing;
      final Consumer<ServerPlayer>[] handlers;
      private final Inventory playerInv;
      private InventoryView bukkitView;

      public EcoMenu(int containerId, Inventory playerInv, int rows, SimpleContainer backing, Consumer<ServerPlayer>[] handlers) {
         super(EconomyGui.menuType(rows), containerId);
         this.rows = rows;
         this.backing = backing;
         this.handlers = handlers;
         this.playerInv = playerInv;

         for (int i = 0; i < rows * 9; i++) {
            int slotIndex = i;
            this.addSlot(new Slot(backing, slotIndex, 8 + slotIndex % 9 * 18, 18 + slotIndex / 9 * 18) {
               {
                  Objects.requireNonNull(EcoMenu.this);
               }

               public boolean mayPickup(Player player) {
                  return false;
               }

               public boolean mayPlace(ItemStack stack) {
                  return false;
               }
            });
         }

         int invTop = 18 + rows * 18 + 13;

         for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
               this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, invTop + row * 18));
            }
         }

         int hotbarTop = invTop + 58;

         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, hotbarTop));
         }
      }

      public ItemStack quickMoveStack(Player player, int index) {
         return ItemStack.EMPTY;
      }

      public boolean stillValid(Player player) {
         return true;
      }

      @Override
      public InventoryView getBukkitView() {
         if (this.bukkitView == null) {
            this.bukkitView = new CraftInventoryView(
               this.playerInv.player.getBukkitEntity(),
               new CraftInventoryCustom(null, InventoryType.CHEST, this.backing),
               this
            );
         }

         return this.bukkitView;
      }

      public void clicked(int slotId, int button, ContainerInput input, Player player) {
         if (slotId >= 0 && slotId < this.rows * 9) {
            Consumer<ServerPlayer> h = this.handlers[slotId];
            if (h != null) {
               h.accept((ServerPlayer)player);
            }

            this.broadcastFullState();
         } else {
            super.clicked(slotId, button, input, player);
         }
      }

      public void removed(Player player) {
         super.removed(player);
      }
   }

   public interface GuiBuilder {
      EconomyGui.GuiBuilder set(int var1, ItemStack var2, Consumer<ServerPlayer> var3);

      EconomyGui.GuiBuilder fill(ChatFormatting var1);

      EconomyGui.GuiBuilder fillRow(int var1, ChatFormatting var2);

      EconomyGui.GuiBuilder back(int var1, Consumer<ServerPlayer> var2);

      EconomyGui.GuiBuilder next(int var1, Consumer<ServerPlayer> var2);

      EconomyGui.GuiBuilder prev(int var1, Consumer<ServerPlayer> var2);

      EconomyGui.GuiBuilder balanceSlot(int var1, long var2);

      SimpleContainer build();
   }

   public static final class GuiBuilderImpl implements EconomyGui.GuiBuilder {
      final ServerPlayer player;
      final int rows;
      final SimpleContainer backing;
      final Consumer<ServerPlayer>[] handlers;

      public GuiBuilderImpl(ServerPlayer player, int rows) {
         this.player = player;
         this.rows = rows;
         this.backing = new SimpleContainer(rows * 9);
         this.handlers = new Consumer[rows * 9];
      }

      @Override
      public EconomyGui.GuiBuilder set(int slot, ItemStack icon, Consumer<ServerPlayer> onClick) {
         this.backing.setItem(slot, icon);
         this.handlers[slot] = onClick;
         return this;
      }

      @Override
      public EconomyGui.GuiBuilder fill(ChatFormatting color) {
         for (int slot = 0; slot < this.rows * 9; slot++) {
            if (this.backing.getItem(slot).isEmpty()) {
               this.backing.setItem(slot, EconomyGui.glass(color));
            }
         }

         return this;
      }

      @Override
      public EconomyGui.GuiBuilder fillRow(int row, ChatFormatting color) {
         int start = row * 9;
         int end = start + 9;

         for (int slot = start; slot < end; slot++) {
            if (this.backing.getItem(slot).isEmpty()) {
               this.backing.setItem(slot, EconomyGui.glass(color));
            }
         }

         return this;
      }

      @Override
      public EconomyGui.GuiBuilder back(int slot, Consumer<ServerPlayer> backFn) {
         return this.set(slot, EconomyGui.namedGlass(ChatFormatting.RED, "§c« Back"), backFn);
      }

      @Override
      public EconomyGui.GuiBuilder next(int slot, Consumer<ServerPlayer> nextFn) {
         return this.set(slot, EconomyGui.namedGlass(ChatFormatting.GREEN, "§a» Next Page"), nextFn);
      }

      @Override
      public EconomyGui.GuiBuilder prev(int slot, Consumer<ServerPlayer> prevFn) {
         return this.set(slot, EconomyGui.namedGlass(ChatFormatting.YELLOW, "§e« Prev Page"), prevFn);
      }

      @Override
      public EconomyGui.GuiBuilder balanceSlot(int slot, long balance) {
         return this.set(slot, EconomyGui.playerHeadBalanceItem(this.player, balance), null);
      }

      @Override
      public SimpleContainer build() {
         return this.backing;
      }
   }
}
