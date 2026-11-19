package minecraft.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public final class ShopGui {
   private static final String[] CATEGORIES = new String[]{"blocks", "tools", "armor", "food", "ores", "redstone", "nether", "end", "misc", "rare"};
   private static final String[] CATEGORY_ICONS = new String[]{
      "bricks", "iron_pickaxe", "iron_chestplate", "golden_apple", "diamond_ore", "redstone", "nether_brick", "ender_eye", "compass", "dragon_egg"
   };

   public static void openMainMenu(ServerPlayer player) {
      long balance = EconomyData.getBalance(player.getUUID());
      EconomyGui.open(
         player,
         "§6Economy Shop",
         6,
         b -> {
            int[] catSlots = new int[]{0, 1, 2, 3, 5, 6, 7, 8, 9, 10};

            for (int i = 0; i < CATEGORIES.length; i++) {
               String cat = CATEGORIES[i];
               String iconId = CATEGORY_ICONS[i];
               ItemStack icon = makeIcon(iconId, "§e" + capitalize(cat), List.of("§7Browse §e" + cat + " §7items", "§aClick to browse"));
               int slot = catSlots[i];
               b.set(slot, icon, p -> openCategoryPage(p, cat, 1));
            }

            ItemStack allIcon = makeIcon("compass", "§6All Items", List.of("§7Browse all shop items", "§aClick to browse"));
            b.set(4, allIcon, p -> openCategoryPage(p, "all", 1));
            ItemStack ahIcon = makeIcon(
               "ender_eye", "§b§lAuction House", List.of("§7Browse player listings, rare", "§7trophies and gear boxes", "§aClick to open")
            );
            b.set(45, ahIcon, p -> {
               GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("main"));
               AuctionGui.openAuctionHouse(p, "", 1);
            });
            ItemStack buyIcon = makeIcon(
               "gold_ingot", "§a§lBuy Catalog", List.of("§7Browse formatted catalog", "§7grouped by item families", "§aClick to open")
            );
            b.set(46, buyIcon, p -> {
               GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("main"));
               BuyGui.open(p, "all", 1);
            });
            ItemStack sellChestIcon = makeIcon(
               "hopper", "§e§lSell Chest", List.of("§7Open empty chest drop area", "§7Closing chest sells placed items!", "§aClick to open")
            );
            b.set(47, sellChestIcon, p -> {
               GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("main"));
               SellGui.open(p);
            });
            b.balanceSlot(49, balance);
            ItemStack closeIcon = makeIcon("barrier", "§cClose", List.of("§7Close this menu"));
            b.set(53, closeIcon, p -> p.closeContainer());
            b.fill(ChatFormatting.GRAY);
         }
      );
   }

   public static void openCategoryPage(ServerPlayer player, String category, int page) {
      List<EconomyData.ShopEntry> entries = EconomyData.all()
         .stream()
         .filter(e -> category.equals("all") || e.category.equals(category))
         .sorted(Comparator.comparing(e -> e.name))
         .collect(Collectors.toList());
      int itemsPerPage = 45;
      int totalItems = entries.size();
      int maxPages = Math.max(1, (totalItems + itemsPerPage - 1) / itemsPerPage);
      int safePage = Math.max(1, Math.min(page, maxPages));
      int start = (safePage - 1) * itemsPerPage;
      int end = Math.min(start + itemsPerPage, totalItems);
      List<EconomyData.ShopEntry> pageEntries = entries.subList(start, end);
      String displayCat = category.equals("all") ? "All" : capitalize(category);
      String title = "§6Shop: §e" + displayCat + " §8(page " + safePage + "/" + maxPages + ")";
      long balance = EconomyData.getBalance(player.getUUID());
      EconomyGui.open(player, title, 6, b -> {
         for (int i = 0; i < pageEntries.size(); i++) {
            EconomyData.ShopEntry entry = pageEntries.get(i);
            Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + entry.id)).orElse(null);
            if (item != null) {
               ItemStack stack = new ItemStack(item, 1);
               List<Component> lore = new ArrayList<>();
               lore.add(Component.literal("§7Category: §e" + entry.category).withStyle(s -> s.withItalic(false)));
               if (entry.buyable) {
                  lore.add(Component.literal("§aBuy: §6" + EconomyData.formatMoney(entry.buy)).withStyle(s -> s.withItalic(false)));
               } else {
                  lore.add(Component.literal("§cNot buyable from shop").withStyle(s -> s.withItalic(false)));
               }

               if (entry.sell > 0L) {
                  lore.add(Component.literal("§eSell: §6" + EconomyData.formatMoney(entry.sell)).withStyle(s -> s.withItalic(false)));
               }

               if (entry.note != null && !entry.note.isEmpty()) {
                  lore.add(Component.literal("§7" + entry.note).withStyle(s -> s.withItalic(false)));
               }

               stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + entry.name).withStyle(s -> s.withItalic(false)));
               stack.set(DataComponents.LORE, new ItemLore(lore));
               if (entry.buyable) {
                  b.set(i, stack, p -> {
                     GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("category", category, safePage));
                     confirmBuy(p, entry.id, 1);
                  });
               } else {
                  b.set(i, stack, p -> p.sendSystemMessage(Component.literal("§c" + entry.name + " is not buyable from the shop.")));
               }
            }
         }

         b.back(45, p -> {
            GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
            if (prev != null) {
               GuiHistoryManager.restore(p, prev);
            } else {
               openMainMenu(p);
            }
         });
         if (safePage > 1) {
            b.prev(46, p -> openCategoryPage(p, category, safePage - 1));
         }

         ItemStack catalogIcon = makeIcon("gold_ingot", "§a§lBuy Catalog", List.of("§7View sorted catalog", "§7grouped by item families"));
         b.set(47, catalogIcon, p -> {
            GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("category", category, safePage));
            BuyGui.open(p, "all", 1);
         });
         b.balanceSlot(49, balance);
         ItemStack sellIcon = makeIcon("hopper", "§e§lSell Chest", List.of("§7Open empty chest drop area", "§7Closing chest sells placed items!"));
         b.set(50, sellIcon, SellGui::open);
         if (safePage < maxPages) {
            b.next(52, p -> openCategoryPage(p, category, safePage + 1));
         }

         ItemStack closeIcon = makeIcon("barrier", "§cClose", List.of("§7Close this menu"));
         b.set(53, closeIcon, p -> p.closeContainer());
         b.fillRow(5, ChatFormatting.GRAY);
      });
   }

   public static void confirmBuy(ServerPlayer player, String itemId, int amount) {
      EconomyData.ShopEntry entry = EconomyData.get(itemId);
      if (entry == null) {
         player.sendSystemMessage(Component.literal("§cItem not found in shop."));
      } else {
         Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + entry.id)).orElse(null);
         if (item == null) {
            player.sendSystemMessage(Component.literal("§cItem not found in registry."));
         } else {
            int safeAmount = Math.max(1, Math.min(64, amount));
            long cost = entry.buy * safeAmount;
            long balance = EconomyData.getBalance(player.getUUID());
            String title = "§6Buy: " + entry.name;
            EconomyGui.open(player, title, 3, b -> {
               ItemStack previewStack = new ItemStack(item, safeAmount);
               List<Component> lore = new ArrayList<>();
               lore.add(Component.literal("§7Total cost: §6" + EconomyData.formatMoney(cost)).withStyle(s -> s.withItalic(false)));
               lore.add(Component.literal("§7Price each: §6" + EconomyData.formatMoney(entry.buy)).withStyle(s -> s.withItalic(false)));
               previewStack.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + entry.name).withStyle(s -> s.withItalic(false)));
               previewStack.set(DataComponents.LORE, new ItemLore(lore));
               b.set(13, previewStack, null);
               ItemStack minus64 = makePlainGlass("lime", "§c-64", List.of("§7Reduce amount by 64"));
               b.set(9, minus64, p -> confirmBuy(p, itemId, Math.max(1, safeAmount - 64)));
               minus64 = makePlainGlass("lime", "§c-8", List.of("§7Reduce amount by 8"));
               b.set(10, minus64, p -> confirmBuy(p, itemId, Math.max(1, safeAmount - 8)));
               minus64 = makePlainGlass("lime", "§c-1", List.of("§7Reduce amount by 1"));
               b.set(11, minus64, p -> confirmBuy(p, itemId, safeAmount > 1 ? safeAmount - 1 : 1));
               minus64 = makePlainGlass("lime", "§a+1", List.of("§7Increase amount by 1"));
               b.set(15, minus64, p -> confirmBuy(p, itemId, Math.min(64, safeAmount + 1)));
               minus64 = makePlainGlass("lime", "§a+8", List.of("§7Increase amount by 8"));
               b.set(16, minus64, p -> confirmBuy(p, itemId, Math.min(64, safeAmount + 8)));
               minus64 = makePlainGlass("lime", "§a+64", List.of("§7Increase amount by 64"));
               b.set(17, minus64, p -> confirmBuy(p, itemId, Math.min(64, safeAmount + 64)));
               b.balanceSlot(2, balance);
               minus64 = makePlainGlass("lime", "§a✔ Buy " + safeAmount + "x for " + EconomyData.formatMoney(cost), List.of("§7Click to confirm purchase"));
               b.set(22, minus64, p -> {
                  long bal = EconomyData.getBalance(p.getUUID());
                  long totalCost = entry.buy * safeAmount;
                  if (bal < totalCost) {
                     p.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
                     p.sendSystemMessage(Component.literal("§cNot enough money! Need §6" + totalCost + "§c, have §6" + bal + "§c."));
                  } else if (p.getInventory().getFreeSlot() < 0) {
                     p.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
                     p.sendSystemMessage(Component.literal("§cYour inventory is full!"));
                  } else if (!EconomyData.take(p.getUUID(), totalCost)) {
                     p.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
                     p.sendSystemMessage(Component.literal("§cTransaction failed."));
                  } else {
                     p.getInventory().add(new ItemStack(item, safeAmount));
                     p.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                     p.sendSystemMessage(Component.literal("§aPurchased §f" + safeAmount + "x " + entry.name + "§a for §6" + totalCost + " §a."));
                     confirmBuy(p, entry.id, safeAmount);
                  }
               });
               minus64 = makePlainGlass("red", "§cCancel", List.of("§7Go back to shop"));
               b.set(24, minus64, p -> {
                  GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
                  if (prev != null) {
                     GuiHistoryManager.restore(p, prev);
                  } else {
                     openCategoryPage(p, entry.category, 1);
                  }
               });
               b.fill(ChatFormatting.GRAY);
            });
         }
      }
   }

   public static void openSellMenu(ServerPlayer player) {
      SellGui.open(player);
   }

   private static ItemStack makeIcon(String id, String name, List<String> loreLines) {
      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + id)).orElse(null);
      if (item == null) {
         item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:barrier")).orElse(null);
      }

      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));
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

   private static ItemStack makePlainGlass(String color, String name, List<String> loreLines) {
      return makeIcon(color + "_stained_glass_pane", name, loreLines);
   }

   private static String capitalize(String s) {
      return s != null && !s.isEmpty() ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
   }
}
