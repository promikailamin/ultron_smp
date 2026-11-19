package minecraft.economy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;

public final class AuctionGui {
   public static void openAuctionHouse(ServerPlayer player, String categoryFilter, int page) {
      String cat = categoryFilter == null ? "" : categoryFilter;
      List<Auctions.Lot> allLots = Auctions.listActiveFiltered(cat.isEmpty() ? null : cat, null);
      int perPage = 36;
      int maxPages = Math.max(1, (int)Math.ceil((double)allLots.size() / perPage));
      int safePage = Math.max(1, Math.min(page, maxPages));
      int from = (safePage - 1) * perPage;
      int to = Math.min(from + perPage, allLots.size());
      List<Auctions.Lot> pageLots = allLots.subList(from, to);
      String catLabel = cat.isEmpty() ? "All" : capitalize(cat);
      String title = "§5Auction House §8| §e" + catLabel;
      EconomyGui.open(
         player,
         title,
         6,
         b -> {
            String[] tabKinds = new String[]{"", "unique", "books", "gear", "discounts", "equip", "player", "super"};
            String[] tabLabels = new String[]{"§7All", "§6Unique", "§bBooks", "§aGear Boxes", "§eDeals", "§cEnchanted Gear", "§9Player Listings", "§d§lSUPER"};
            String[] tabItems = new String[]{"gray_stained_glass_pane", "dragon_egg", "book", "chest", "emerald", "diamond_sword", "paper", "nether_star"};

            for (int i = 0; i < tabKinds.length; i++) {
               String kind = tabKinds[i];
               String label = tabLabels[i];
               boolean active = cat.equalsIgnoreCase(kind);
               ItemStack tab;
               if (active) {
                  tab = namedGlass(ChatFormatting.GREEN, label);
               } else {
                  ItemStack candidate = makeItem(tabItems[i], label, List.of());
                  tab = candidate.isEmpty() ? namedGlass(ChatFormatting.GRAY, label) : candidate;
               }

               b.set(i, tab, p -> {
                  GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("auction", cat, safePage));
                  openAuctionHouse(p, kind, 1);
               });
            }

            ItemStack sellTab = makeItem(
               "gold_ingot", "§bSell Item", List.of(Component.literal("§7List your items for auction").withStyle(s -> s.withItalic(false)))
            );
            if (sellTab.isEmpty()) {
               sellTab = namedGlass(ChatFormatting.AQUA, "§bSell Item");
            }

            b.set(8, sellTab, p -> {
               GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("auction", cat, safePage));
               openSellListing(p);
            });

            for (int i = 0; i < pageLots.size(); i++) {
               Auctions.Lot lot = pageLots.get(i);
               int guiSlot = 9 + i;
               ItemStack icon = buildLotIcon(lot);
               long lotId = lot.id;
               b.set(guiSlot, icon, p -> {
                  GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("auction", cat, safePage));
                  openLotView(p, lotId);
               });
            }

            b.back(45, p -> {
               GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
               if (prev != null) {
                  GuiHistoryManager.restore(p, prev);
               } else {
                  ShopGui.openMainMenu(p);
               }
            });
            if (safePage > 1) {
               int prevPage = safePage - 1;
               b.prev(46, p -> openAuctionHouse(p, cat, prevPage));
            }

