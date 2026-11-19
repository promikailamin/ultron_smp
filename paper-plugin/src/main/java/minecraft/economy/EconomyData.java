package minecraft.economy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EconomyData {
   public static final long STARTING_BALANCE = 1000L;
   private static final Logger LOGGER = LoggerFactory.getLogger("economy");
   private static final Map<UUID, Long> BALANCES = new LinkedHashMap<>();
   private static final Map<String, EconomyData.ShopEntry> ENTRIES = new LinkedHashMap<>();
   private static final Path CONFIG_DIR = determineConfigDir();
   private static MinecraftServer currentServer = null;
   private static Path activeDataDir = CONFIG_DIR;
   private static boolean loaded = false;

   public static synchronized void bindServer(MinecraftServer server) {
      if (server != null) {
         if (currentServer != server) {
            if (currentServer != null) {
               saveBalances();
            }

            currentServer = server;
            activeDataDir = determineDataDir(server);
            BALANCES.clear();
            loadBalances();
            LOGGER.info("Economy data bound to {} (singleplayer={})", activeDataDir, server.isSingleplayer());
         }
      }
   }

   public static Path determineDataDir(MinecraftServer server) {
      return CONFIG_DIR;
   }

   public static Path determineConfigDir() {
      return Path.of("config", "economy");
   }

   public static Path activeDataDir() {
      return activeDataDir;
   }

   public static Path balancesFile() {
      return activeDataDir.resolve("balances.properties");
   }

   private EconomyData() {
   }

   public static String formatMoney(long amount) {
      if (amount < 1000L) {
         return "$" + amount;
      } else if (amount < 1000000L) {
         return String.format("$%.1fK", amount / 1000.0);
      } else if (amount < 1000000000L) {
         return String.format("$%.1fM", amount / 1000000.0);
      } else {
         return amount < 1000000000000L ? String.format("$%.1fB", amount / 1.0E9) : String.format("$%.1fT", amount / 1.0E12);
      }
   }

   private static long[] applyPriceRules(String id, long buy, long sell, boolean buyable, String category) {
      String lower = id.toLowerCase(Locale.ROOT);
      if (isEasyDupeable(lower)) {
         return new long[]{buy, sell, buyable ? 1L : 0L};
      } else if (isVeryExpensive(lower)) {
         long multiplier = 100000L;
         return new long[]{buy * multiplier, sell * multiplier, buyable ? 1L : 0L};
      } else {
         long multiplier = 1000L;
         return new long[]{buy * multiplier, sell * multiplier, buyable ? 1L : 0L};
      }
   }

   private static boolean isEasyDupeable(String lower) {
      return lower.contains("carpet")
         || lower.contains("sand")
         || lower.contains("red_sand")
         || lower.contains("gravel")
         || lower.contains("cobblestone")
         || lower.contains("cobble_")
         || lower.contains("rail")
         || lower.equals("dirt")
         || lower.contains("dirt_path")
         || lower.contains("coarse_dirt")
         || lower.contains("podzol")
         || lower.contains("mycelium")
         || lower.endsWith("_log")
         || lower.endsWith("_wood")
         || lower.contains("stripped_")
         || lower.endsWith("_planks")
         || lower.endsWith("_leaves")
         || lower.endsWith("_sapling")
         || lower.contains("grass")
         || lower.contains("fern")
         || lower.contains("flower")
         || lower.contains("dandelion")
         || lower.contains("poppy")
         || lower.contains("orchid")
         || lower.contains("allium")
         || lower.contains("tulip")
         || lower.contains("bluet")
         || lower.contains("daisy")
         || lower.contains("rose")
         || lower.contains("peony")
         || lower.contains("lilac")
         || lower.contains("sunflower")
         || lower.contains("torchflower")
         || lower.contains("pitcher")
         || lower.contains("vine")
         || lower.contains("stem")
         || lower.contains("hyphae")
         || lower.contains("bamboo")
         || lower.contains("cactus")
         || lower.contains("sugar_cane")
         || lower.contains("kelp")
         || lower.contains("seagrass")
         || lower.contains("lily_pad")
         || lower.contains("mushroom")
         || lower.contains("spore")
         || lower.contains("root")
         || lower.equals("mud")
         || lower.contains("muddy")
         || lower.contains("shrub")
         || lower.equals("bush")
         || lower.contains("vines")
         || lower.equals("air")
         || lower.equals("cave_air")
         || lower.equals("void_air");
   }

   private static boolean isVeryExpensive(String lower) {
      return !lower.equals("diamond")
            && !lower.equals("emerald")
            && !lower.equals("gold_ingot")
            && !lower.equals("gold_nugget")
            && !lower.equals("iron_ingot")
            && !lower.equals("iron_nugget")
            && !lower.equals("copper_ingot")
            && !lower.equals("copper_nugget")
            && !lower.equals("raw_iron")
            && !lower.equals("raw_gold")
            && !lower.equals("raw_copper")
            && !lower.equals("amethyst_shard")
            && !lower.endsWith("_nugget")
            && (!lower.endsWith("_ingot") || lower.contains("netherite"))
         ? lower.contains("diamond")
            || lower.contains("emerald")
            || lower.contains("netherite")
            || lower.contains("ancient_debris")
            || lower.equals("elytra")
            || lower.contains("dragon_head")
            || lower.contains("player_head")
            || lower.contains("wither_skeleton")
            || lower.contains("wither_skull")
            || lower.contains("piglin_head")
            || lower.contains("creeper_head")
            || lower.contains("skeleton_skull")
            || lower.equals("nether_star")
            || lower.equals("beacon")
            || lower.equals("totem_of_undying")
            || lower.contains("enchanted_golden_apple")
            || lower.contains("god_apple")
            || lower.contains("spawner")
            || lower.equals("conduit")
            || lower.equals("heavy_core")
            || lower.contains("sniffer_egg")
         : false;
   }

   public static synchronized void load() {
      if (!loaded) {
         loaded = true;
         loadPrices();
         loadBalances();
         LOGGER.info("Loaded {} economy price entries ({} buyable).", ENTRIES.size(), ENTRIES.values().stream().filter(e -> e.buyable).count());
      }
   }

   private static void loadPrices() {
      try {
         label103: {
            try (InputStream in = EconomyData.class.getResourceAsStream("/prices.tsv")) {
               if (in != null) {
                  try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                     int lineNumber = 0;

                     String line;
                     while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (!line.isBlank() && !line.startsWith("#")) {
                           String[] parts = line.split("\t", -1);
                           if (parts.length < 8) {
                              LOGGER.warn("Skipping malformed prices.tsv line {}: {}", lineNumber, line);
                           } else {
                              try {
                                 String id = normalizeId(parts[0]);
                                 String name = parts[1];
                                 long buy = Long.parseLong(parts[2]);
                                 long sell = Long.parseLong(parts[3]);
                                 boolean obtainable = "yes".equalsIgnoreCase(parts[4]);
                                 boolean buyable = "yes".equalsIgnoreCase(parts[5]);
                                 String category = parts[6];
                                 String note = parts[7];
                                 if ("dragon_egg".equals(id)) {
                                    buyable = false;
                                    sell = 0L;
                                    buy = 0L;
                                 }

                                 long[] adjusted = applyPriceRules(id, buy, sell, buyable, category);
                                 buy = adjusted[0];
                                 sell = adjusted[1];
                                 buyable = adjusted[2] != 0L;
                                 ENTRIES.put(id, new EconomyData.ShopEntry(id, name, buy, sell, obtainable, buyable, category, note));
                              } catch (NumberFormatException var18) {
                                 LOGGER.warn("Skipping prices.tsv line {} with bad number: {}", lineNumber, line);
                              }
                           }
                        }
                     }
                     break label103;
                  }
               }

               LOGGER.error("prices.tsv was not found inside the mod jar.");
            }

            return;
         }
      } catch (IOException var21) {
         LOGGER.error("Failed to read prices.tsv", var21);
      }
   }

   private static void loadBalances() {
      Path file = balancesFile();
      if (!Files.exists(file)) {
         Path fallback = CONFIG_DIR.resolve("balances.properties");
         if (!Files.exists(fallback) || fallback.equals(file)) {
            return;
         }

         file = fallback;
      }

      Properties props = new Properties();

      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
         props.load(reader);
      } catch (IOException var10) {
         LOGGER.error("Failed to read {}", file, var10);
         return;
      }

      for (String key : props.stringPropertyNames()) {
         try {
            UUID uuid = UUID.fromString(key);
            long balance = Long.parseLong(props.getProperty(key));
            BALANCES.put(uuid, balance);
         } catch (IllegalArgumentException var8) {
            LOGGER.warn("Ignoring malformed balance entry: {}={}", key, props.getProperty(key));
         }
      }
   }

   public static synchronized void saveBalances() {
      try {
         Path file = balancesFile();
         Path parent = file.getParent();
         if (parent != null) {
            Files.createDirectories(parent);
         }

         Properties props = new Properties();

         for (Entry<UUID, Long> entry : BALANCES.entrySet()) {
            props.setProperty(entry.getKey().toString(), Long.toString(entry.getValue()));
         }

         try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "Economy player balances");
         }
      } catch (IOException var8) {
         LOGGER.error("Failed to save {}", balancesFile(), var8);
      }
   }

   public static synchronized long getBalance(UUID uuid) {
      load();
      return BALANCES.computeIfAbsent(uuid, ignored -> 1000L);
   }

   public static synchronized void setBalance(UUID uuid, long amount) {
      load();
      BALANCES.put(uuid, amount);
      saveBalances();
   }

   public static synchronized long fine(UUID uuid, long amount) {
      load();
      if (amount <= 0L) {
         return getBalance(uuid);
      } else {
         long balance = BALANCES.getOrDefault(uuid, 1000L);
         long newBalance = balance - amount;
         BALANCES.put(uuid, newBalance);
         saveBalances();
         return newBalance;
      }
   }

   public static synchronized boolean hasDebt(UUID uuid) {
      return getBalance(uuid) < 0L;
   }

   public static synchronized long getDebt(UUID uuid) {
      long bal = getBalance(uuid);
      return bal < 0L ? -bal : 0L;
   }

   public static synchronized boolean take(UUID uuid, long amount) {
      load();
      if (amount <= 0L) {
         return true;
      } else {
         long balance = BALANCES.getOrDefault(uuid, 1000L);
         if (balance < amount) {
            return false;
         } else {
            BALANCES.put(uuid, balance - amount);
            saveBalances();
            return true;
         }
      }
   }

   public static synchronized void add(UUID uuid, long amount) {
      load();
      if (amount > 0L) {
         long balance = BALANCES.getOrDefault(uuid, 1000L);
         BALANCES.put(uuid, balance + amount);
         saveBalances();
      }
   }

   public static synchronized EconomyData.ShopEntry get(String input) {
      load();
      return ENTRIES.get(normalizeId(input));
   }

   public static synchronized Collection<EconomyData.ShopEntry> all() {
      load();
      return Collections.unmodifiableCollection(ENTRIES.values());
   }

   public static String normalizeId(String input) {
      String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
      if (value.startsWith("minecraft:")) {
         value = value.substring("minecraft:".length());
      }

      return value;
   }

   public static Path configDir() {
      return activeDataDir;
   }

   public static final class ShopEntry {
      public final String id;
      public final String name;
      public final long buy;
      public final long sell;
      public final boolean obtainable;
      public final boolean buyable;
      public final String category;
      public final String note;

      public ShopEntry(String id, String name, long buy, long sell, boolean obtainable, boolean buyable, String category, String note) {
         this.id = id;
         this.name = name;
         this.buy = buy;
         this.sell = sell;
         this.obtainable = obtainable;
         this.buyable = buyable;
         this.category = category;
         this.note = note;
      }

      public String fullId() {
         return "minecraft:" + this.id;
      }

      public boolean sellable() {
         return this.sell > 0L;
      }
   }
}
