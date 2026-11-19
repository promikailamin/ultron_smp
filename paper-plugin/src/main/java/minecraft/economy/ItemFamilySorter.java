package minecraft.economy;

import java.util.Comparator;
import java.util.Locale;

public final class ItemFamilySorter {
   public static final Comparator<EconomyData.ShopEntry> ENTRY_COMPARATOR = Comparator.comparing(entry -> rank(entry.id));
   public static final Comparator<String> ID_COMPARATOR = Comparator.comparing(ItemFamilySorter::rank);
   private static final String[] WOOD_TYPES = new String[]{
      "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped"
   };
   private static final String[] ORE_TIERS = new String[]{"coal", "copper", "iron", "gold", "redstone", "lapis", "diamond", "emerald", "netherite"};
   private static final String[] TOOL_TIERS = new String[]{"wooden", "stone", "iron", "golden", "diamond", "netherite"};
   private static final String[] ARMOR_TIERS = new String[]{"leather", "chainmail", "iron", "golden", "diamond", "netherite", "turtle"};
   private static final String[] COLORS = new String[]{
      "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
   };

   private static String rank(String rawId) {
      String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
      if (id.startsWith("minecraft:")) {
         id = id.substring("minecraft:".length());
      }

      if (isWood(id)) {
         return "01_wood_" + String.format("%03d", woodSubRank(id)) + "_" + id;
      } else {
         int oreTier = oreTierRank(id);
         if (oreTier >= 0) {
            return "02_ore_" + String.format("%02d", oreTier) + "_" + String.format("%03d", oreSubRank(id)) + "_" + id;
         } else {
            int toolTier = toolTierRank(id);
            if (toolTier >= 0) {
               return "03_tool_" + String.format("%02d", toolTier) + "_" + String.format("%03d", toolSubRank(id)) + "_" + id;
            } else {
               int armorTier = armorTierRank(id);
               if (armorTier >= 0) {
                  return "04_armor_" + String.format("%02d", armorTier) + "_" + String.format("%03d", armorSubRank(id)) + "_" + id;
               } else if (isStone(id)) {
                  return "05_stone_" + String.format("%03d", stoneSubRank(id)) + "_" + id;
               } else if (isFood(id)) {
                  return "06_food_" + String.format("%03d", foodSubRank(id)) + "_" + id;
               } else if (isRedstone(id)) {
                  return "07_redstone_" + String.format("%03d", redstoneSubRank(id)) + "_" + id;
               } else {
                  return isColored(id) ? "08_colored_" + String.format("%03d", colorSubRank(id)) + "_" + id : "99_misc_" + id;
               }
            }
         }
      }
   }

   private static boolean isWood(String id) {
      if (!id.endsWith("_log")
         && !id.endsWith("_stem")
         && !id.endsWith("_wood")
         && !id.endsWith("_hyphae")
         && !id.endsWith("_planks")
         && !id.endsWith("_sapling")
         && !id.endsWith("_leaves")
         && !id.equals("bamboo_block")
         && !id.equals("stripped_bamboo_block")) {
         for (String w : WOOD_TYPES) {
            if (id.contains(w)) {
               return true;
            }
         }

         return false;
      } else {
         return true;
      }
   }

