package minecraft.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public final class BuyGui {
   private static final int ITEMS_PER_PAGE = 36;
   public static final String[] TABS = new String[]{"all", "wood", "stone", "ores", "tools", "armor", "food", "redstone", "misc"};
   public static final String[] TAB_NAMES = new String[]{
      "All Items", "Wood & Logs", "Stone & Rocks", "Ores & Metals", "Tools & Weapons", "Armor Gear", "Food & Crops", "Redstone", "Misc Supplies"
   };
   public static final String[] TAB_ICONS = new String[]{
      "compass", "oak_log", "deepslate", "diamond", "netherite_sword", "netherite_chestplate", "golden_carrot", "repeater", "beacon"
   };

   public static void open(ServerPlayer player, String rawCategory, int requestedPage) {
      EconomyData.bindServer(player.level().getServer());
      String category = rawCategory != null && !rawCategory.isBlank() ? rawCategory.toLowerCase(Locale.ROOT) : "all";
      List<EconomyData.ShopEntry> list = new ArrayList<>();

      for (EconomyData.ShopEntry entry : EconomyData.all()) {
         if (entry.buyable && matchesCategory(entry, category)) {
            list.add(entry);
         }
      }

      list.sort(ItemFamilySorter.ENTRY_COMPARATOR);
      int totalItems = list.size();
      int maxPages = Math.max(1, (totalItems + 36 - 1) / 36);
      int page = Math.max(1, Math.min(requestedPage, maxPages));
      int startIndex = (page - 1) * 36;
      int endIndex = Math.min(startIndex + 36, totalItems);
      long balance = EconomyData.getBalance(player.getUUID());
      String title = "§6Buy Catalog §8| §e" + tabTitle(category) + " §8(" + page + "/" + maxPages + ")";
      EconomyGui.open(player, title, 6, b -> {
         for (int i = 0; i < TABS.length; i++) {
            String tab = TABS[i];
            boolean active = tab.equals(category);
            String tabName = (active ? "§a§l▶ " : "§e") + TAB_NAMES[i];
            List<String> lore = List.of(active ? "§aCurrently viewing this category" : "§7Click to browse " + TAB_NAMES[i]);
            ItemStack icon = makeTabIcon(TAB_ICONS[i], tabName, lore, active);
            b.set(i, icon, p -> open(p, tab, 1));
         }

         for (int i = startIndex; i < endIndex; i++) {
            EconomyData.ShopEntry entryx = list.get(i);
            int guiSlot = 9 + (i - startIndex);
            Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + entryx.id)).orElse(null);
            if (item != null) {
               ItemStack stack = new ItemStack(item, 1);
               stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + entryx.name).withStyle(s -> s.withItalic(false)));
               List<Component> lore = new ArrayList<>();
               lore.add(Component.literal("§7Category: §e" + entryx.category).withStyle(s -> s.withItalic(false)));
               lore.add(Component.literal("§aBuy Price: §6" + EconomyData.formatMoney(entryx.buy)).withStyle(s -> s.withItalic(false)));
               if (entryx.sellable()) {
                  lore.add(Component.literal("§eSell Price: §e" + EconomyData.formatMoney(entryx.sell)).withStyle(s -> s.withItalic(false)));
               }

               if (!entryx.note.isEmpty()) {
                  lore.add(Component.literal("§8" + entryx.note).withStyle(s -> s.withItalic(false)));
               }

               lore.add(Component.literal("§8-------------------------").withStyle(s -> s.withItalic(false)));
               lore.add(Component.literal("§a▶ Left-Click: §fBuy 1x").withStyle(s -> s.withItalic(false)));
               lore.add(Component.literal("§d▶ Right-Click: §fSelect Quantity").withStyle(s -> s.withItalic(false)));
               stack.set(DataComponents.LORE, new ItemLore(lore));
               b.set(guiSlot, stack, p -> {
                  GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("buy_catalog", category, page));
                  ShopGui.confirmBuy(p, entryx.id, 1);
               });
            }
         }

         for (int s = 9; s < 45; s++) {
            if (b.build().getItem(s).isEmpty()) {
               b.set(s, makeIcon("black_stained_glass_pane", " ", List.of()), null);
            }
         }

         b.set(45, makeIcon("orange_stained_glass_pane", "§6« Back to Shop Menu", List.of("§7Return to the previous screen")), p -> {
            GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
            if (prev != null) {
               GuiHistoryManager.restore(p, prev);
            } else {
               ShopGui.openMainMenu(p);
            }
         });
         b.set(46, makeIcon("gray_stained_glass_pane", " ", List.of()), null);
         b.set(47, makeIcon("hopper", "§e§lSell Items Bin", List.of("§7Open the chest drop area", "§7to sell items for")), p -> {
            GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("buy_catalog", category, page));
            SellGui.open(p);
         });
         if (page > 1) {
            b.set(48, makeIcon("yellow_stained_glass_pane", "§e« Page " + (page - 1), List.of("§7Previous page")), p -> open(p, category, page - 1));
         } else {
            b.set(48, makeIcon("gray_stained_glass_pane", "§8No Previous Page", List.of()), null);
         }

         ItemStack head = EconomyGui.playerHeadBalanceItem(player, balance);
         List<Component> headLore = new ArrayList<>();
         headLore.add(Component.literal("§7Page " + page + " of " + maxPages).withStyle(sx -> sx.withItalic(false)));
         ItemLore existingLore = (ItemLore)head.get(DataComponents.LORE);
         if (existingLore != null) {
            headLore.addAll(existingLore.lines());
         }

         head.set(DataComponents.LORE, new ItemLore(headLore));
         b.set(49, head, null);
         if (page < maxPages) {
            b.set(50, makeIcon("lime_stained_glass_pane", "§aPage " + (page + 1) + " »", List.of("§7Next page")), p -> open(p, category, page + 1));
         } else {
            b.set(50, makeIcon("gray_stained_glass_pane", "§8No Next Page", List.of()), null);
         }

         b.set(51, makeIcon("ender_eye", "§b§lAuction House", List.of("§7Browse player auctions,", "§7gear boxes and rare items")), p -> {
            GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("buy_catalog", category, page));
            AuctionGui.openAuctionHouse(p, "", 1);
         });
         b.set(52, makeIcon("gray_stained_glass_pane", " ", List.of()), null);
         b.set(53, makeIcon("red_stained_glass_pane", "§c§l✖ Close Menu", List.of("§7Exit the catalog")), ServerPlayer::closeContainer);
      });
   }

   public static void openSearch(ServerPlayer player, String query) {
      EconomyData.bindServer(player.level().getServer());
      String lower = query.toLowerCase(Locale.ROOT);
      List<EconomyData.ShopEntry> matches = EconomyData.all()
         .stream()
         .filter(e -> e.buyable)
         .filter(e -> e.id.contains(lower) || e.name.toLowerCase(Locale.ROOT).contains(lower))
         .sorted(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)))
         .collect(Collectors.toList());
      String title = "§6Buy Search: §e" + query + " §8(" + matches.size() + " results)";
      long balance = EconomyData.getBalance(player.getUUID());
      EconomyGui.open(player, title, 6, b -> {
         int slot = 0;

         for (EconomyData.ShopEntry entry : matches) {
            if (slot >= 45) {
               break;
            }

            Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + entry.id)).orElse(null);
            if (item != null) {
               ItemStack stack = new ItemStack(item, 1);
               stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + entry.name).withStyle(s -> s.withItalic(false)));
               List<Component> lore = new ArrayList<>();
               lore.add(Component.literal("§7Category: §e" + entry.category).withStyle(s -> s.withItalic(false)));
               lore.add(Component.literal("§aBuy: §6" + EconomyData.formatMoney(entry.buy)).withStyle(s -> s.withItalic(false)));
               if (entry.sellable()) {
                  lore.add(Component.literal("§eSell: §6" + EconomyData.formatMoney(entry.sell)).withStyle(s -> s.withItalic(false)));
               }

               stack.set(DataComponents.LORE, new ItemLore(lore));
               String fEntryId = entry.id;
               b.set(slot, stack, p -> {
                  GuiHistoryManager.get().push(p, new GuiHistoryManager.GuiState("buy_search", query, 0));
                  ShopGui.confirmBuy(p, fEntryId, 1);
               });
               slot++;
            }
         }

         for (int s = slot; s < 45; s++) {
            b.set(s, makeIcon("gray_stained_glass_pane", " ", List.of()), null);
         }

         b.back(45, p -> {
            GuiHistoryManager.GuiState prev = GuiHistoryManager.get().pop(p);
            if (prev != null) {
               GuiHistoryManager.restore(p, prev);
            } else {
               open(player, "all", 1);
            }
         });
         b.balanceSlot(49, balance);
         b.set(53, makeIcon("barrier", "§cClose", List.of("§7Close this menu")), p -> p.closeContainer());
         b.fillRow(5, ChatFormatting.GRAY);
      });
   }

   private static boolean matchesCategory(EconomyData.ShopEntry entry, String category) {
      if ("all".equals(category)) {
         return true;
      } else {
         String id = entry.id;
         String cat = entry.category;

         return switch (category) {
            case "wood" -> id.contains("oak")
               || id.contains("spruce")
               || id.contains("birch")
               || id.contains("jungle")
               || id.contains("acacia")
               || id.contains("mangrove")
               || id.contains("cherry")
               || id.contains("bamboo")
               || id.contains("crimson")
               || id.contains("warped")
               || id.contains("wood")
               || id.contains("log")
               || id.contains("planks");
            case "stone" -> id.contains("stone")
               || id.contains("granite")
               || id.contains("diorite")
               || id.contains("andesite")
               || id.contains("calcite")
               || id.contains("tuff")
               || id.contains("deepslate")
               || id.contains("sandstone")
               || id.contains("prismarine")
               || id.contains("blackstone")
               || id.contains("basalt")
               || id.contains("brick");
            case "ores" -> id.contains("coal")
               || id.contains("copper")
               || id.contains("iron")
               || id.contains("gold")
               || id.contains("lapis")
               || id.contains("diamond")
               || id.contains("emerald")
               || id.contains("netherite")
               || id.contains("ore")
               || id.contains("raw_");
            case "tools" -> id.endsWith("_sword")
               || id.endsWith("_pickaxe")
               || id.endsWith("_axe")
               || id.endsWith("_shovel")
               || id.endsWith("_hoe")
               || id.equals("bow")
               || id.equals("crossbow")
               || id.equals("shield")
               || id.equals("trident")
               || id.equals("mace");
            case "armor" -> id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots");
            case "food" -> id.contains("apple")
               || id.contains("bread")
               || id.contains("meat")
               || id.contains("beef")
               || id.contains("pork")
               || id.contains("chicken")
               || id.contains("fish")
               || id.contains("carrot")
               || id.contains("potato")
               || id.contains("berry")
               || id.contains("melon");
            case "redstone" -> id.contains("redstone")
               || id.contains("piston")
               || id.contains("observer")
               || id.contains("repeater")
               || id.contains("comparator")
               || id.contains("dispenser")
               || id.contains("dropper")
               || id.contains("hopper")
               || id.contains("target");
            case "misc" -> "misc".equals(cat) || "rare".equals(cat) || "end".equals(cat) || "nether".equals(cat);
            default -> cat.equalsIgnoreCase(category);
         };
      }
   }

   private static String tabTitle(String category) {
      for (int i = 0; i < TABS.length; i++) {
         if (TABS[i].equalsIgnoreCase(category)) {
            return TAB_NAMES[i];
         }
      }

      return "All Items";
   }

   private static ItemStack makeTabIcon(String itemId, String title, List<String> loreLines, boolean active) {
      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + itemId)).orElse(null);
      if (item == null) {
         item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:stone")).orElse(null);
      }

      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(s -> s.withItalic(false)));
         List<Component> lore = new ArrayList<>();
         if (active) {
            lore.add(Component.literal("§a§l[SELECTED CATEGORY]").withStyle(s -> s.withItalic(false)));
         }

         for (String line : loreLines) {
            lore.add(Component.literal(line).withStyle(s -> s.withItalic(false)));
         }

         stack.set(DataComponents.LORE, new ItemLore(lore));
         return stack;
      }
   }

   private static ItemStack makeIcon(String itemId, String title, List<String> loreLines) {
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

   private BuyGui() {
   }
}
