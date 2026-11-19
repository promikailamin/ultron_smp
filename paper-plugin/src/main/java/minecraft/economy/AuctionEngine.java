package minecraft.economy;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
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
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments.Mutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuctionEngine {
   public static final String KIND_UNIQUE = "UNIQUE";
   public static final String KIND_BOOK = "BOOK";
   public static final String KIND_DISCOUNT = "DISCOUNT";
   public static final String KIND_GEAR_BOX = "GEAR_BOX";
   public static final String KIND_ENCHANTED_GEAR = "ENCHANTED_GEAR";
   public static final String KIND_SUPER_ITEM = "SUPER_ITEM";
   private static final Logger LOGGER = LoggerFactory.getLogger("economy");
   public static final int GEAR_BOX_SLOTS = 50;
   public static final int UNIQUE_SLOTS = 15;
   public static final int BOOK_SLOTS = 12;
   public static final int DISCOUNT_SLOTS = 24;
   public static final int ENCHANTED_GEAR_SLOTS = 24;
   public static final int SUPER_ITEM_SLOTS = 8;
   private static final long MINUTE = 60000L;
   private static final long HOUR = 3600000L;
   private static MinecraftServer currentServer = null;
   private static boolean initialized = false;
   private static long lastUpdate = 0L;
   private static List<EconomyData.ShopEntry> discountCandidates = null;
   private static final List<AuctionEngine.UniqueSpec> UNIQUE_SPECS = List.of(
      new AuctionEngine.UniqueSpec("dragon_egg", "Dragon Egg", 50000L, 0.85, 1.35, 1080),
      new AuctionEngine.UniqueSpec("elytra", "Enchanted Elytra", 25000L, 0.85, 1.35, 720, "unbreaking", "3", "mending", "1"),
      new AuctionEngine.UniqueSpec("heavy_core", "Heavy Core", 20000L, 0.85, 1.3, 600),
      new AuctionEngine.UniqueSpec("dragon_head", "Dragon Head", 15000L, 0.85, 1.35, 480),
      new AuctionEngine.UniqueSpec("silence_armor_trim_smithing_template", "Silence Armor Trim Template", 12000L, 0.85, 1.3, 480),
      new AuctionEngine.UniqueSpec("nether_star", "Nether Star", 10000L, 0.85, 1.35, 360),
      new AuctionEngine.UniqueSpec("ward_armor_trim_smithing_template", "Ward Armor Trim Template", 8000L, 0.85, 1.3, 360),
      new AuctionEngine.UniqueSpec(
         "trident", "Master Trident", 8000L, 0.85, 1.3, 360, "channeling", "1", "loyalty", "3", "impaling", "5", "unbreaking", "3", "mending", "1"
      ),
      new AuctionEngine.UniqueSpec("beacon", "Beacon", 7000L, 0.85, 1.25, 360),
      new AuctionEngine.UniqueSpec("conduit", "Conduit", 6000L, 0.85, 1.25, 300),
      new AuctionEngine.UniqueSpec("sniffer_egg", "Sniffer Egg", 5000L, 0.85, 1.25, 300),
      new AuctionEngine.UniqueSpec("spire_armor_trim_smithing_template", "Spire Armor Trim Template", 5000L, 0.85, 1.25, 300),
      new AuctionEngine.UniqueSpec("eye_armor_trim_smithing_template", "Eye Armor Trim Template", 5000L, 0.85, 1.25, 300),
      new AuctionEngine.UniqueSpec("netherite_upgrade_smithing_template", "Netherite Upgrade Template", 4000L, 0.85, 1.25, 240),
      new AuctionEngine.UniqueSpec("enchanted_golden_apple", "Enchanted Golden Apple", 4000L, 0.85, 1.25, 240)
   );
   private static final List<AuctionEngine.BookSpec> BOOK_SPECS = List.of(
      new AuctionEngine.BookSpec("mending", "Mending", 1, 12000L, 0.8, 1.35, 360),
      new AuctionEngine.BookSpec("wind_burst", "Wind Burst III", 3, 14000L, 0.8, 1.35, 360),
      new AuctionEngine.BookSpec("swift_sneak", "Swift Sneak III", 3, 9000L, 0.8, 1.35, 360),
      new AuctionEngine.BookSpec("fortune", "Fortune III", 3, 8000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("silk_touch", "Silk Touch", 1, 6000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("sharpness", "Sharpness V", 5, 6000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("protection", "Protection IV", 4, 6000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("efficiency", "Efficiency V", 5, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("unbreaking", "Unbreaking III", 3, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("looting", "Looting III", 3, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("power", "Power V", 5, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("feather_falling", "Feather Falling IV", 4, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("density", "Density V", 5, 7000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("breach", "Breach IV", 4, 7000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("infinity", "Infinity", 1, 6000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("soul_speed", "Soul Speed III", 3, 7000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("frost_walker", "Frost Walker II", 2, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("depth_strider", "Depth Strider III", 3, 4500L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("respiration", "Respiration III", 3, 4500L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("aqua_affinity", "Aqua Affinity", 1, 3500L, 0.8, 1.35, 180),
      new AuctionEngine.BookSpec("quick_charge", "Quick Charge III", 3, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("multishot", "Multishot", 1, 4000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("piercing", "Piercing IV", 4, 4000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("sweeping_edge", "Sweeping Edge III", 3, 4500L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("fire_aspect", "Fire Aspect II", 2, 4000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("flame", "Flame", 1, 4000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("thorns", "Thorns III", 3, 4000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("riptide", "Riptide III", 3, 6000L, 0.8, 1.35, 300),
      new AuctionEngine.BookSpec("channeling", "Channeling", 1, 5000L, 0.8, 1.35, 240),
      new AuctionEngine.BookSpec("loyalty", "Loyalty III", 3, 5000L, 0.8, 1.35, 240)
   );
   private static final List<AuctionEngine.MasterGearSpec> MASTER_GEAR = List.of(
      new AuctionEngine.MasterGearSpec(
         "netherite_pickaxe", "World Breaker (Fortune Pick)", 24000L, "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec(
         "netherite_pickaxe", "Silk Carver (Silk Touch Pick)", 22000L, "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec(
         "netherite_sword",
         "Vorpal Cleaver (God Sword)",
         28000L,
         "sharpness",
         5,
         "fire_aspect",
         2,
         "looting",
         3,
         "sweeping_edge",
         3,
         "unbreaking",
         3,
         "mending",
         1
      ),
      new AuctionEngine.MasterGearSpec(
         "netherite_sword", "Undead Bane (Smite Sword)", 22000L, "smite", 5, "looting", 3, "sweeping_edge", 3, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec("netherite_axe", "Executioner's Greataxe", 22000L, "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec("netherite_shovel", "Terra Former (Excavator)", 16000L, "efficiency", 5, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec("netherite_hoe", "Grand Harvester (God Hoe)", 16000L, "efficiency", 5, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec(
         "netherite_helmet", "Crown of the Deep (Helmet)", 26000L, "protection", 4, "respiration", 3, "aqua_affinity", 1, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec("netherite_chestplate", "Titan's Plate (Chestplate)", 30000L, "protection", 4, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec(
         "netherite_leggings", "Shadow Greaves (Leggings)", 28000L, "protection", 4, "swift_sneak", 3, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec(
         "netherite_boots", "Abyssal Striders (God Boots)", 28000L, "protection", 4, "feather_falling", 4, "depth_strider", 3, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec("bow", "Phantom Piercer (God Bow)", 18000L, "power", 5, "flame", 1, "punch", 2, "infinity", 1, "unbreaking", 3),
      new AuctionEngine.MasterGearSpec("crossbow", "Ballista (Piercing Crossbow)", 18000L, "quick_charge", 3, "piercing", 4, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec(
         "mace", "Thunderstrike (Wind Burst Mace)", 32000L, "wind_burst", 3, "density", 5, "breach", 4, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec(
         "trident", "Poseidon's Wrath (God Trident)", 24000L, "channeling", 1, "loyalty", 3, "impaling", 5, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec("shield", "Aegis of the Immortal", 10000L, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec("wolf_armor", "Ironclad Wolf Armor", 12000L, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec("diamond_pickaxe", "Master Diamond Pickaxe", 12000L, "efficiency", 5, "fortune", 3, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec("diamond_sword", "Master Diamond Blade", 14000L, "sharpness", 5, "looting", 3, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec("diamond_chestplate", "Diamond Champion's Plate", 16000L, "protection", 4, "unbreaking", 3, "mending", 1),
      new AuctionEngine.MasterGearSpec(
         "diamond_boots", "Diamond Feather Striders", 14000L, "protection", 4, "feather_falling", 4, "unbreaking", 3, "mending", 1
      ),
      new AuctionEngine.MasterGearSpec(
         "turtle_helmet", "Shell of the Ocean King", 18000L, "protection", 4, "respiration", 3, "aqua_affinity", 1, "unbreaking", 3, "mending", 1
      )
   );
   private static final List<AuctionEngine.SuperItemSpec> SUPER_ITEM_SPECS = List.of(
      new AuctionEngine.SuperItemSpec(
         "netherite_pickaxe", "Ω World Shredder (Pickaxe)", 10000000L, 0.9, 1.4, 720, "efficiency", 15, "fortune", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "netherite_sword",
         "Ω Void Reaver (Sword)",
         15000000L,
         0.9,
         1.4,
         720,
         "sharpness",
         15,
         "looting",
         15,
         "sweeping_edge",
         15,
         "unbreaking",
         15,
         "mending",
         15,
         "fire_aspect",
         10
      ),
      new AuctionEngine.SuperItemSpec(
         "netherite_axe", "Ω Titan's Cleaver (Axe)", 12000000L, 0.9, 1.4, 720, "sharpness", 15, "efficiency", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "netherite_shovel", "Ω Excavator Prime (Shovel)", 8000000L, 0.9, 1.4, 600, "efficiency", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec("netherite_hoe", "Ω God Harvester (Hoe)", 8000000L, 0.9, 1.4, 600, "efficiency", 15, "unbreaking", 15, "mending", 15),
      new AuctionEngine.SuperItemSpec(
         "netherite_helmet", "Ω Aegis of Gods (Helmet)", 20000000L, 0.9, 1.4, 600, "protection", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "netherite_chestplate", "Ω Immortal's Plate (Chestplate)", 25000000L, 0.9, 1.4, 600, "protection", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "netherite_leggings", "Ω Shadow Greaves (Leggings)", 22000000L, 0.9, 1.4, 600, "protection", 15, "swift_sneak", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "netherite_boots",
         "Ω Strider of Worlds (Boots)",
         18000000L,
         0.9,
         1.4,
         600,
         "protection",
         15,
         "feather_falling",
         15,
         "depth_strider",
         15,
         "unbreaking",
         15,
         "mending",
         15
      ),
      new AuctionEngine.SuperItemSpec(
         "bow", "Ω Apollo's Bow (Bow)", 30000000L, 0.9, 1.4, 720, "power", 15, "punch", 15, "flame", 10, "infinity", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "crossbow",
         "Ω Siege Repeater (Crossbow)",
         25000000L,
         0.9,
         1.4,
         720,
         "quick_charge",
         15,
         "piercing",
         15,
         "multishot",
         15,
         "unbreaking",
         15,
         "mending",
         15
      ),
      new AuctionEngine.SuperItemSpec(
         "mace", "Ω Thunder's Wrath (Mace)", 35000000L, 0.9, 1.4, 720, "density", 15, "breach", 15, "wind_burst", 15, "unbreaking", 15, "mending", 15
      ),
      new AuctionEngine.SuperItemSpec(
         "trident",
         "Ω Poseidon's Fury (Trident)",
         28000000L,
         0.9,
         1.4,
         720,
         "channeling",
         10,
         "riptide",
         15,
         "loyalty",
         15,
         "impaling",
         15,
         "unbreaking",
         15,
         "mending",
         15
      )
   );
   private static final List<AuctionEngine.SuperBookSpec> SUPER_BOOK_SPECS = List.of(
      new AuctionEngine.SuperBookSpec("sharpness", "Sharpness XV", 15, 50000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("protection", "Protection XV", 15, 60000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("efficiency", "Efficiency XV", 15, 40000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("fortune", "Fortune XV", 15, 70000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("unbreaking", "Unbreaking XV", 15, 35000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("mending", "Mending XV", 15, 80000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("looting", "Looting XV", 15, 55000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("power", "Power XV", 15, 65000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("density", "Density XV", 15, 75000000L, 0.85, 1.45, 480),
      new AuctionEngine.SuperBookSpec("breach", "Breach XV", 15, 70000000L, 0.85, 1.45, 480)
   );

   private AuctionEngine() {
   }

   public static synchronized void ensureInitialized(MinecraftServer server) {
      if (server != null) {
         if (currentServer != server) {
            currentServer = server;
            initialized = false;
            discountCandidates = null;
         }

         if (!initialized) {
            initialized = true;
            Auctions.ensureLoaded(server);
            long started = System.currentTimeMillis();
            Random random = new Random(System.nanoTime());
            int created = 0;
            long now = System.currentTimeMillis();

            for (int slot = 0; slot < 15; slot++) {
               if (Auctions.findEngine("UNIQUE", slot) == null) {
                  AuctionEngine.UniqueSpec spec = UNIQUE_SPECS.get(slot);
                  AuctionEngine.GeneratedLot generated = makeUnique(spec, random, server, false);
                  if (generated != null && create("UNIQUE", slot, generated, server)) {
                     created++;
                  }
               }
            }

            for (int slotx = 0; slotx < 12; slotx++) {
               if (Auctions.findEngine("BOOK", slotx) == null) {
                  AuctionEngine.BookSpec spec = BOOK_SPECS.get(slotx % BOOK_SPECS.size());
                  AuctionEngine.GeneratedLot generated = makeBook(spec, random, server);
                  if (generated != null && create("BOOK", slotx, generated, server)) {
                     created++;
                  }
               }
            }

            for (int slotxx = 0; slotxx < 50; slotxx++) {
               if (Auctions.findEngine("GEAR_BOX", slotxx) == null) {
                  boolean startActive = slotxx < 12;
                  AuctionEngine.GeneratedLot generated = makeGearBox(slotxx, random, server, startActive, now + minutes(15 + (slotxx - 12) * 6));
                  if (generated != null && create("GEAR_BOX", slotxx, generated, server)) {
                     created++;
                  }
               }
            }

            for (int slotxxx = 0; slotxxx < 24; slotxxx++) {
               if (Auctions.findEngine("DISCOUNT", slotxxx) == null) {
                  boolean startActive = slotxxx < 12;
                  AuctionEngine.GeneratedLot generated = makeDiscount(random, startActive, now + minutes(10 + slotxxx * 5));
                  if (generated != null && create("DISCOUNT", slotxxx, generated, server)) {
                     created++;
                  }
               }
            }

            for (int slotxxxx = 0; slotxxxx < 24; slotxxxx++) {
               if (Auctions.findEngine("ENCHANTED_GEAR", slotxxxx) == null) {
                  boolean startActive = slotxxxx < 10;
                  AuctionEngine.GeneratedLot generated = makeEnchantedGearLot(slotxxxx, random, server, startActive, now + minutes(15 + slotxxxx * 6));
                  if (generated != null && create("ENCHANTED_GEAR", slotxxxx, generated, server)) {
                     created++;
                  }
               }
            }

            for (int slotxxxxx = 0; slotxxxxx < 8; slotxxxxx++) {
               if (Auctions.findEngine("SUPER_ITEM", slotxxxxx) == null) {
                  boolean startActive = slotxxxxx < 2;
                  AuctionEngine.GeneratedLot generated = makeSuperItem(slotxxxxx, random, server, startActive, now + minutes(30 + slotxxxxx * 10));
                  if (generated != null && create("SUPER_ITEM", slotxxxxx, generated, server)) {
                     created++;
                  }
               }
            }

            if (created > 0) {
               Auctions.save();
            }

            LOGGER.info("Auction Management Engine initialized: {} lot(s) managed in {} ms.", created, System.currentTimeMillis() - started);
         }
      }
   }

   private static boolean create(String kind, int slot, AuctionEngine.GeneratedLot generated, MinecraftServer server) {
      return generated == null
         ? false
         : Auctions.addEngine(
               kind,
               slot,
               generated.displayName(),
               generated.basePrice(),
               generated.price(),
               generated.stock(),
               generated.maxStock(),
               generated.restockAt(),
               generated.expiresAt(),
               generated.nextPriceChange(),
               generated.stack(),
               server
            )
            != null;
   }

   public static void tick(MinecraftServer server) {
      if (server != null) {
         long now = System.currentTimeMillis();
         if (!initialized) {
            ensureInitialized(server);
            lastUpdate = now;
         } else if (now - lastUpdate >= 10000L) {
            lastUpdate = now;

            try {
               boolean changed = false;
               Random random = new Random(System.nanoTime());

               for (Auctions.Lot lot : Auctions.engineLots()) {
                  if (lot.stock > 0 && lot.expiresAt > 0L && now >= lot.expiresAt) {
                     if ("GEAR_BOX".equals(lot.kind)) {
                        lot.stock = 0;
                        lot.restockAt = now + minutes(restockMinutes(lot.kind));
                        lot.expiresAt = 0L;
                        changed = true;
                     } else {
                        AuctionEngine.GeneratedLot generated = generate(lot.kind, lot.slot, random, server);
                        if (generated != null) {
                           Auctions.updateEngineLot(
                              lot,
                              generated.stack(),
                              generated.displayName(),
                              generated.basePrice(),
                              generated.price(),
                              generated.stock(),
                              generated.maxStock(),
                              generated.restockAt(),
                              generated.expiresAt(),
                              generated.nextPriceChange(),
                              server
                           );
                           changed = true;
                        }
                     }
                  } else if (lot.stock <= 0 && lot.restockAt > 0L && now >= lot.restockAt) {
                     AuctionEngine.GeneratedLot generated = generate(lot.kind, lot.slot, random, server);
                     if (generated != null) {
                        Auctions.updateEngineLot(
                           lot,
                           generated.stack(),
                           generated.displayName(),
                           generated.basePrice(),
                           generated.price(),
                           generated.stock(),
                           generated.maxStock(),
                           generated.restockAt(),
                           generated.expiresAt(),
                           generated.nextPriceChange(),
                           server
                        );
                        changed = true;
                     }
                  } else if (lot.stock > 0 && lot.nextPriceChange > 0L && now >= lot.nextPriceChange) {
                     lot.price = adjustedPrice(lot, random);
                     lot.nextPriceChange = now + minutes(10 + random.nextInt(11));
                     changed = true;
                  } else if (lot.stock > 0 && lot.nextPriceChange == 0L) {
                     lot.nextPriceChange = now + minutes(10 + random.nextInt(11));
                     changed = true;
                  }
               }

               if (changed) {
                  Auctions.save();
               }
            } catch (Throwable var8) {
               LOGGER.error("Auction engine maintenance pass failed", var8);
            }
         }
      }
   }

   public static void onPurchased(Auctions.Lot lot) {
      if (lot != null && lot.engine) {
         if (lot.stock <= 0) {
            lot.restockAt = System.currentTimeMillis() + minutes(restockMinutes(lot.kind));
            lot.expiresAt = 0L;
         }

         Auctions.save();
      }
   }

   private static AuctionEngine.GeneratedLot generate(String kind, int slot, Random random, MinecraftServer server) {
      return switch (kind) {
         case "UNIQUE" -> {
            AuctionEngine.UniqueSpec spec = UNIQUE_SPECS.get(slot % UNIQUE_SPECS.size());
            yield makeUnique(spec, random, server, false);
         }
         case "BOOK" -> {
            int index = random.nextInt(BOOK_SPECS.size());
            yield makeBook(BOOK_SPECS.get(index), random, server);
         }
         case "DISCOUNT" -> makeDiscount(random, true, 0L);
         case "GEAR_BOX" -> makeGearBox(slot, random, server, true, 0L);
         case "ENCHANTED_GEAR" -> makeEnchantedGearLot(slot, random, server, true, 0L);
         case "SUPER_ITEM" -> random.nextBoolean() ? makeSuperItem(slot, random, server, true, 0L) : makeSuperBook(random, server, true, 0L);
         default -> null;
      };
   }

   private static AuctionEngine.GeneratedLot makeUnique(AuctionEngine.UniqueSpec spec, Random random, MinecraftServer server, boolean forceSpawn) {
      Item item = item(spec.itemId());
      if (item == null) {
         return null;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         if (spec.enchantsAndLevels().length > 0) {
            for (int i = 0; i < spec.enchantsAndLevels().length; i += 2) {
               String encId = spec.enchantsAndLevels()[i];
               int lvl = Integer.parseInt(spec.enchantsAndLevels()[i + 1]);
               enchant(server, stack, encId, lvl);
            }
         }

         double jitter = spec.minFactor() + random.nextDouble() * (spec.maxFactor() - spec.minFactor());
         long price = Math.round(spec.basePrice() * jitter);
         long now = System.currentTimeMillis();
         boolean spawn = forceSpawn || random.nextInt(100) < 8;
         if (!spawn) {
            long restockCooldown = now + minutes(spec.restockMinutes() + random.nextInt(180));
            return new AuctionEngine.GeneratedLot(stack, spec.displayName(), spec.basePrice(), price, 0, 1, restockCooldown, 0L, now + minutes(15L));
         } else {
            long duration = hours(8 + random.nextInt(9));
            int stock = !"nether_star".equals(spec.itemId()) && !"enchanted_golden_apple".equals(spec.itemId()) ? 1 : 1 + random.nextInt(2);
            return new AuctionEngine.GeneratedLot(
               stack, spec.displayName(), spec.basePrice(), price, stock, Math.max(1, stock), 0L, now + duration, now + minutes(10 + random.nextInt(11))
            );
         }
      }
   }

   private static AuctionEngine.GeneratedLot makeBook(AuctionEngine.BookSpec spec, Random random, MinecraftServer server) {
      Item item = item("enchanted_book");
      if (item == null) {
         return null;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         Holder<Enchantment> enchantment = enchantment(server, spec.enchantId());
         if (enchantment == null) {
            return null;
         } else {
            Mutable mutable = new Mutable(ItemEnchantments.EMPTY);
            mutable.set(enchantment, spec.level());
            stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
            int stock = 1 + random.nextInt(3);
            double jitter = spec.minFactor() + random.nextDouble() * (spec.maxFactor() - spec.minFactor());
            long price = Math.round(spec.basePrice() * jitter);
            long now = System.currentTimeMillis();
            long duration = hours(4 + random.nextInt(5));
            return new AuctionEngine.GeneratedLot(
               stack,
               "Enchanted Book (" + spec.displayName() + ")",
               spec.basePrice(),
               price,
               stock,
               stock,
               0L,
               now + duration,
               now + minutes(10 + random.nextInt(11))
            );
         }
      }
   }

   private static AuctionEngine.GeneratedLot makeDiscount(Random random, boolean active, long scheduledRestock) {
      List<EconomyData.ShopEntry> candidates = discountCandidates();
      if (candidates.isEmpty()) {
         return null;
      } else {
         EconomyData.ShopEntry entry = candidates.get(random.nextInt(candidates.size()));
         Item item = item(entry.id);
         if (item == null) {
            return null;
         } else {
            ItemStack stack = new ItemStack(item, 1);
            double discountFactor = 0.55 + random.nextDouble() * 0.25;
            long basePrice = Math.max(1L, Math.round(entry.buy * discountFactor));
            int stock;
            if (entry.buy >= 500L) {
               stock = 4 + random.nextInt(9);
            } else if (entry.buy >= 50L) {
               stock = 16 + random.nextInt(17);
            } else {
               stock = 32 + random.nextInt(33);
            }

            long now = System.currentTimeMillis();
            if (!active) {
               return new AuctionEngine.GeneratedLot(
                  stack, entry.name + " (Shop Deal)", basePrice, basePrice, 0, stock, scheduledRestock, 0L, now + minutes(15L)
               );
            } else {
               long duration = hours(2 + random.nextInt(3));
               return new AuctionEngine.GeneratedLot(
                  stack, entry.name + " (Shop Deal)", basePrice, basePrice, stock, stock, 0L, now + duration, now + minutes(10 + random.nextInt(11))
               );
            }
         }
      }
   }

   private static AuctionEngine.GeneratedLot makeGearBox(int slot, Random random, MinecraftServer server, boolean active, long scheduledRestock) {
      ItemStack box = GearBoxes.createBox(slot, random, server);
      if (box.isEmpty()) {
         return null;
      } else {
         GearBoxes.GearBoxSpec spec = GearBoxes.getSpec(slot);
         long basePrice = spec.basePrice();
         double jitter = 0.9 + random.nextDouble() * 0.18;
         long price = Math.round(basePrice * jitter);
         long now = System.currentTimeMillis();
         if (!active) {
            return new AuctionEngine.GeneratedLot(box, spec.name(), basePrice, price, 0, 1, scheduledRestock, 0L, now + minutes(15L));
         } else {
            long duration = hours(6 + random.nextInt(7));
            return new AuctionEngine.GeneratedLot(box, spec.name(), basePrice, price, 1, 1, 0L, now + duration, now + minutes(10 + random.nextInt(11)));
         }
      }
   }

   private static AuctionEngine.GeneratedLot makeEnchantedGearLot(int slot, Random random, MinecraftServer server, boolean active, long scheduledRestock) {
      AuctionEngine.MasterGearSpec spec = MASTER_GEAR.get(slot % MASTER_GEAR.size());
      Item item = item(spec.id());
      if (item == null) {
         return null;
      } else {
         ItemStack stack = new ItemStack(item, 1);

         for (int i = 0; i < spec.enchants().length; i += 2) {
            String enc = (String)spec.enchants()[i];
            int lvl = (Integer)spec.enchants()[i + 1];
            enchant(server, stack, enc, lvl);
         }

         Component title = Component.literal(spec.title()).withStyle(ChatFormatting.GOLD);
         stack.set(DataComponents.CUSTOM_NAME, title);
         long basePrice = spec.basePrice();
         double jitter = 0.88 + random.nextDouble() * 0.22;
         long price = Math.round(basePrice * jitter);
         long now = System.currentTimeMillis();
         if (!active) {
            return new AuctionEngine.GeneratedLot(stack, spec.title(), basePrice, price, 0, 1, scheduledRestock, 0L, now + minutes(15L));
         } else {
            long duration = hours(4 + random.nextInt(5));
            return new AuctionEngine.GeneratedLot(stack, spec.title(), basePrice, price, 1, 1, 0L, now + duration, now + minutes(10 + random.nextInt(11)));
         }
      }
   }

   private static AuctionEngine.GeneratedLot makeSuperItem(int slot, Random random, MinecraftServer server, boolean active, long scheduledRestock) {
      AuctionEngine.SuperItemSpec spec = SUPER_ITEM_SPECS.get(slot % SUPER_ITEM_SPECS.size());
      Item item = item(spec.itemId());
      if (item == null) {
         return null;
      } else {
         ItemStack stack = new ItemStack(item, 1);

         for (int i = 0; i < spec.enchantsAndLevels().length; i += 2) {
            String encId = (String)spec.enchantsAndLevels()[i];
            int lvl = (Integer)spec.enchantsAndLevels()[i + 1];
            enchant(server, stack, encId, lvl);
         }

         double jitter = spec.minFactor() + random.nextDouble() * (spec.maxFactor() - spec.minFactor());
         long price = Math.round(spec.basePrice() * jitter);
         long now = System.currentTimeMillis();
         boolean spawn = active || random.nextInt(100) < 15;
         if (!spawn) {
            long restockCooldown = now + minutes(spec.restockMinutes() + random.nextInt(240));
            return new AuctionEngine.GeneratedLot(stack, spec.displayName(), spec.basePrice(), price, 0, 1, restockCooldown, 0L, now + minutes(15L));
         } else {
            long duration = hours(6 + random.nextInt(7));
            return new AuctionEngine.GeneratedLot(
               stack, spec.displayName(), spec.basePrice(), price, 1, 1, 0L, now + duration, now + minutes(10 + random.nextInt(11))
            );
         }
      }
   }

   private static AuctionEngine.GeneratedLot makeSuperBook(Random random, MinecraftServer server, boolean active, long scheduledRestock) {
      AuctionEngine.SuperBookSpec spec = SUPER_BOOK_SPECS.get(random.nextInt(SUPER_BOOK_SPECS.size()));
      Item item = item("enchanted_book");
      if (item == null) {
         return null;
      } else {
         ItemStack stack = new ItemStack(item, 1);
         Holder<Enchantment> enchantment = enchantment(server, spec.enchantId());
         if (enchantment == null) {
            return null;
         } else {
            Mutable mutable = new Mutable(ItemEnchantments.EMPTY);
            mutable.set(enchantment, spec.level());
            stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
            double jitter = spec.minFactor() + random.nextDouble() * (spec.maxFactor() - spec.minFactor());
            long price = Math.round(spec.basePrice() * jitter);
            long now = System.currentTimeMillis();
            boolean spawn = active || random.nextInt(100) < 15;
            if (!spawn) {
               long restockCooldown = now + minutes(spec.restockMinutes() + random.nextInt(240));
               return new AuctionEngine.GeneratedLot(
                  stack, "Super Book (" + spec.displayName() + ")", spec.basePrice(), price, 0, 1, restockCooldown, 0L, now + minutes(15L)
               );
            } else {
               long duration = hours(4 + random.nextInt(5));
               return new AuctionEngine.GeneratedLot(
                  stack, "Super Book (" + spec.displayName() + ")", spec.basePrice(), price, 1, 1, 0L, now + duration, now + minutes(10 + random.nextInt(11))
               );
            }
         }
      }
   }

   private static void enchant(MinecraftServer server, ItemStack stack, String id, int level) {
      Holder<Enchantment> holder = enchantment(server, id);
      if (holder != null) {
         stack.enchant(holder, level);
      }
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

   private static long adjustedPrice(Auctions.Lot lot, Random random) {
      String kind = lot.kind;
      double minFactor;
      double maxFactor;
      double jitter;
      switch (kind) {
         case "UNIQUE":
            minFactor = 0.85;
            maxFactor = 1.35;
            jitter = 0.9 + random.nextDouble() * 0.25;
            break;
         case "BOOK":
            minFactor = 0.8;
            maxFactor = 1.35;
            jitter = 0.9 + random.nextDouble() * 0.25;
            break;
         case "GEAR_BOX":
            minFactor = 0.88;
            maxFactor = 1.18;
            jitter = 0.92 + random.nextDouble() * 0.16;
            break;
         case "DISCOUNT":
            minFactor = 0.75;
            maxFactor = 1.15;
            jitter = 0.85 + random.nextDouble() * 0.2;
            break;
         case "ENCHANTED_GEAR":
            minFactor = 0.85;
            maxFactor = 1.25;
            jitter = 0.9 + random.nextDouble() * 0.2;
            break;
         case "SUPER_ITEM":
            minFactor = 0.85;
            maxFactor = 1.45;
            jitter = 0.85 + random.nextDouble() * 0.3;
            break;
         default:
            minFactor = 0.9;
            maxFactor = 1.1;
            jitter = 0.95 + random.nextDouble() * 0.1;
      }

      long price = Math.round(lot.basePrice * jitter);
      long min = Math.round(lot.basePrice * minFactor);
      long max = Math.round(lot.basePrice * maxFactor);
      return Math.max(1L, Math.max(min, Math.min(max, price)));
   }

   private static List<EconomyData.ShopEntry> discountCandidates() {
      if (discountCandidates == null) {
         discountCandidates = EconomyData.all()
            .stream()
            .filter(entry -> entry.buyable && entry.buy >= 10L && !"creative".equals(entry.category) && !"auction".equals(entry.category))
            .toList();
      }

      return discountCandidates;
   }

   private static int restockMinutes(String kind) {
      ThreadLocalRandom random = ThreadLocalRandom.current();

      return switch (kind) {
         case "UNIQUE" -> 360 + random.nextInt(480);
         case "BOOK" -> 120 + random.nextInt(180);
         case "DISCOUNT" -> 30 + random.nextInt(60);
         case "GEAR_BOX" -> 120 + random.nextInt(240);
         case "ENCHANTED_GEAR" -> 60 + random.nextInt(120);
         case "SUPER_ITEM" -> 240 + random.nextInt(480);
         default -> 120;
      };
   }

   private static long minutes(long minutes) {
      return minutes * 60000L;
   }

   private static long hours(long hours) {
      return hours * 3600000L;
   }

   private static Item item(String id) {
      String full = id.contains(":") ? id : "minecraft:" + id;
      return (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse(full)).orElse(null);
   }

   public record BookSpec(String enchantId, String displayName, int level, long basePrice, double minFactor, double maxFactor, int restockMinutes) {
   }

   private record GeneratedLot(
      ItemStack stack, String displayName, long basePrice, long price, int stock, int maxStock, long restockAt, long expiresAt, long nextPriceChange
   ) {
   }

   private record MasterGearSpec(String id, String title, long basePrice, Object... enchants) {
   }

   public record SuperBookSpec(String enchantId, String displayName, int level, long basePrice, double minFactor, double maxFactor, int restockMinutes) {
   }

   public record SuperItemSpec(
      String itemId, String displayName, long basePrice, double minFactor, double maxFactor, int restockMinutes, Object... enchantsAndLevels
   ) {
   }

   public record UniqueSpec(
      String itemId, String displayName, long basePrice, double minFactor, double maxFactor, int restockMinutes, String... enchantsAndLevels
   ) {
   }
}