   private static int woodSubRank(String id) {
      if (id.contains("stripped_") || !id.endsWith("_log") && !id.endsWith("_stem") && !id.equals("bamboo_block")) {
         if (!id.contains("stripped_") || !id.endsWith("_log") && !id.endsWith("_stem") && !id.contains("bamboo_block")) {
            if (id.contains("stripped_") || !id.endsWith("_wood") && !id.endsWith("_hyphae")) {
               if (!id.contains("stripped_") || !id.endsWith("_wood") && !id.endsWith("_hyphae")) {
                  if (id.endsWith("_planks") || id.endsWith("_mosaic")) {
                     return 50;
                  } else if (id.endsWith("_stairs")) {
                     return 60;
                  } else if (id.endsWith("_slab")) {
                     return 70;
                  } else if (id.endsWith("_fence")) {
                     return 80;
                  } else if (id.endsWith("_fence_gate")) {
                     return 90;
                  } else if (id.endsWith("_door") && !id.contains("trapdoor")) {
                     return 100;
                  } else if (id.endsWith("_trapdoor")) {
                     return 110;
                  } else if (id.endsWith("_pressure_plate")) {
                     return 120;
                  } else if (id.endsWith("_button")) {
                     return 130;
                  } else if (id.endsWith("_sign") && !id.contains("hanging")) {
                     return 140;
                  } else if (id.endsWith("_hanging_sign")) {
                     return 150;
                  } else if (id.endsWith("_boat") && !id.contains("chest")) {
                     return 160;
                  } else if (id.endsWith("_chest_boat")) {
                     return 170;
                  } else if (id.endsWith("_sapling") || id.endsWith("_propagule")) {
                     return 180;
                  } else {
                     return id.endsWith("_leaves") ? 190 : 900;
                  }
               } else {
                  return 40;
               }
            } else {
               return 30;
            }
         } else {
            return 20;
         }
      } else {
         return 10;
      }
   }

   private static int oreTierRank(String id) {
      for (int i = 0; i < ORE_TIERS.length; i++) {
         if (id.contains(ORE_TIERS[i])) {
            return i;
         }
      }

      return -1;
   }

   private static int oreSubRank(String id) {
      if (id.startsWith("raw_")) {
         return 1;
      } else if (id.contains("deepslate_") && id.endsWith("_ore")) {
         return 3;
      } else if (id.endsWith("_ore")) {
         return 2;
      } else if (id.endsWith("_ingot") || id.equals("coal") || id.equals("diamond") || id.equals("emerald")) {
         return 4;
      } else if (id.endsWith("_nugget") || id.endsWith("_scrap")) {
         return 5;
      } else {
         return !id.endsWith("_block") && !id.endsWith("_raw_block") ? 9 : 6;
      }
   }

