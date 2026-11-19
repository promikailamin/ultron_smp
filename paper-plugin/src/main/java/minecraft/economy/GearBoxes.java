package minecraft.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments.Mutable;

public final class GearBoxes {
   public static final int TOTAL_BOXES = 50;
   private static final List<GearBoxes.Entry> CATALOG = new ArrayList<>(50);

   private static void register(
      int slot,
      String id,
      String name,
      String color,
      ChatFormatting titleColor,
      String subtitle,
      List<String> highlights,
      long basePrice,
      GearBoxes.BoxPopulator populator
   ) {
      CATALOG.add(new GearBoxes.Entry(new GearBoxes.GearBoxSpec(slot, id, name, color, titleColor, subtitle, highlights, basePrice), populator));
   }

   public static GearBoxes.GearBoxSpec getSpec(int slot) {
      int index = Math.floorMod(slot, CATALOG.size());
      return CATALOG.get(index).spec();
   }

   public static ItemStack createBox(int slot, Random random, MinecraftServer server) {
      int index = Math.floorMod(slot, CATALOG.size());
      GearBoxes.Entry entry = CATALOG.get(index);
      GearBoxes.GearBoxSpec spec = entry.spec();
      Item boxItem = item(spec.color() + "_shulker_box");
      if (boxItem == null) {
         boxItem = item("shulker_box");
      }

      if (boxItem == null) {
         return ItemStack.EMPTY;
      } else {
         List<ItemStack> contents = entry.populator().populate(server, random);

         while (contents.size() < 27) {
            contents.add(itemStack("diamond", 1 + random.nextInt(4)));
         }

         while (contents.size() > 27) {
            contents.remove(contents.size() - 1);
         }

         ItemStack box = new ItemStack(boxItem, 1);
         box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
         Component title = Component.literal(spec.name()).withStyle(new ChatFormatting[]{spec.titleColor(), ChatFormatting.BOLD});
         box.set(DataComponents.CUSTOM_NAME, title);
         List<Component> loreLines = new ArrayList<>();
         loreLines.add(Component.literal("§7" + spec.subtitle()));
         loreLines.add(Component.literal("§8-----------------------------"));
         loreLines.add(Component.literal("§6Key Contents:"));

         for (String highlight : spec.highlights()) {
            loreLines.add(Component.literal("§e • " + highlight));
         }

         loreLines.add(Component.literal("§8-----------------------------"));
         loreLines.add(Component.literal("§dUse /ah view to inspect all 27 slots"));
         box.set(DataComponents.LORE, new ItemLore(loreLines));
         return box;
      }
   }

   private static ItemStack gear(MinecraftServer server, String id, Object... enchantsAndLevels) {
      Item item = item(id);
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);

         for (int i = 0; i < enchantsAndLevels.length; i += 2) {
            String encId = (String)enchantsAndLevels[i];
            int lvl = (Integer)enchantsAndLevels[i + 1];
            Holder<Enchantment> holder = enchantment(server, encId);
            if (holder != null) {
               stack.enchant(holder, lvl);
            }
         }