            b.set(
               47, makeItem("gold_ingot", "§a§lBuy Catalog", List.of(Component.literal("§7Browse shop catalog").withStyle(s -> s.withItalic(false)))), p -> {
                  GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("auction", cat, safePage));
                  BuyGui.open(p, "all", 1);
               }
            );
            b.set(48, makeItem("hopper", "§e§lSell Chest", List.of(Component.literal("§7Open empty sell chest").withStyle(s -> s.withItalic(false)))), p -> {
               GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("auction", cat, safePage));
               SellGui.open(p);
            });
            long balance = EconomyData.getBalance(player.getUUID());
            b.balanceSlot(49, balance);
            b.set(50, namedGlass(ChatFormatting.GRAY, "§7Page " + safePage + "/" + maxPages), null);
            if (safePage < maxPages) {
               int nextPage = safePage + 1;
               b.next(52, p -> openAuctionHouse(p, cat, nextPage));
            }

            b.set(
               53,
               makeItem("barrier", "§cClose", List.of(Component.literal("§7Exit auction house").withStyle(s -> s.withItalic(false)))),
               ServerPlayer::closeContainer
            );
            b.fill(ChatFormatting.GRAY);
         }
      );
   }

   public static void openLotView(ServerPlayer player, long lotId) {
      Auctions.Lot lot = Auctions.get(lotId);
      if (lot != null && lot.active()) {
         EconomyGui.open(player, "§5Lot #" + lotId, 3, b -> {
            ItemStack display = lot.stack.copy();
            display.setCount(1);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§6Price: §e" + EconomyData.formatMoney(lot.price)).withStyle(s -> s.withItalic(false)));
            lore.add(Component.literal("§7Seller: §b" + lot.sellerName).withStyle(s -> s.withItalic(false)));
            lore.add(Component.literal("§7Category: §e" + lot.category()).withStyle(s -> s.withItalic(false)));
            lore.add(Component.literal("§7Stock: §f" + lot.stock + "/" + lot.maxStock).withStyle(s -> s.withItalic(false)));
            lore.add(Component.literal("§7" + lot.priceTrend()).withStyle(s -> s.withItalic(false)));
            if ("GEAR_BOX".equals(lot.kind)) {
               lore.add(Component.literal("§dContains 27 items of gear!").withStyle(s -> s.withItalic(false)));
            }

            display.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + lot.name()).withStyle(s -> s.withItalic(false)));
            display.set(DataComponents.LORE, new ItemLore(lore));
            b.set(13, display, null);
            if ("GEAR_BOX".equals(lot.kind)) {
               ItemContainerContents contents = (ItemContainerContents)lot.stack.get(DataComponents.CONTAINER);
               if (contents != null) {
                  List<ItemStack> items = contents.nonEmptyItemCopyStream().limit(2L).toList();
                  if (items.size() > 0) {
                     b.set(10, items.get(0).copy(), null);
                  }

                  if (items.size() > 1) {
                     b.set(11, items.get(1).copy(), null);
                  }
               }
            }

            long balance = EconomyData.getBalance(player.getUUID());
            b.balanceSlot(2, balance);
            b.set(20, namedGlass(ChatFormatting.GREEN, "§a✔ Buy 1 for " + EconomyData.formatMoney(lot.price)), p -> doBuy(p, lotId, 1));
            if (lot.stock > 1) {
               int buyAmt = Math.min(10, lot.stock);
               long totalPrice = lot.price * buyAmt;
               b.set(21, namedGlass(ChatFormatting.GREEN, "§a✔ Buy " + buyAmt + " for " + EconomyData.formatMoney(totalPrice)), p -> doBuy(p, lotId, buyAmt));
            }

            b.back(24, p -> {
               GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
               if (prev != null) {
                  GuiHistoryManager.restore(p, prev);
               } else {
                  openAuctionHouse(p, "", 1);
               }
            });
            b.fill(ChatFormatting.GRAY);
         });
      } else {
         player.sendSystemMessage(Component.literal("§cThis lot is no longer available."));
         openAuctionHouse(player, "", 1);
      }
   }

   private static void doBuy(ServerPlayer player, long lotId, int amount) {
      MinecraftServer server = player.level().getServer();
      Auctions.Lot lot = Auctions.get(lotId);
      if (lot != null && lot.active() && lot.stock > 0) {
         int qty = Math.min(amount, lot.stock);
         long totalCost = lot.price * qty;
         if (!EconomyData.take(player.getUUID(), totalCost)) {
            player.sendSystemMessage(Component.literal("§cNot enough money! Need §e" + totalCost + " §ccoins."));
            player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
            player.closeContainer();
         } else {
            if (lot.engine) {
               Auctions.consume(lot, qty);
               AuctionEngine.onPurchased(lot);

               for (int i = 0; i < qty; i++) {
                  ItemStack copy = lot.stack.copy();
                  copy.setCount(1);
                  player.getInventory().add(copy);
               }
            } else {
               Auctions.remove(lotId);
               EconomyData.add(lot.seller, lot.price);
               if (server != null) {
                  ServerPlayer seller = server.getPlayerList().getPlayer(lot.seller);
                  if (seller != null) {
                     seller.sendSystemMessage(
                        Component.literal(
                           "§a[Auction] §f" + player.getName().getString() + " §abought your §f" + lot.name() + " §afor §e" + lot.price + " §acoins!"
                        )
                     );
                  }
               }

               ItemStack copy = lot.stack.copy();
               copy.setCount(qty);
               player.getInventory().add(copy);
            }

            player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
            player.sendSystemMessage(Component.literal("§aPurchased §f" + qty + "x " + lot.name() + " §afor §e" + totalCost + " §acoins!"));
            openLotView(player, lotId);
         }
      } else {
         player.sendSystemMessage(Component.literal("§cThis lot is no longer available or out of stock!"));
         player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
         player.closeContainer();
      }
   }

   public static void openSellListing(ServerPlayer player) {
      EconomyGui.open(
         player,
         "§5List Item for Auction",
         6,
         b -> {
            for (int invSlot = 0; invSlot < 36; invSlot++) {
               ItemStack item = player.getInventory().getItem(invSlot);
               if (!item.isEmpty()) {
                  ItemStack display = item.copy();
                  List<Component> lore = new ArrayList<>();
                  lore.add(Component.literal("§7Click to list this item for auction").withStyle(s -> s.withItalic(false)));
                  lore.add(Component.literal("§eHold an amount to sell 1").withStyle(s -> s.withItalic(false)));
                  display.set(DataComponents.LORE, new ItemLore(lore));
                  int slot = invSlot;
                  b.set(invSlot, display, p -> openPriceInput(p, slot));
               }
            }

            b.back(45, p -> {
               GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
               if (prev != null) {
                  GuiHistoryManager.restore(p, prev);
               } else {
                  openAuctionHouse(p, "", 1);
               }
            });
            long balance = EconomyData.getBalance(player.getUUID());
            b.balanceSlot(49, balance);
            b.set(
               53,
               makeItem("barrier", "§cClose", List.of(Component.literal("§7Exit auction house").withStyle(s -> s.withItalic(false)))),
               ServerPlayer::closeContainer
            );
            b.fill(ChatFormatting.GRAY);
         }
      );
   }

   public static void openPriceInput(ServerPlayer player, int invSlot) {
      ItemStack heldItem = player.getInventory().getItem(invSlot);
      if (heldItem.isEmpty()) {
         player.sendSystemMessage(Component.literal("§cThat slot is empty!"));
         openSellListing(player);
      } else {
         EconomyGui.open(player, "§5Set Price to List", 3, b -> {
            b.set(13, heldItem.copy(), null);
            b.set(4, namedGlass(ChatFormatting.GRAY, "§7Listing fee: FREE"), null);
            long[] prices = new long[]{100L, 500L, 1000L, 5000L, 10000L, 50000L};
            int[] pSlots = new int[]{10, 11, 12, 14, 15, 16};

            for (int i = 0; i < prices.length; i++) {
               long price = prices[i];
               b.set(pSlots[i], namedGlass(ChatFormatting.YELLOW, "§e" + EconomyData.formatMoney(price)), p -> listItem(p, invSlot, price));
            }

            b.set(22, namedGlass(ChatFormatting.RED, "§c« Cancel"), p -> openSellListing(p));
            b.fill(ChatFormatting.GRAY);
         });
      }
   }

   private static void listItem(ServerPlayer player, int invSlot, long price) {
      ItemStack item = player.getInventory().getItem(invSlot);
      if (item.isEmpty()) {
         player.sendSystemMessage(Component.literal("§cThat slot is empty!"));
         openSellListing(player);
      } else {
         ItemStack toList = item.copy();
         toList.setCount(1);
         item.shrink(1);
         if (item.isEmpty()) {
            player.getInventory().setItem(invSlot, ItemStack.EMPTY);
         }

         MinecraftServer server = player.level().getServer();
         Auctions.addPlayer(player.getUUID(), player.getName().getString(), price, toList, server);
         player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
         String itemName = toList.getHoverName().getString();
         player.sendSystemMessage(Component.literal("§aListed 1x " + itemName + " for §e" + price + " §acoins on the auction house!"));
         openSellListing(player);
      }
   }

   private static ItemStack buildLotIcon(Auctions.Lot lot) {
      ItemStack base = lot.stack.copy();
      base.setCount(1);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7#" + lot.id + " | " + lot.kind).withStyle(s -> s.withItalic(false)));
      StringBuilder priceStr = new StringBuilder("§6Price: §e").append(lot.price).append("");
      if (lot.stock > 1) {
         priceStr.append("  §7Stock: ").append(lot.stock).append("/").append(lot.maxStock);
      }

      lore.add(Component.literal(priceStr.toString()).withStyle(s -> s.withItalic(false)));
      lore.add(Component.literal("§7Seller: §b" + lot.sellerName).withStyle(s -> s.withItalic(false)));
      lore.add(Component.literal("§7" + lot.priceTrend()).withStyle(s -> s.withItalic(false)));
      if (lot.engine && lot.expiresAt > 0L) {
         long remaining = lot.expiresAt - System.currentTimeMillis();
         if (remaining > 0L) {
            lore.add(Component.literal("§8Expires: §7" + timeLeft(remaining)).withStyle(s -> s.withItalic(false)));
         }
      }

      lore.add(Component.literal("§aLeft-click to view & buy").withStyle(s -> s.withItalic(false)));
      base.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + lot.name()).withStyle(s -> s.withItalic(false)));
      base.set(DataComponents.LORE, new ItemLore(lore));
      return base;
   }

   private static ItemStack namedGlass(ChatFormatting color, String name) {
      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + colorToGlassId(color))).orElse(null);
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));
         return stack;
      }
   }

   private static String colorToGlassId(ChatFormatting color) {
      if (color == null) {
         return "gray_stained_glass_pane";
      } else {
         return switch (color) {
            case GRAY -> "gray_stained_glass_pane";
            case DARK_GRAY -> "black_stained_glass_pane";
            case BLACK -> "black_stained_glass_pane";
            case GREEN -> "lime_stained_glass_pane";
            case DARK_GREEN -> "green_stained_glass_pane";
            case RED -> "red_stained_glass_pane";
            case DARK_RED -> "red_stained_glass_pane";
            case YELLOW -> "yellow_stained_glass_pane";
            case GOLD -> "yellow_stained_glass_pane";
            case AQUA -> "cyan_stained_glass_pane";
            case DARK_AQUA -> "cyan_stained_glass_pane";
            case BLUE -> "blue_stained_glass_pane";
            case DARK_BLUE -> "blue_stained_glass_pane";
            case LIGHT_PURPLE -> "purple_stained_glass_pane";
            case DARK_PURPLE -> "purple_stained_glass_pane";
            case WHITE -> "white_stained_glass_pane";
            default -> "gray_stained_glass_pane";
         };
      }
   }

   private static ItemStack makeItem(String id, String name, List<Component> loreLines) {
      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + id)).orElse(null);
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));
         if (!loreLines.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(loreLines));
         }

         return stack;
      }
   }

   private static String timeLeft(long millis) {
      long seconds = millis / 1000L;
      if (seconds < 60L) {
         return seconds + "s";
      } else {
         return seconds < 3600L ? seconds / 60L + "m" : seconds / 3600L + "h";
      }
   }

   private static String capitalize(String s) {
      return s != null && !s.isEmpty() ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
   }
}