   private static int toolTierRank(String id) {
      if (!id.equals("bow")
         && !id.equals("crossbow")
         && !id.equals("trident")
         && !id.equals("mace")
         && !id.equals("shield")
         && !id.equals("fishing_rod")
         && !id.equals("shears")
         && !id.equals("flint_and_steel")) {
         for (int i = 0; i < TOOL_TIERS.length; i++) {
            if (id.startsWith(TOOL_TIERS[i] + "_")
               && (id.endsWith("_sword") || id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe"))) {
               return i;
            }
         }

         return -1;
      } else {
         return 90;
      }
   }

   private static int toolSubRank(String id) {
      if (id.endsWith("_sword")) {
         return 1;
      } else if (id.endsWith("_pickaxe")) {
         return 2;
      } else if (id.endsWith("_axe")) {
         return 3;
      } else if (id.endsWith("_shovel")) {
         return 4;
      } else {
         return id.endsWith("_hoe") ? 5 : 9;
      }
   }

   private static int armorTierRank(String id) {
      for (int i = 0; i < ARMOR_TIERS.length; i++) {
         if ((id.startsWith(ARMOR_TIERS[i] + "_") || ARMOR_TIERS[i].equals("turtle") && id.contains("turtle"))
            && (id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots") || id.endsWith("_scute"))) {
            return i;
         }
      }

      return -1;
   }

   private static int armorSubRank(String id) {
      if (id.endsWith("_helmet")) {
         return 1;
      } else if (id.endsWith("_chestplate")) {
         return 2;
      } else if (id.endsWith("_leggings")) {
         return 3;
      } else {
         return id.endsWith("_boots") ? 4 : 9;
      }
   }

   private static boolean isStone(String id) {
      return id.contains("stone")
         || id.contains("granite")
         || id.contains("diorite")
         || id.contains("andesite")
         || id.contains("calcite")
         || id.contains("tuff")
         || id.contains("deepslate")
         || id.contains("blackstone")
         || id.contains("basalt")
         || id.contains("sandstone")
         || id.contains("prismarine")
         || id.contains("obsidian")
         || id.contains("purpur")
         || id.contains("quartz");
   }

   private static int stoneSubRank(String id) {
      if (!id.contains("cobble") && !id.contains("brick") && !id.contains("smooth") && !id.contains("slab") && !id.contains("stairs") && !id.contains("wall")) {
         return 1;
      } else if (id.contains("cobble")) {
         return 2;
      } else if (id.contains("brick")) {
         return 3;
      } else if (id.contains("smooth")) {
         return 4;
      } else if (id.endsWith("_slab")) {
         return 5;
      } else if (id.endsWith("_stairs")) {
         return 6;
      } else {
         return id.endsWith("_wall") ? 7 : 9;
      }
   }

   private static boolean isFood(String id) {
      return id.contains("apple")
         || id.contains("beef")
         || id.contains("pork")
         || id.contains("mutton")
         || id.contains("chicken")
         || id.contains("rabbit")
         || id.contains("fish")
         || id.contains("salmon")
         || id.contains("cod")
         || id.contains("bread")
         || id.contains("cookie")
         || id.contains("cake")
         || id.contains("pie")
         || id.contains("carrot")
         || id.contains("potato")
         || id.contains("beetroot")
         || id.contains("melon")
         || id.contains("berry")
         || id.contains("stew")
         || id.contains("soup");
   }

   private static int foodSubRank(String id) {
      if (id.contains("golden") || id.contains("enchanted")) {
         return 1;
      } else if (id.startsWith("cooked_")) {
         return 2;
      } else if (id.startsWith("raw_") || id.equals("beef") || id.equals("porkchop") || id.equals("chicken") || id.equals("mutton") || id.equals("rabbit")) {
         return 3;
      } else if (id.equals("bread") || id.contains("pie") || id.equals("cake") || id.equals("cookie")) {
         return 4;
      } else {
         return !id.contains("carrot") && !id.contains("potato") && !id.contains("beetroot") && !id.contains("melon") && !id.contains("berry") ? 9 : 5;
      }
   }

   private static boolean isRedstone(String id) {
      return id.contains("redstone")
         || id.contains("piston")
         || id.contains("observer")
         || id.contains("repeater")
         || id.contains("comparator")
         || id.contains("dispenser")
         || id.contains("dropper")
         || id.contains("hopper")
         || id.contains("target")
         || id.contains("sensor")
         || id.contains("lever")
         || id.contains("crafter");
   }

   private static int redstoneSubRank(String id) {
      if (id.contains("redstone")) {
         return 1;
      } else if (id.contains("repeater") || id.contains("comparator")) {
         return 2;
      } else if (id.contains("piston")) {
         return 3;
      } else {
         return !id.contains("dispenser") && !id.contains("dropper") && !id.contains("hopper") && !id.contains("crafter") ? 9 : 4;
      }
   }

   private static boolean isColored(String id) {
      for (String c : COLORS) {
         if (id.startsWith(c + "_")) {
            return id.contains("wool")
               || id.contains("concrete")
               || id.contains("terracotta")
               || id.contains("glass")
               || id.contains("candle")
               || id.contains("carpet")
               || id.contains("bed");
         }
      }

      return false;
   }

   private static int colorSubRank(String id) {
      if (id.contains("wool")) {
         return 1;
      } else if (id.contains("carpet")) {
         return 2;
      } else if (id.contains("concrete") && !id.contains("powder")) {
         return 3;
      } else if (id.contains("concrete_powder")) {
         return 4;
      } else if (id.contains("terracotta") && !id.contains("glazed")) {
         return 5;
      } else if (id.contains("glazed_terracotta")) {
         return 6;
      } else if (id.contains("stained_glass") && !id.contains("pane")) {
         return 7;
      } else {
         return id.contains("stained_glass_pane") ? 8 : 9;
      }
   }

   private ItemFamilySorter() {
   }
}