         return stack;
      }
   }

   private static ItemStack book(MinecraftServer server, String enchantId, int level) {
      Item item = item("enchanted_book");
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         Holder<Enchantment> holder = enchantment(server, enchantId);
         if (holder != null) {
            Mutable mutable = new Mutable(ItemEnchantments.EMPTY);
            mutable.set(holder, level);
            stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
         }

         return stack;
      }
   }

   private static ItemStack potion(MinecraftServer server, String potionItemId, Holder<Potion> potion, int count) {
      Item item = item(potionItemId);
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = PotionContents.createItemStack(item, potion);
         stack.setCount(count);
         return stack;
      }
   }

   private static ItemStack fireworks(int count, int flightDuration) {
      Item item = item("firework_rocket");
      if (item == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = new ItemStack(item, count);
         stack.set(DataComponents.FIREWORKS, new Fireworks(flightDuration, List.of()));
         return stack;
      }
   }

   private static ItemStack itemStack(String id, int count) {
      Item item = item(id);
      return item == null ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, count));
   }

   private static Holder<Enchantment> enchantment(MinecraftServer server, String id) {
      if (server == null) {
         return null;
      } else {
         try {
            ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("minecraft:" + id));
            return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
         } catch (RuntimeException var3) {
            return null;
         }
      }
   }

   private static Item item(String id) {
      String full = id.contains(":") ? id : "minecraft:" + id;
      return (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse(full)).orElse(null);
   }

   static {
      register(
         0,
         "warlord_netherite",
         "Warlord's Netherite Arsenal",
         "black",
         ChatFormatting.GOLD,
         "Full God Netherite Armor & Weapons, Totems, God Apples",
         List.of(
            "Full Netherite Armor (Prot IV, Unbreaking III, Mending)",
            "Netherite Sword (Sharp V, Fire II, Loot III, Sweep III)",
            "Netherite Axe (Sharp V, Eff V), 3x Totem of Undying",
            "2x God Apples, 16x Golden Apples, Strength II Potions"
         ),
         65000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1, "respiration", 3, "aqua_affinity", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "depth_strider", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "fire_aspect", 2, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("obsidian", 16));
            list.add(itemStack("end_crystal", 8));
            list.add(book(server, "mending", 1));
            list.add(book(server, "protection", 4));
            list.add(itemStack("golden_carrot", 32));
            list.add(itemStack("diamond_block", 2));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("arrow", 64));
            return list;
         }
      );
      register(
         1,
         "master_miner",
         "Master Miner's Treasury",
         "gray",
         ChatFormatting.DARK_GRAY,
         "Dual God Netherite Pickaxes, Ancient Debris & Diamonds",
         List.of(
            "World Carver Netherite Pickaxe (Fortune III, Eff V, Mend)",
            "Silk Carver Netherite Pickaxe (Silk Touch, Eff V, Mend)",
            "Netherite Shovel & Hoe, 2x Totem of Undying",
            "4x Ancient Debris, 8x Netherite Scrap, 32x Diamonds"
         ),
         50000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_hoe", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("ancient_debris", 4));
            list.add(itemStack("netherite_scrap", 8));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("diamond", 32));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("ender_chest", 8));
            list.add(itemStack("torch", 64));
            list.add(itemStack("torch", 64));
            list.add(itemStack("iron_block", 16));
            list.add(itemStack("gold_block", 16));
            list.add(itemStack("redstone_block", 32));
            list.add(itemStack("lapis_block", 32));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("cooked_porkchop", 64));
            list.add(itemStack("golden_carrot", 32));
            list.add(book(server, "efficiency", 5));
            list.add(book(server, "fortune", 3));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         2,
         "void_conqueror",
         "Void Conqueror / End Raider",
         "purple",
         ChatFormatting.DARK_PURPLE,
         "Enchanted Elytra, Netherite Armor, Flight Stash & Shulkers",
         List.of(
            "Enchanted Elytra (Unbreaking III, Mending)",
            "Netherite Armor, Maxed Sniper Bow, 3x Totem",
            "64x Flight 3 Rockets, 32x Ender Pearls, 8x Shulker Shells",
            "4x God Apples, 16x Golden Apples, Slow Falling Potions"
         ),
         55000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "elytra", "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "bow", "power", 5, "flame", 1, "punch", 2, "infinity", 1, "unbreaking", 3));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(fireworks(64, 3));
            list.add(fireworks(64, 3));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("chorus_fruit", 64));
            list.add(itemStack("shulker_shell", 8));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("diamond", 16));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("ender_chest", 4));
            list.add(book(server, "mending", 1));
            return list;
         }
      );
      register(
         3,
         "warden_hunter",
         "Deep Dark Warden Hunter",
         "cyan",
         ChatFormatting.DARK_AQUA,
         "Sculk Cleaver, Swift Sneak III, Wool Stash & Echo Shards",
         List.of(
            "Netherite Leggings (Swift Sneak III, Prot IV, Mend)",
            "Netherite Armor & Sculk Cleaver Sword (Smite V, Loot III)",
            "4x Totem of Undying, 8x Echo Shards, Recovery Compass",
            "Wool stacks, Night Vision Potions, 4x God Apples"
         ),
         52000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "respiration", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "swift_sneak", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "soul_speed", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "smite", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_hoe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "piercing", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("gray_wool", 64));
            list.add(itemStack("cyan_wool", 64));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_INVISIBILITY, 1));
            list.add(itemStack("echo_shard", 8));
            list.add(itemStack("recovery_compass", 1));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(book(server, "swift_sneak", 3));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("golden_carrot", 32));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         4,
         "ocean_leviathan",
         "Ocean Leviathan / Sea Master",
         "blue",
         ChatFormatting.BLUE,
         "Dual God Tridents, Turtle Helmet, Conduit & Heart of the Sea",
         List.of(
            "Channeling & Loyalty III God Trident 'Stormbringer'",
            "Riptide III God Trident 'Tsunami', Turtle Helmet",
            "Conduit, Heart of the Sea, 3x Totem of Undying",
            "Water Breathing Potions, Sea Lanterns, God Apples"
         ),
         48000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "turtle_helmet", "protection", 4, "respiration", 3, "aqua_affinity", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "depth_strider", 3, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "trident", "channeling", 1, "loyalty", 3, "impaling", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "trident", "riptide", 3, "impaling", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "impaling", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("conduit", 1));
            list.add(itemStack("heart_of_the_sea", 1));
            list.add(itemStack("prismarine_shard", 32));
            list.add(itemStack("prismarine_crystals", 32));
            list.add(itemStack("sea_lantern", 64));
            list.add(itemStack("dark_prismarine", 32));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(potion(server, "potion", Potions.LONG_WATER_BREATHING, 1));
            list.add(potion(server, "potion", Potions.LONG_WATER_BREATHING, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(itemStack("cooked_salmon", 64));
            list.add(book(server, "impaling", 5));
            list.add(book(server, "riptide", 3));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("sponge", 8));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         5,
         "dragon_slayer",
         "Dragon Slayer's Imperial Vault",
         "magenta",
         ChatFormatting.LIGHT_PURPLE,
         "Imperial Netherite Armor, Dragon Bane Bow, Crystals & Apples",
         List.of(
            "Power V Flame Dragon's Bane Bow, God Netherite Armor",
            "Netherite Sharpness V Sword, 4x Totem of Undying",
            "6x God Apples, Slow Falling & Strength II Potions",
            "4x End Crystals, 16x Obsidian, 32x Ender Pearls"
         ),
         58000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "bow", "power", 5, "flame", 1, "punch", 2, "infinity", 1, "unbreaking", 3));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "fire_aspect", 2, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 6));
            list.add(itemStack("golden_apple", 24));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(itemStack("end_crystal", 4));
            list.add(itemStack("obsidian", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("golden_carrot", 32));
            list.add(book(server, "power", 5));
            list.add(book(server, "infinity", 1));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         6,
         "wither_annihilator",
         "Wither Annihilator Kit",
         "brown",
         ChatFormatting.GOLD,
         "Smite V Netherite God Weapons, 3x Wither Skulls & Milk",
         List.of(
            "Smite V Looting III Netherite Sword & Smite V Axe",
            "Blast Protection IV & Protection IV Netherite Armor",
            "3x Wither Skeleton Skulls, 4x Soul Sand, 4x Totems",
            "6x God Apples, Strength II, Splash Health II"
         ),
         60000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "smite", 5, "looting", 3, "fire_aspect", 2, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "smite", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "bow", "power", 5, "infinity", 1, "unbreaking", 3));
            list.add(itemStack("wither_skeleton_skull", 3));
            list.add(itemStack("soul_sand", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 6));
            list.add(itemStack("golden_apple", 16));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("honey_bottle", 4));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("golden_carrot", 32));
            list.add(itemStack("cooked_beef", 64));
            list.add(book(server, "smite", 5));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("arrow", 64));
            return list;
         }
      );
      register(
         7,
         "mace_master",
         "Trial Chambers Mace Master",
         "orange",
         ChatFormatting.GOLD,
         "Thunderclap Mace, Wind Charges, Ominous Keys & Bottles",
         List.of(
            "Mace 'Thunderclap' (Wind Burst III, Density V, Breach IV)",
            "Netherite Armor, Multishot Crossbow, 4x Totems",
            "48x Wind Charges, 4x Ominous Bottles, 8x Trial Keys",
            "4x Ominous Trial Keys, 4x God Apples, Golden Apples"
         ),
         62000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "mace", "wind_burst", 3, "density", 5, "breach", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "wind_burst", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "multishot", 1, "unbreaking", 3, "mending", 1));
            list.add(itemStack("wind_charge", 64));
            list.add(itemStack("wind_charge", 64));
            list.add(itemStack("ominous_bottle", 4));
            list.add(itemStack("trial_key", 8));
            list.add(itemStack("ominous_trial_key", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("golden_carrot", 32));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(book(server, "wind_burst", 3));
            list.add(book(server, "density", 5));
            list.add(itemStack("heavy_core", 1));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         8,
         "fortress_infiltrator",
         "Fortress Infiltrator / Blaze Bane",
         "red",
         ChatFormatting.DARK_RED,
         "Fire Prot IV Netherite, Blaze Rods, Nether Wart & Tears",
         List.of(
            "Netherite Armor (Fire Prot IV, Unbreaking III, Mending)",
            "Netherite Sharpness V Sword & Fortune III Pickaxe",
            "32x Blaze Rods, 32x Nether Wart, 16x Ghast Tears",
            "4x Totem of Undying, Fire Res Potions, God Apples"
         ),
         46000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "fire_protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(itemStack("blaze_rod", 32));
            list.add(itemStack("nether_wart", 32));
            list.add(itemStack("magma_cream", 16));
            list.add(itemStack("ghast_tear", 16));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("cooked_porkchop", 64));
            list.add(itemStack("netherite_scrap", 4));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("diamond_block", 2));
            list.add(book(server, "looting", 3));
            list.add(book(server, "fire_aspect", 2));
            list.add(itemStack("golden_carrot", 32));
            return list;
         }
      );
      register(
         9,
         "village_hero",
         "Village Hero & Raid Defender",
         "lime",
         ChatFormatting.GREEN,
         "Raid Breaker Crossbow, Ominous Bottles & 64x Emerald Blocks",
         List.of(
            "Crossbow 'Raid Breaker' (Piercing IV, Quick Charge III)",
            "Netherite Armor & Sword, 4x Totem of Undying",
            "64x Emerald Blocks, 16x Ominous Bottles, 4x God Apples",
            "Village Bell, Lead, Golden Carrots & Enchants"
         ),
         49000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "multishot", 1, "piercing", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("emerald_block", 64));
            list.add(itemStack("emerald_block", 32));
            list.add(itemStack("ominous_bottle", 16));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("bell", 1));
            list.add(itemStack("lead", 4));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(book(server, "piercing", 4));
            list.add(book(server, "quick_charge", 3));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("saddle", 2));
            return list;
         }
      );
      register(
         10,
         "immortal_juggernaut",
         "Immortal Juggernaut's Reliquary",
         "yellow",
         ChatFormatting.YELLOW,
         "Massive Cache of 12 Totems of Undying & 8 God Apples",
         List.of(
            "12x Totem of Undying (True Immortality)",
            "Full Netherite Armor Set (Prot IV, Mend)",
            "8x Enchanted God Apples, 32x Golden Apples",
            "Splash Health II & Regeneration II Potions"
         ),
         78000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();

            for (int i = 0; i < 12; i++) {
               list.add(itemStack("totem_of_undying", 1));
            }

            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("enchanted_golden_apple", 8));
            list.add(itemStack("golden_apple", 32));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("diamond_block", 4));
            list.add(book(server, "mending", 1));
            return list;
         }
      );
      register(
         11,
         "grand_enchanter",
         "Grand Enchanter's Archive",
         "light_blue",
         ChatFormatting.AQUA,
         "12 Top Enchanted Books (Mending, Wind Burst, Swift Sneak)",
         List.of(
            "12x Top Enchanted Books (Mending, Fortune III, Silk Touch...)",
            "Wind Burst III, Swift Sneak III, Sharpness V, Prot IV",
            "64x Lapis Blocks, Bookshelves, Anvil, Enchanting Table",
            "32x Diamonds, 4x Netherite Ingots, 2x Totem of Undying"
         ),
         70000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(book(server, "mending", 1));
            list.add(book(server, "wind_burst", 3));
            list.add(book(server, "swift_sneak", 3));
            list.add(book(server, "fortune", 3));
            list.add(book(server, "silk_touch", 1));
            list.add(book(server, "sharpness", 5));
            list.add(book(server, "protection", 4));
            list.add(book(server, "feather_falling", 4));
            list.add(book(server, "efficiency", 5));
            list.add(book(server, "looting", 3));
            list.add(book(server, "power", 5));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("lapis_block", 64));
            list.add(itemStack("bookshelf", 64));
            list.add(itemStack("enchanting_table", 1));
            list.add(itemStack("anvil", 1));
            list.add(itemStack("grindstone", 1));
            list.add(itemStack("diamond", 32));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(book(server, "infinity", 1));
            list.add(book(server, "breach", 4));
            return list;
         }
      );
      register(
         12,
         "netherite_blacksmith",
         "Netherite Blacksmith's Reserve",
         "black",
         ChatFormatting.DARK_GRAY,
         "8 Netherite Ingots, 16 Debris, Upgrade Templates & 5 God Tools",
         List.of(
            "8x Netherite Ingots, 16x Ancient Debris, 16x Scraps",
            "2x Netherite Upgrade Templates, 32x Diamond Blocks",
            "Full 5-piece Maxed Netherite Tools (Sword, Pick, Axe...)",
            "2x Totem of Undying, Anvil, Master Smithing Stash"
         ),
         75000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("netherite_ingot", 8));
            list.add(itemStack("ancient_debris", 16));
            list.add(itemStack("netherite_scrap", 16));
            list.add(itemStack("netherite_upgrade_smithing_template", 2));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_hoe", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("diamond_block", 32));
            list.add(itemStack("iron_block", 64));
            list.add(itemStack("gold_block", 32));
            list.add(itemStack("anvil", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(book(server, "mending", 1));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("lava_bucket", 1));
            list.add(itemStack("diamond", 64));
            list.add(itemStack("ancient_debris", 4));
            list.add(itemStack("netherite_block", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(book(server, "efficiency", 5));
            return list;
         }
      );
      register(
         13,
         "royal_trim_fashion",
         "Royal Trim Fashionista Kit",
         "pink",
         ChatFormatting.LIGHT_PURPLE,
         "Silence, Ward, Spire, Eye, Vex Templates & Trimmed Armor",
         List.of(
            "Silence Armor Trim (Rarest 1.2% Ancient City loot)",
            "Ward, Spire, Eye, Vex & Netherite Upgrade Templates",
            "Full Maxed Netherite Armor Set, Gold & Diamonds",
            "2x Totem of Undying, Amethyst Shards"
         ),
         68000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("silence_armor_trim_smithing_template", 1));
            list.add(itemStack("ward_armor_trim_smithing_template", 1));
            list.add(itemStack("spire_armor_trim_smithing_template", 1));
            list.add(itemStack("eye_armor_trim_smithing_template", 1));
            list.add(itemStack("vex_armor_trim_smithing_template", 1));
            list.add(itemStack("netherite_upgrade_smithing_template", 2));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("gold_block", 16));
            list.add(itemStack("diamond_block", 16));
            list.add(itemStack("amethyst_shard", 64));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(book(server, "mending", 1));
            list.add(book(server, "protection", 4));
            list.add(itemStack("iron_block", 32));
            list.add(itemStack("copper_block", 32));
            list.add(itemStack("lapis_block", 32));
            list.add(itemStack("redstone_block", 32));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         14,
         "ancient_archaeologist",
         "Ancient Archaeologist's Trove",
         "brown",
         ChatFormatting.GOLD,
         "Sniffer Eggs, Rare Music Discs (Relic/Creator) & Relics",
         List.of(
            "2x Sniffer Eggs (Archaeology Ancient Hatchable)",
            "Music Discs (Relic & Creator), Maxed Brush",
            "Silk Touch Netherite Tools, Torchflowers, Pitcher Pods",
            "2x Totem of Undying, Gold Blocks, Diamond Blocks"
         ),
         45000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("sniffer_egg", 2));
            list.add(gear(server, "brush", "unbreaking", 3, "mending", 1));
            list.add(itemStack("music_disc_relic", 1));
            list.add(itemStack("music_disc_creator", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(itemStack("torchflower_seeds", 16));
            list.add(itemStack("pitcher_pod", 16));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("gold_block", 16));
            list.add(itemStack("diamond_block", 8));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("ender_chest", 4));
            list.add(itemStack("lead", 4));
            list.add(itemStack("sniffer_egg", 1));
            list.add(itemStack("amethyst_shard", 32));
            list.add(itemStack("copper_block", 32));
            list.add(book(server, "silk_touch", 1));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         15,
         "redstone_engineer",
         "Redstone Engineer's Superbox",
         "red",
         ChatFormatting.RED,
         "Industrial Crafters, Observers, Pistons & Component Stacks",
         List.of(
            "Crafters, Observers, Sticky Pistons, Repeaters",
            "Comparators, Hoppers, Redstone Blocks, Lamps",
            "Slime Blocks, Honey Blocks, Target Blocks",
            "Silk Touch Pickaxe, 2x Totem of Undying"
         ),
         38000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("redstone_block", 64));
            list.add(itemStack("redstone_block", 64));
            list.add(itemStack("crafter", 16));
            list.add(itemStack("observer", 64));
            list.add(itemStack("sticky_piston", 64));
            list.add(itemStack("piston", 64));
            list.add(itemStack("repeater", 64));
            list.add(itemStack("comparator", 64));
            list.add(itemStack("hopper", 64));
            list.add(itemStack("dispenser", 32));
            list.add(itemStack("dropper", 32));
            list.add(itemStack("target", 32));
            list.add(itemStack("redstone_lamp", 32));
            list.add(itemStack("slime_block", 64));
            list.add(itemStack("honey_block", 64));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("iron_block", 16));
            list.add(itemStack("gold_block", 16));
            list.add(itemStack("tnt", 32));
            list.add(book(server, "mending", 1));
            list.add(itemStack("chest", 64));
            list.add(itemStack("barrel", 64));
            return list;
         }
      );
      register(
         16,
         "crystal_pvp",
         "Crystal PvP Dominator Kit",
         "black",
         ChatFormatting.DARK_PURPLE,
         "End Crystals, Obsidian, Respawn Anchors & Blast Prot Armor",
         List.of(
            "Blast Prot IV Netherite Armor Set, Sharp V Knockback Sword",
            "64x End Crystals, 64x Obsidian, 64x Respawn Anchors",
            "4x Totem of Undying, 8x God Apples, Strength II Potions",
            "64x Glowstone, 32x Ender Pearls, Chorus Fruit"
         ),
         68000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "fire_aspect", 2, "knockback", 2, "unbreaking", 3, "mending", 1));
            list.add(itemStack("end_crystal", 64));
            list.add(itemStack("obsidian", 64));
            list.add(itemStack("respawn_anchor", 64));
            list.add(itemStack("glowstone", 64));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 8));
            list.add(itemStack("golden_apple", 32));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("chorus_fruit", 64));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(book(server, "sharpness", 5));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         17,
         "shadow_assassin",
         "Shadow Assassin / Ninja Kit",
         "gray",
         ChatFormatting.DARK_GRAY,
         "Invisibility Stash, Swift Sneak Armor, Piercing Crossbow",
         List.of(
            "Swift Sneak III & Soul Speed Netherite Armor",
            "Netherite 'Shadow Fang' Sword, Piercing IV Crossbow",
            "Invisibility & Speed II Potions, 3x Totem of Undying",
            "Spectral Arrows, Ender Pearls, 4x God Apples"
         ),
         47000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "swift_sneak", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "soul_speed", 3, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "piercing", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_INVISIBILITY, 1));
            list.add(potion(server, "potion", Potions.LONG_INVISIBILITY, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(book(server, "swift_sneak", 3));
            list.add(book(server, "soul_speed", 3));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("shield", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(book(server, "mending", 1));
            return list;
         }
      );
      register(
         18,
         "hellfire_pyromancer",
         "Hellfire Pyromancer Kit",
         "orange",
         ChatFormatting.GOLD,
         "Fire Prot IV Armor, Flame Bow, Fire Aspect Sword & Lava",
         List.of(
            "Fire Protection IV Netherite Armor Set",
            "Netherite Fire Aspect II Sword, Flame & Power Bow",
            "Fire Charges, Lava Buckets, Fire Res Potions",
            "4x Totem of Undying, Blaze Powder, Golden Apples"
         ),
         44000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "fire_protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "fire_aspect", 2, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "bow", "power", 5, "flame", 1, "infinity", 1, "unbreaking", 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("lava_bucket", 1));
            list.add(itemStack("lava_bucket", 1));
            list.add(gear(server, "flint_and_steel", "unbreaking", 3, "mending", 1));
            list.add(itemStack("blaze_rod", 32));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("cooked_porkchop", 64));
            list.add(itemStack("golden_carrot", 32));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "fire_aspect", 2));
            list.add(book(server, "flame", 1));
            list.add(book(server, "fire_protection", 4));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         19,
         "frost_knight",
         "Frost Knight / Tundra Warden",
         "light_blue",
         ChatFormatting.AQUA,
         "Frost Walker II Netherite Boots, Blue Ice, Powder Snow",
         List.of(
            "Frost Walker II & Prot IV Netherite Armor Set",
            "Netherite 'Frostbite' Sword, Silk Touch Pickaxe",
            "64x Blue Ice, 64x Packed Ice, Powder Snow Buckets",
            "3x Totem of Undying, God Apples, Golden Carrots"
         ),
         45000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "frost_walker", 2, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "knockback", 2, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(itemStack("blue_ice", 64));
            list.add(itemStack("packed_ice", 64));
            list.add(itemStack("powder_snow_bucket", 1));
            list.add(itemStack("powder_snow_bucket", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "frost_walker", 2));
            list.add(book(server, "silk_touch", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("snowball", 16));
            list.add(itemStack("snowball", 16));
            return list;
         }
      );
      register(
         20,
         "timber_baron",
         "Timber Baron & Woodland King",
         "green",
         ChatFormatting.DARK_GREEN,
         "Dual God Netherite Axes, All Wood Stacks, Shears & Hoes",
         List.of(
            "Lumberjack King Netherite Axes (Fortune & Silk Touch)",
            "Netherite Hoe (Eff V, Mend), Shears (Eff V, Mend)",
            "Wood Stacks (Oak, Spruce, Dark Oak, Mangrove, Cherry)",
            "2x Totem of Undying, Bone Meal, Golden Apples"
         ),
         36000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_axe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_hoe", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shears", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("oak_log", 64));
            list.add(itemStack("spruce_log", 64));
            list.add(itemStack("dark_oak_log", 64));
            list.add(itemStack("mangrove_log", 64));
            list.add(itemStack("cherry_log", 64));
            list.add(itemStack("bone_meal", 64));
            list.add(itemStack("bone_meal", 64));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "efficiency", 5));
            list.add(book(server, "unbreaking", 3));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("beehive", 4));
            list.add(itemStack("honey_bottle", 4));
            return list;
         }
      );
      register(
         21,
         "master_architect",
         "Master Architect's Palatial Stash",
         "white",
         ChatFormatting.WHITE,
         "Quartz Blocks, Sea Lanterns, Dark Prismarine & Silk Tools",
         List.of(
            "Quartz Blocks, Smooth Stone, Sea Lanterns, Blackstone",
            "Dark Prismarine, Copper Blocks, Tinted Glass, Deepslate",
            "Silk Touch Netherite Pickaxe & Shovel, 2x Totem",
            "Diamond Blocks, Golden Carrots & Building Stash"
         ),
         40000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("quartz_block", 64));
            list.add(itemStack("quartz_block", 64));
            list.add(itemStack("smooth_stone", 64));
            list.add(itemStack("sea_lantern", 64));
            list.add(itemStack("polished_blackstone_bricks", 64));
            list.add(itemStack("dark_prismarine", 64));
            list.add(itemStack("copper_block", 64));
            list.add(itemStack("tinted_glass", 64));
            list.add(itemStack("deepslate_bricks", 64));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("ender_chest", 4));
            list.add(itemStack("glass", 64));
            list.add(itemStack("stone", 64));
            list.add(itemStack("glowstone", 64));
            list.add(book(server, "silk_touch", 1));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         22,
         "master_alchemist",
         "Master Alchemist's Laboratory",
         "purple",
         ChatFormatting.DARK_PURPLE,
         "Brewing Stand, Full Reagents, Dragon's Breath & Potions",
         List.of(
            "Brewing Stand, Dragon's Breath, Ghast Tears, Nether Wart",
            "Blaze Powder, Glistering Melons, Phantom Membranes",
            "2x Totem of Undying, God Apples, Redstone, Glowstone",
            "Finished High-Tier Splash & Lingering Potions"
         ),
         44000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("brewing_stand", 2));
            list.add(itemStack("dragon_breath", 16));
            list.add(itemStack("ghast_tear", 16));
            list.add(itemStack("nether_wart", 64));
            list.add(itemStack("blaze_powder", 64));
            list.add(itemStack("glistering_melon_slice", 32));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("magma_cream", 32));
            list.add(itemStack("phantom_membrane", 32));
            list.add(itemStack("fermented_spider_eye", 32));
            list.add(itemStack("redstone", 64));
            list.add(itemStack("glowstone_dust", 64));
            list.add(itemStack("gunpowder", 64));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.LONG_INVISIBILITY, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("totem_of_undying", 1));
            list.add(book(server, "mending", 1));
            list.add(itemStack("glass_bottle", 64));
            return list;
         }
      );
      register(
         23,
         "elytra_ace_pilot",
         "Elytra Ace Pilot Squadron Kit",
         "cyan",
         ChatFormatting.AQUA,
         "Dual Enchanted Elytras, 192x Flight 3 Rockets & 4x Totems",
         List.of(
            "2x Enchanted Elytra (Unbreaking III, Mending)",
            "192x Flight Duration 3 Fireworks, 4x Totems",
            "Netherite Helmet & Feather Falling IV Boots",
            "Slow Falling Potions, Ender Pearls, 4x God Apples"
         ),
         62000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "elytra", "unbreaking", 3, "mending", 1));
            list.add(gear(server, "elytra", "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(fireworks(64, 3));
            list.add(fireworks(64, 3));
            list.add(fireworks(64, 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "unbreaking", 3));
            list.add(book(server, "mending", 1));
            list.add(itemStack("chorus_fruit", 64));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("shulker_shell", 4));
            return list;
         }
      );
      register(
         24,
         "soundless_stalker",
         "Soundless Stalker / Sculk Raider",
         "black",
         ChatFormatting.DARK_AQUA,
         "Wind Burst Mace, Swift Sneak III Armor, Wool Stash",
         List.of(
            "Mace (Wind Burst III, Density V, Mending)",
            "Swift Sneak III Netherite Armor Set, 4x Totems",
            "Wool stacks, Night Vision & Invisibility Potions",
            "4x God Apples, Ender Pearls & Golden Carrots"
         ),
         54000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "mace", "wind_burst", 3, "density", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "swift_sneak", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("white_wool", 64));
            list.add(itemStack("gray_wool", 64));
            list.add(itemStack("black_wool", 64));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_INVISIBILITY, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("echo_shard", 4));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "swift_sneak", 3));
            list.add(book(server, "wind_burst", 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("lead", 2));
            return list;
         }
      );
      register(
         25,
         "demolition_expert",
         "Demolition Expert / Blast Master",
         "red",
         ChatFormatting.RED,
         "256x TNT, 64x TNT Minecarts, Blast Prot Armor & Redstone",
         List.of(
            "256x TNT, 64x TNT Minecarts, Activator Rails",
            "Blast Protection IV Netherite Armor Set",
            "3x Totem of Undying, Flint and Steel (Mending)",
            "Redstone Blocks, 4x God Apples, Golden Carrots"
         ),
         42000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("tnt", 64));
            list.add(itemStack("tnt", 64));
            list.add(itemStack("tnt", 64));
            list.add(itemStack("tnt", 64));
            list.add(itemStack("tnt_minecart", 64));
            list.add(itemStack("activator_rail", 64));
            list.add(itemStack("redstone_block", 64));
            list.add(gear(server, "netherite_helmet", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "blast_protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "flint_and_steel", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "blast_protection", 4));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("dispenser", 16));
            return list;
         }
      );
      register(
         26,
         "cavalry_commander",
         "Cavalry Knight & Beast Master",
         "brown",
         ChatFormatting.GOLD,
         "Wolf Armor (Mending), Horse Armor, Saddles & God Sword",
         List.of(
            "Wolf Armor (Unbreaking III, Mending), Diamond Horse Armor",
            "Netherite Sharpness V Sword & Shield, 3x Totem",
            "Saddles, Leads, Golden Apples, Golden Carrots",
            "Diamond Blocks & Veterinary Supplies"
         ),
         38000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "wolf_armor", "unbreaking", 3, "mending", 1));
            list.add(itemStack("diamond_horse_armor", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "knockback", 2, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(itemStack("saddle", 2));
            list.add(itemStack("lead", 8));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 32));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("hay_block", 64));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "unbreaking", 3));
            list.add(book(server, "mending", 1));
            list.add(gear(server, "wolf_armor", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("shears", 1));
            list.add(itemStack("iron_block", 16));
            return list;
         }
      );
      register(
         27,
         "bastion_conqueror",
         "Nether Bastion Conqueror",
         "red",
         ChatFormatting.DARK_RED,
         "Gold Armor + Netherite, 64x Gold Blocks & Bastion Loot",
         List.of(
            "Netherite Armor with Gold Helmet (Pacifies Piglins)",
            "Netherite Axe & Sword, 64x Gold Blocks, Quartz",
            "3x Totem of Undying, Fire Res Potions, God Apples",
            "Bastion Loot, Netherite Ingots & Ancient Debris"
         ),
         50000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "golden_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("gold_block", 64));
            list.add(itemStack("gold_block", 32));
            list.add(itemStack("netherite_upgrade_smithing_template", 1));
            list.add(itemStack("ancient_debris", 4));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("cooked_porkchop", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("music_disc_pigstep", 1));
            list.add(book(server, "looting", 3));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         28,
         "end_city_hoard",
         "End City Sovereign Hoard",
         "magenta",
         ChatFormatting.LIGHT_PURPLE,
         "Dragon Head, Elytra, 16x Shulker Shells & Diamond Gear",
         List.of(
            "1x Dragon Head (End Ship prow trophy)",
            "1x Elytra (Unbreaking III, Mending), 16x Shulker Shells",
            "Enchanted Diamond Armor Set, 3x Totem of Undying",
            "32x Chorus Fruit, 64x End Rods, God Apples, Pearls"
         ),
         56000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("dragon_head", 1));
            list.add(gear(server, "elytra", "unbreaking", 3, "mending", 1));
            list.add(gear(server, "diamond_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "diamond_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "diamond_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "diamond_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "diamond_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "diamond_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("shulker_shell", 16));
            list.add(itemStack("chorus_fruit", 64));
            list.add(itemStack("end_rod", 64));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(book(server, "mending", 1));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("ender_chest", 4));
            return list;
         }
      );
      register(
         29,
         "arena_gladiator",
         "Gladiator Champion of the Colosseum",
         "yellow",
         ChatFormatting.GOLD,
         "Prot IV Thorns III Armor, God Sword, 4x Totems & Potions",
         List.of(
            "Netherite Armor Set with Thorns III & Prot IV",
            "Netherite Sharpness V Fire Aspect II God Sword",
            "4x Totem of Undying, Strength II & Speed II Potions",
            "6x God Apples, Steaks, Golden Carrots & Enchants"
         ),
         60000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "thorns", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "thorns", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "thorns", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "thorns", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "fire_aspect", 2, "knockback", 2, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(itemStack("enchanted_golden_apple", 6));
            list.add(itemStack("golden_apple", 24));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "thorns", 3));
            list.add(book(server, "sharpness", 5));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("ender_pearl", 16));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            return list;
         }
      );
      register(
         30,
         "marksman_sniper",
         "Marksman & Legendary Archer",
         "lime",
         ChatFormatting.GREEN,
         "Eagle Eye Bow, Ballista Crossbow & Tipped Arrows",
         List.of(
            "Bow 'Eagle Eye' (Power V, Flame, Punch II, Infinity)",
            "Crossbow 'Ballista' (Piercing IV, Quick Charge III)",
            "Multishot Crossbow, 3x Totem of Undying, God Apples",
            "Spectral Arrows, Netherite Armor & Sniper Stash"
         ),
         50000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "bow", "power", 5, "flame", 1, "punch", 2, "infinity", 1, "unbreaking", 3));
            list.add(gear(server, "crossbow", "quick_charge", 3, "piercing", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "multishot", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.LONG_INVISIBILITY, 1));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "power", 5));
            list.add(book(server, "piercing", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("ender_pearl", 16));
            return list;
         }
      );
      register(
         31,
         "spelunker_cave_king",
         "Spelunker Cave Explorer",
         "gray",
         ChatFormatting.GRAY,
         "5-Piece Netherite Tools, 256 Torches, Night Vision & Debris",
         List.of(
            "Full Netherite Toolset (Pick, Shovel, Axe, Hoe, Sword)",
            "256x Torches, Night Vision Potions, 3x Totem",
            "Golden Carrots, Water Buckets, Deepslate Blocks",
            "Ancient Debris, Diamonds & Cave Survival Gear"
         ),
         46000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_hoe", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("torch", 64));
            list.add(itemStack("torch", 64));
            list.add(itemStack("torch", 64));
            list.add(itemStack("torch", 64));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("water_bucket", 1));
            list.add(itemStack("water_bucket", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("ancient_debris", 4));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("iron_block", 32));
            list.add(itemStack("ender_chest", 4));
            list.add(book(server, "efficiency", 5));
            list.add(book(server, "fortune", 3));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         32,
         "witch_purger",
         "Witch Purger & Cursebreaker",
         "purple",
         ChatFormatting.DARK_PURPLE,
         "Honey Bottles, Splash Potions, Smite V Sword & Totems",
         List.of(
            "Netherite Armor Set & Smite V Netherite Sword",
            "Honey Bottles (Instant Poison cure), 4x Totems",
            "Splash Health II & Splash Damage II Potions",
            "God Apples, Golden Carrots & Cursebreaker Gear"
         ),
         44000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "smite", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("honey_bottle", 4));
            list.add(itemStack("honey_bottle", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HARMING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HARMING, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "smite", 5));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("glowstone_dust", 32));
            return list;
         }
      );
      register(
         33,
         "mob_farm_overlord",
         "Mob Farm & Spawner Overlord",
         "green",
         ChatFormatting.DARK_GREEN,
         "Looting III Sweeper Sword, 64x Hoppers, Soul Sand & Chests",
         List.of(
            "Netherite Sword (Loot III, Sweeping III, Sharp V, Mend)",
            "64x Hoppers, 64x Soul Sand, 64x Chests, Campfires",
            "2x Totem of Undying, Water Buckets, Golden Apples",
            "Spawner Materials, Redstone & Storage Stash"
         ),
         41000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("hopper", 64));
            list.add(itemStack("soul_sand", 64));
            list.add(itemStack("chest", 64));
            list.add(itemStack("chest", 64));
            list.add(itemStack("campfire", 32));
            list.add(itemStack("soul_campfire", 32));
            list.add(itemStack("water_bucket", 1));
            list.add(itemStack("water_bucket", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("iron_block", 32));
            list.add(itemStack("redstone_block", 32));
            list.add(itemStack("dropper", 32));
            list.add(itemStack("dispenser", 32));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "looting", 3));
            list.add(book(server, "sweeping_edge", 3));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("packed_ice", 64));
            return list;
         }
      );
      register(
         34,
         "poseidon_angler",
         "Poseidon's Angler & Deep Fisher",
         "light_blue",
         ChatFormatting.AQUA,
         "Mythic Fishing Rod, Heart of the Sea, Shells & 4 Books",
         List.of(
            "Poseidon's Rod (Luck of Sea III, Lure III, Mend)",
            "Heart of the Sea, 8x Nautilus Shells, 4x Enchants",
            "2x Totem of Undying, Cooked Salmon, Sea Lanterns",
            "Water Breathing Potions & Marine Treasures"
         ),
         42000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "fishing_rod", "luck_of_the_sea", 3, "lure", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("heart_of_the_sea", 1));
            list.add(itemStack("conduit", 1));
            list.add(itemStack("sea_lantern", 64));
            list.add(book(server, "mending", 1));
            list.add(book(server, "luck_of_the_sea", 3));
            list.add(book(server, "lure", 3));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_WATER_BREATHING, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(itemStack("enchanted_golden_apple", 2));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("cooked_salmon", 64));
            list.add(itemStack("cooked_salmon", 64));
            list.add(itemStack("prismarine_shard", 64));
            list.add(itemStack("prismarine_crystals", 64));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("sponge", 8));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("golden_carrot", 64));
            list.add(gear(server, "trident", "impaling", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("ender_chest", 4));
            return list;
         }
      );
      register(
         35,
         "end_sorcerer",
         "End Sorcerer's Void Vault",
         "magenta",
         ChatFormatting.DARK_PURPLE,
         "128x End Crystals, 128x Obsidian, Blast Armor & 4 Totems",
         List.of(
            "128x End Crystals, 128x Obsidian, 4x Totems",
            "Blast Protection IV Netherite Armor Set",
            "6x God Apples, Strength Potions, 32x Ender Pearls",
            "Void Sorcery Stash, Diamonds & Netherite"
         ),
         66000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("end_crystal", 64));
            list.add(itemStack("end_crystal", 64));
            list.add(itemStack("obsidian", 64));
            list.add(itemStack("obsidian", 64));
            list.add(gear(server, "netherite_helmet", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "blast_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 6));
            list.add(itemStack("golden_apple", 24));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(book(server, "mending", 1));
            list.add(book(server, "blast_protection", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "unbreaking", 3, "mending", 1));
            return list;
         }
      );
      register(
         36,
         "titan_defender",
         "Titan's Bastion & Unbreakable Shield",
         "blue",
         ChatFormatting.BLUE,
         "Prot IV & Proj Prot Armor, 2 Shields, 5 Totems & Potions",
         List.of(
            "Netherite Armor (Prot IV, Proj Prot IV, Unbreaking III)",
            "2x God Shields (Unbreaking III, Mending), 5x Totems",
            "Turtle Master Potions (Slowness IV, Res III)",
            "4x God Apples, Golden Apples & Fortification Stash"
         ),
         55000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "projectile_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.STRONG_TURTLE_MASTER, 1));
            list.add(potion(server, "potion", Potions.STRONG_TURTLE_MASTER, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 32));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("iron_block", 32));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "protection", 4));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("obsidian", 32));
            return list;
         }
      );
      register(
         37,
         "bounty_hunter",
         "Bounty Hunter's Manhunt Kit",
         "orange",
         ChatFormatting.GOLD,
         "Sharp V Fire II Sword, Infinity Bow, Speed II & 4 Totems",
         List.of(
            "Netherite Sword (Sharp V, Fire II, Loot III, Mend)",
            "Infinity Bow, Speed II Potions, 4x Totems",
            "Ender Pearls, Compass, Clock, 4x God Apples",
            "Netherite Armor & High-Speed Pursuit Supplies"
         ),
         52000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "fire_aspect", 2, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "bow", "power", 5, "infinity", 1, "unbreaking", 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("compass", 1));
            list.add(itemStack("clock", 1));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "sharpness", 5));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         38,
         "beacon_high_altar",
         "High Altar of the Nether Star",
         "white",
         ChatFormatting.WHITE,
         "2x Nether Stars, Beacon, 4 Netherite Blocks & Full Base",
         List.of(
            "2x Nether Stars, 1x Beacon, 4x Netherite Blocks",
            "64x Iron Blocks, 64x Emerald Blocks, 32x Diamond Blocks",
            "2x Totem of Undying, 4x God Apples, Golden Carrots",
            "Instant Level 4 Beacon Pyramidal Base"
         ),
         72000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("nether_star", 2));
            list.add(itemStack("beacon", 1));
            list.add(itemStack("netherite_block", 4));
            list.add(itemStack("iron_block", 64));
            list.add(itemStack("iron_block", 64));
            list.add(itemStack("emerald_block", 64));
            list.add(itemStack("diamond_block", 32));
            list.add(itemStack("gold_block", 32));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 24));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(itemStack("obsidian", 64));
            list.add(itemStack("glass", 64));
            list.add(book(server, "mending", 1));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("diamond", 64));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("ender_chest", 4));
            list.add(itemStack("sea_lantern", 32));
            return list;
         }
      );
      register(
         39,
         "dragon_reliquary",
         "Dragon Monarch's Reliquary",
         "purple",
         ChatFormatting.DARK_PURPLE,
         "Dragon Head, Elytra, 16x Dragon's Breath & 4 Crystals",
         List.of(
            "1x Dragon Head (Ship prow trophy), 1x Elytra (Mending)",
            "16x Dragon's Breath, 4x End Crystals, 3x Totems",
            "4x God Apples, Ender Pearls, Diamonds & Ingot",
            "Monarch's End Relics & Ritual Supplies"
         ),
         58000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("dragon_head", 1));
            list.add(gear(server, "elytra", "unbreaking", 3, "mending", 1));
            list.add(itemStack("dragon_breath", 16));
            list.add(itemStack("end_crystal", 4));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("chorus_fruit", 64));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(book(server, "mending", 1));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("obsidian", 32));
            list.add(itemStack("shulker_shell", 8));
            list.add(itemStack("end_rod", 32));
            list.add(fireworks(64, 3));
            return list;
         }
      );
      register(
         40,
         "deepslate_treasure",
         "Deepslate Ore Baron's Cache",
         "black",
         ChatFormatting.DARK_GRAY,
         "64 Diamonds, 32 Ancient Debris, God Picks & Scraps",
         List.of(
            "Dual Netherite Picks (Fortune III & Silk Touch)",
            "64x Diamonds, 32x Ancient Debris, 16x Scraps",
            "3x Totem of Undying, Night Vision Potions, God Apples",
            "Netherite Shovel, Diamond Blocks & Ores"
         ),
         58000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("diamond", 64));
            list.add(itemStack("ancient_debris", 32));
            list.add(itemStack("netherite_scrap", 16));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("diamond_block", 8));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("torch", 64));
            list.add(itemStack("ender_chest", 4));
            list.add(book(server, "efficiency", 5));
            list.add(book(server, "fortune", 3));
            list.add(itemStack("iron_block", 32));
            list.add(itemStack("gold_block", 32));
            list.add(itemStack("totem_of_undying", 1));
            list.add(book(server, "mending", 1));
            return list;
         }
      );
      register(
         41,
         "god_apple_sanctuary",
         "Golden Sanctuary & Vitality Cache",
         "yellow",
         ChatFormatting.YELLOW,
         "8x Enchanted God Apples, 64x Golden Apples & Netherite",
         List.of(
            "8x Enchanted God Apples (Uncraftable Overpowered)",
            "64x Golden Apples, 4x Totem of Undying",
            "Netherite Chestplate (Prot IV, Mend), Regen II",
            "Splash Health II, Golden Carrots & Vitality"
         ),
         74000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("enchanted_golden_apple", 8));
            list.add(itemStack("golden_apple", 64));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(potion(server, "splash_potion", Potions.STRONG_HEALING, 1));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("gold_block", 32));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "protection", 4));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("apple", 64));
            list.add(itemStack("gold_ingot", 64));
            list.add(itemStack("gold_ingot", 64));
            list.add(itemStack("beacon", 1));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         42,
         "gale_force_champion",
         "Gale Force Wind Burst Master",
         "light_gray",
         ChatFormatting.GRAY,
         "Wind Burst III Density V Mace, 192 Wind Charges & Ominous",
         List.of(
            "Mace 'Gale Force' (Wind Burst III, Density V, Mend)",
            "192x Wind Charges, 4x Ominous Bottles V, 4x Totems",
            "Crossbow (Piercing IV, Quick Charge III), 4x God Apples",
            "Feather Falling IV Netherite Boots & Trial Stash"
         ),
         60000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "mace", "wind_burst", 3, "density", 5, "breach", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("wind_charge", 64));
            list.add(itemStack("wind_charge", 64));
            list.add(itemStack("wind_charge", 64));
            list.add(itemStack("ominous_bottle", 4));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "wind_burst", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "piercing", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("trial_key", 8));
            list.add(itemStack("ominous_trial_key", 4));
            list.add(itemStack("arrow", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.STRONG_SWIFTNESS, 1));
            list.add(potion(server, "potion", Potions.LONG_SLOW_FALLING, 1));
            list.add(itemStack("diamond_block", 2));
            list.add(book(server, "wind_burst", 3));
            list.add(book(server, "density", 5));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("heavy_core", 1));
            return list;
         }
      );
      register(
         43,
         "ancient_debris_excavator",
         "Ancient Debris & Scraps Excavator",
         "red",
         ChatFormatting.DARK_RED,
         "Netherite Armor, 8 Scraps, 4 Debris, Fire Res & Tools",
         List.of(
            "Netherite Armor Set (Prot IV & Fire Prot IV)",
            "Netherite Sword & Axe, 4x Ancient Debris, 8x Scraps",
            "3x Totem of Undying, Fire Res Potions, God Apples",
            "Pickaxes, Golden Carrots & Nether Survival Gear"
         ),
         48000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "fire_protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("ancient_debris", 4));
            list.add(itemStack("netherite_scrap", 8));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_porkchop", 64));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("gold_block", 16));
            list.add(book(server, "efficiency", 5));
            list.add(book(server, "unbreaking", 3));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("ender_pearl", 16));
            return list;
         }
      );
      register(
         44,
         "tempest_trident_vault",
         "Tempest Caller's Trident Armory",
         "cyan",
         ChatFormatting.AQUA,
         "2x Channeling Tridents, 1x Riptide Trident, Conduit & 3 Totems",
         List.of(
            "2x Channeling & Loyalty III Impaling V Tridents",
            "1x Riptide III Impaling V Trident, Conduit Power",
            "3x Totem of Undying, Water Breathing Potions",
            "God Apples, Prismarine, Cooked Salmon & Depth Gear"
         ),
         50000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "trident", "channeling", 1, "loyalty", 3, "impaling", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "trident", "channeling", 1, "loyalty", 3, "impaling", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "trident", "riptide", 3, "impaling", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("conduit", 1));
            list.add(gear(server, "turtle_helmet", "protection", 4, "respiration", 3, "aqua_affinity", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "depth_strider", 3, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(potion(server, "potion", Potions.LONG_WATER_BREATHING, 1));
            list.add(potion(server, "potion", Potions.LONG_WATER_BREATHING, 1));
            list.add(potion(server, "potion", Potions.LONG_NIGHT_VISION, 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("cooked_salmon", 64));
            list.add(itemStack("sea_lantern", 64));
            list.add(itemStack("prismarine_shard", 64));
            list.add(itemStack("prismarine_crystals", 64));
            list.add(itemStack("diamond_block", 2));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "channeling", 1));
            list.add(book(server, "loyalty", 3));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("heart_of_the_sea", 1));
            list.add(itemStack("golden_carrot", 64));
            return list;
         }
      );
      register(
         45,
         "paladin_crusader",
         "Paladin of Holy Radiance",
         "yellow",
         ChatFormatting.GOLD,
         "Holy Smite V Sweeper Sword, Beacon, 4 Totems & 6 God Apples",
         List.of(
            "Netherite Armor Set with Prot IV & Mending",
            "Netherite 'Holy Avenger' Sword (Smite V, Sweep III)",
            "1x Beacon, 4x Totem of Undying, 6x God Apples",
            "Regeneration II Potions, Golden Carrots & Gold Blocks"
         ),
         58000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "smite", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "shield", "unbreaking", 3, "mending", 1));
            list.add(itemStack("beacon", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 6));
            list.add(itemStack("golden_apple", 24));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(itemStack("gold_block", 32));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(book(server, "smite", 5));
            list.add(book(server, "protection", 4));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("iron_block", 32));
            list.add(itemStack("netherite_ingot", 2));
            return list;
         }
      );
      register(
         46,
         "desert_dunes_nomad",
         "Dune Raider & Desert Archaeologist",
         "orange",
         ChatFormatting.GOLD,
         "Dune Trim Template, 2 Sniffer Eggs, God Bow & 4 Totems",
         List.of(
            "Dune Armor Trim Smithing Template, 2x Sniffer Eggs",
            "Netherite Sharpness V Sword & Infinity God Bow",
            "4x Totem of Undying, Golden Apples, Sandstone Stash",
            "Diamonds, Gold & Desert Exploration Supplies"
         ),
         44000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("dune_armor_trim_smithing_template", 2));
            list.add(itemStack("sniffer_egg", 2));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "bow", "power", 5, "infinity", 1, "unbreaking", 3));
            list.add(gear(server, "brush", "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 3));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("gold_block", 16));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("netherite_ingot", 2));
            list.add(itemStack("arrow", 64));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(potion(server, "potion", Potions.LONG_SWIFTNESS, 1));
            list.add(book(server, "power", 5));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("lead", 4));
            list.add(itemStack("saddle", 2));
            list.add(itemStack("torchflower_seeds", 16));
            list.add(itemStack("pitcher_pod", 16));
            return list;
         }
      );
      register(
         47,
         "mansion_raider",
         "Woodland Mansion Cleanser",
         "green",
         ChatFormatting.DARK_GREEN,
         "6x Totem of Undying, Sharp V Axe, Crossbow & Emeralds",
         List.of(
            "6x Totem of Undying (Looted from Evokers)",
            "Netherite Sharpness V Axe, Multishot Crossbow",
            "4x God Apples, 16x Golden Apples, Emerald Blocks",
            "Netherite Armor Set & Woodland Cleansing Supplies"
         ),
         55000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();

            for (int i = 0; i < 6; i++) {
               list.add(itemStack("totem_of_undying", 1));
            }

            list.add(gear(server, "netherite_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "crossbow", "quick_charge", 3, "multishot", 1, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(itemStack("emerald_block", 64));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 16));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("arrow", 64));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(itemStack("diamond_block", 3));
            list.add(itemStack("netherite_ingot", 2));
            list.add(book(server, "sharpness", 5));
            list.add(book(server, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("ender_pearl", 16));
            return list;
         }
      );
      register(
         48,
         "survival_apex_bunker",
         "Survivalist's Apex Preparedness Bunker",
         "gray",
         ChatFormatting.DARK_GRAY,
         "Full God Armor, Full 5-Piece Toolset, 4 Totems & Anvil",
         List.of(
            "Full Maxed Netherite Armor & 5-Piece Toolset",
            "4x Totem of Undying, 4x God Apples, Ender Chests",
            "Anvil, Enchanting Table, Obsidian, Pearls",
            "Complete Mobile Survival Base in One Box"
         ),
         72000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(gear(server, "netherite_helmet", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_chestplate", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_leggings", "protection", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_boots", "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_shovel", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_hoe", "efficiency", 5, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 32));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(itemStack("ender_chest", 8));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("obsidian", 64));
            list.add(itemStack("anvil", 1));
            list.add(itemStack("enchanting_table", 1));
            list.add(itemStack("diamond_block", 4));
            list.add(itemStack("netherite_ingot", 4));
            list.add(book(server, "mending", 1));
            list.add(book(server, "unbreaking", 3));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
      register(
         49,
         "omega_singularity",
         "The Omega Singularity Shulker",
         "purple",
         ChatFormatting.LIGHT_PURPLE,
         "Dragon Egg, Elytra, Nether Star, Dragon Head & Heavy Core",
         List.of(
            "Dragon Egg (The Rarest Trophy in Minecraft)",
            "Elytra, Nether Star, Dragon Head, Heavy Core",
            "God Netherite Sword & Pickaxe, 4x Totems, 4x God Apples",
            "4x Netherite Ingots, Diamond Blocks, Emerald Blocks"
         ),
         98000L,
         (server, random) -> {
            List<ItemStack> list = new ArrayList<>();
            list.add(itemStack("dragon_egg", 1));
            list.add(gear(server, "elytra", "unbreaking", 3, "mending", 1));
            list.add(itemStack("nether_star", 1));
            list.add(itemStack("dragon_head", 1));
            list.add(itemStack("heavy_core", 1));
            list.add(gear(server, "netherite_sword", "sharpness", 5, "looting", 3, "fire_aspect", 2, "sweeping_edge", 3, "unbreaking", 3, "mending", 1));
            list.add(gear(server, "netherite_pickaxe", "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("totem_of_undying", 1));
            list.add(itemStack("enchanted_golden_apple", 4));
            list.add(itemStack("golden_apple", 32));
            list.add(itemStack("netherite_ingot", 4));
            list.add(itemStack("diamond_block", 16));
            list.add(itemStack("emerald_block", 16));
            list.add(itemStack("ender_pearl", 16));
            list.add(itemStack("chorus_fruit", 64));
            list.add(itemStack("golden_carrot", 64));
            list.add(itemStack("cooked_beef", 64));
            list.add(potion(server, "potion", Potions.STRONG_REGENERATION, 1));
            list.add(potion(server, "potion", Potions.STRONG_STRENGTH, 1));
            list.add(potion(server, "potion", Potions.LONG_FIRE_RESISTANCE, 1));
            list.add(book(server, "mending", 1));
            list.add(book(server, "wind_burst", 3));
            list.add(itemStack("beacon", 1));
            list.add(itemStack("totem_of_undying", 1));
            return list;
         }
      );
   }

   private interface BoxPopulator {
      List<ItemStack> populate(MinecraftServer var1, Random var2);
   }

   private record Entry(GearBoxes.GearBoxSpec spec, GearBoxes.BoxPopulator populator) {
   }

   public record GearBoxSpec(
      int slot, String id, String name, String color, ChatFormatting titleColor, String subtitle, List<String> highlights, long basePrice
   ) {
   }
}
