package minecraft.economy;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Auctions {
   public static final UUID ENGINE_SELLER = new UUID(0L, 0L);
   private static final Logger LOGGER = LoggerFactory.getLogger("economy");
   private static MinecraftServer currentServer = null;
   private static Path activeFile = EconomyData.configDir().resolve("auctions.properties");
   private static final Pattern LOT_KEY = Pattern.compile("lot\\.(\\d+)\\.seller");
   private static final Map<Long, Auctions.Lot> LOTS = new LinkedHashMap<>();
   private static boolean loaded = false;
   private static long nextId = 1L;

   public static Path auctionsFile() {
      return activeFile != null ? activeFile : EconomyData.configDir().resolve("auctions.properties");
   }

   private Auctions() {
   }

   public static synchronized void ensureLoaded(MinecraftServer server) {
      if (server != null && currentServer != server) {
         if (currentServer != null) {
            save();
         }

         currentServer = server;
         EconomyData.bindServer(server);
         activeFile = EconomyData.activeDataDir().resolve("auctions.properties");
         LOTS.clear();
         loaded = false;
      }

      if (!loaded) {
         loaded = true;
         Path file = auctionsFile();
         if (!Files.exists(file)) {
            Path fallback = EconomyData.determineConfigDir().resolve("auctions.properties");
            if (!Files.exists(fallback) || fallback.equals(file)) {
               return;
            }

            file = fallback;
         }

         Properties props = new Properties();

         try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
         } catch (IOException var35) {
            LOGGER.error("Failed to read {}", file, var35);
            return;
         }

         try {
            nextId = Long.parseLong(props.getProperty("nextId", "1"));
         } catch (NumberFormatException var31) {
            nextId = 1L;
         }

         Iterator var37 = props.stringPropertyNames().iterator();

         while (true) {
            long id;
            String prefix;
            String itemText;
            UUID seller;
            while (true) {
               while (true) {
                  if (!var37.hasNext()) {
                     LOGGER.info("Loaded {} auction lot(s) ({} managed by the engine).", LOTS.size(), LOTS.values().stream().filter(lot -> lot.engine).count());
                     return;
                  }

                  String key = (String)var37.next();
                  Matcher matcher = LOT_KEY.matcher(key);
                  if (matcher.matches()) {
                     try {
                        id = Long.parseLong(matcher.group(1));
                        break;
                     } catch (NumberFormatException var33) {
                     }
                  }
               }

               prefix = "lot." + id + ".";
               String sellerText = props.getProperty(prefix + "seller");
               itemText = props.getProperty(prefix + "item");
               if (sellerText != null && itemText != null) {
                  try {
                     seller = UUID.fromString(sellerText);
                     break;
                  } catch (IllegalArgumentException var34) {
                     LOGGER.warn("Skipping malformed auction lot {}", id);
                  }
               }
            }

            String sellerName = props.getProperty(prefix + "sellerName", "Unknown");
            long price = parseLong(props.getProperty(prefix + "price"), 1L);
            boolean engine = Boolean.parseBoolean(props.getProperty(prefix + "engine", "false"));
            String kind = props.getProperty(prefix + "kind", engine ? "UNKNOWN" : "PLAYER");
            int slot = parseInt(props.getProperty(prefix + "slot"), -1);
            String displayName = props.getProperty(prefix + "displayName", "");
            long basePrice = parseLong(props.getProperty(prefix + "basePrice"), price);
            int stock = parseInt(props.getProperty(prefix + "stock"), engine ? 1 : 1);
            int maxStock = parseInt(props.getProperty(prefix + "maxStock"), stock);
            long restockAt = parseLong(props.getProperty(prefix + "restockAt"), 0L);
            long expiresAt = parseLong(props.getProperty(prefix + "expiresAt"), 0L);
            long nextPriceChange = parseLong(props.getProperty(prefix + "nextPriceChange"), 0L);
            ItemStack stack = decode(itemText, server);
            if (stack != null && !stack.isEmpty()) {
               LOTS.put(
                  id,
                  new Auctions.Lot(
                     id,
                     seller,
                     sellerName,
                     engine,
                     kind,
                     slot,
                     displayName,
                     price,
                     basePrice,
                     stock,
                     Math.max(1, maxStock),
                     restockAt,
                     expiresAt,
                     nextPriceChange,
                     stack,
                     itemText
                  )
               );
               nextId = Math.max(nextId, id + 1L);
            } else {
               LOGGER.warn("Skipping auction lot {} because its item could not be decoded", id);
            }
         }
      }
   }

   public static synchronized Auctions.Lot addPlayer(UUID seller, String sellerName, long price, ItemStack stack, MinecraftServer server) {
      ensureLoaded(server);
      if (!stack.isEmpty() && price > 0L) {
         String encoded = encode(stack, server);
         if (encoded == null) {
            return null;
         } else {
            long id = nextId++;
            Auctions.Lot lot = new Auctions.Lot(id, seller, sellerName, false, "PLAYER", -1, "", price, price, 1, 1, 0L, 0L, 0L, stack.copy(), encoded);
            LOTS.put(id, lot);
            save();
            return lot;
         }
      } else {
         return null;
      }
   }

   public static synchronized Auctions.Lot addEngine(
      String kind,
      int slot,
      String displayName,
      long basePrice,
      long price,
      int stock,
      int maxStock,
      long restockAt,
      long expiresAt,
      long nextPriceChange,
      ItemStack stack,
      MinecraftServer server
   ) {
      ensureLoaded(server);
      if (!stack.isEmpty() && price > 0L) {
         String encoded = encode(stack, server);
         if (encoded == null) {
            return null;
         } else {
            long id = nextId++;
            Auctions.Lot lot = new Auctions.Lot(
               id,
               ENGINE_SELLER,
               "Auction House",
               true,
               kind,
               slot,
               displayName,
               price,
               basePrice,
               Math.max(0, stock),
               Math.max(1, maxStock),
               restockAt,
               expiresAt,
               nextPriceChange,
               stack.copy(),
               encoded
            );
            LOTS.put(id, lot);
            return lot;
         }
      } else {
         return null;
      }
   }

   public static synchronized Auctions.Lot get(long id) {
      return LOTS.get(id);
   }

   public static synchronized List<Auctions.Lot> list() {
      List<Auctions.Lot> result = new ArrayList<>(LOTS.values());
      result.sort(Comparator.comparingLong(lot -> lot.id));
      return result;
   }

   public static synchronized List<Auctions.Lot> listActive() {
      return listActiveFiltered(null, null);
   }

   public static synchronized List<Auctions.Lot> listActiveFiltered(String categoryFilter, String queryFilter) {
      String cat = categoryFilter != null ? categoryFilter.trim().toLowerCase(Locale.ROOT) : "";
      if ("all".equals(cat) || "any".equals(cat)) {
         cat = "";
      } else if ("boxes".equals(cat) || "box".equals(cat) || "shulker".equals(cat) || "shulkers".equals(cat)) {
         cat = "gear";
      } else if ("deals".equals(cat) || "discount".equals(cat) || "shop".equals(cat)) {
         cat = "discounts";
      } else if ("book".equals(cat) || "enchant".equals(cat) || "enchants".equals(cat)) {
         cat = "books";
      } else if ("uniques".equals(cat) || "rare".equals(cat)) {
         cat = "unique";
      } else if ("armor".equals(cat) || "tools".equals(cat) || "weapons".equals(cat) || "gear_piece".equals(cat) || "equipment".equals(cat)) {
         cat = "equip";
      } else if ("super".equals(cat) || "illegal".equals(cat) || "god".equals(cat)) {
         cat = "super";
      }

      String query = queryFilter != null ? queryFilter.trim().toLowerCase(Locale.ROOT) : "";
      List<Auctions.Lot> result = new ArrayList<>();

      for (Auctions.Lot lot : LOTS.values()) {
         if (lot.active() && (cat.isEmpty() || cat.equalsIgnoreCase(lot.category()))) {
            if (!query.isEmpty()) {
               String name = lot.name().toLowerCase(Locale.ROOT);
               String seller = lot.sellerName.toLowerCase(Locale.ROOT);
               String idStr = "#" + lot.id;
               String itemKey = BuiltInRegistries.ITEM.getKey(lot.stack.getItem()).getPath().toLowerCase(Locale.ROOT);
               if (!name.contains(query) && !seller.contains(query) && !idStr.contains(query) && !itemKey.contains(query)) {
                  continue;
               }
            }

            result.add(lot);
         }
      }

      result.sort(Comparator.comparingLong(lotx -> lotx.id));
      return result;
   }

   public static synchronized List<Auctions.Lot> engineLots() {
      List<Auctions.Lot> result = new ArrayList<>();

      for (Auctions.Lot lot : LOTS.values()) {
         if (lot.engine) {
            result.add(lot);
         }
      }

      return result;
   }

   public static synchronized Auctions.Lot findEngine(String kind, int slot) {
      for (Auctions.Lot lot : LOTS.values()) {
         if (lot.engine && lot.kind.equals(kind) && lot.slot == slot) {
            return lot;
         }
      }

      return null;
   }

   public static synchronized Auctions.Lot remove(long id) {
      Auctions.Lot removed = LOTS.remove(id);
      if (removed != null) {
         save();
      }

      return removed;
   }

   public static synchronized void consume(Auctions.Lot lot, int amount) {
      if (lot.engine) {
         lot.stock = Math.max(0, lot.stock - amount);
      } else {
         lot.stock = 0;
      }

      save();
   }

   public static synchronized void setPrice(Auctions.Lot lot, long price) {
      lot.price = Math.max(1L, price);
      save();
   }

   public static synchronized void updateEngineLot(
      Auctions.Lot lot,
      ItemStack stack,
      String displayName,
      long basePrice,
      long price,
      int stock,
      int maxStock,
      long restockAt,
      long expiresAt,
      long nextPriceChange,
      MinecraftServer server
   ) {
      String encoded = encode(stack, server);
      if (encoded != null) {
         lot.stack = stack.copy();
         lot.encodedStack = encoded;
         lot.displayName = displayName == null ? "" : displayName;
         lot.price = Math.max(1L, price);
         lot.basePrice = Math.max(1L, basePrice);
         lot.stock = Math.max(0, stock);
         lot.maxStock = Math.max(1, maxStock);
         lot.restockAt = restockAt;
         lot.expiresAt = expiresAt;
         lot.nextPriceChange = nextPriceChange;
      }
   }

   public static synchronized void save() {
      try {
         Path file = auctionsFile();
         Path parent = file.getParent();
         if (parent != null) {
            Files.createDirectories(parent);
         }

         Properties props = new Properties();
         props.setProperty("nextId", Long.toString(nextId));

         for (Auctions.Lot lot : LOTS.values()) {
            String prefix = "lot." + lot.id + ".";
            props.setProperty(prefix + "seller", lot.seller.toString());
            props.setProperty(prefix + "sellerName", lot.sellerName);
            props.setProperty(prefix + "price", Long.toString(lot.price));
            props.setProperty(prefix + "basePrice", Long.toString(lot.basePrice));
            props.setProperty(prefix + "stock", Integer.toString(lot.stock));
            props.setProperty(prefix + "maxStock", Integer.toString(lot.maxStock));
            props.setProperty(prefix + "engine", Boolean.toString(lot.engine));
            props.setProperty(prefix + "kind", lot.kind);
            props.setProperty(prefix + "slot", Integer.toString(lot.slot));
            props.setProperty(prefix + "displayName", lot.displayName == null ? "" : lot.displayName);
            props.setProperty(prefix + "restockAt", Long.toString(lot.restockAt));
            props.setProperty(prefix + "expiresAt", Long.toString(lot.expiresAt));
            props.setProperty(prefix + "nextPriceChange", Long.toString(lot.nextPriceChange));
            props.setProperty(prefix + "item", lot.encodedStack);
         }

         try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "Economy auction house");
         }
      } catch (IOException var8) {
         LOGGER.error("Failed to save {}", auctionsFile(), var8);
      }
   }

   private static long parseLong(String text, long fallback) {
      if (text == null) {
         return fallback;
      } else {
         try {
            return Long.parseLong(text);
         } catch (NumberFormatException var4) {
            return fallback;
         }
      }
   }

   private static int parseInt(String text, int fallback) {
      if (text == null) {
         return fallback;
      } else {
         try {
            return Integer.parseInt(text);
         } catch (NumberFormatException var3) {
            return fallback;
         }
      }
   }

   public static String encode(ItemStack stack, MinecraftServer server) {
      try {
         RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
         DataResult<Tag> result = ItemStack.CODEC.encodeStart(ops, stack);
         Tag tag = (Tag)result.getOrThrow();
         return Base64.getEncoder().encodeToString(tag.toString().getBytes(StandardCharsets.UTF_8));
      } catch (RuntimeException var5) {
         LOGGER.error("Failed to encode auction item", var5);
         return null;
      }
   }

   private static ItemStack decode(String encoded, MinecraftServer server) {
      try {
         String snbt = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
         CompoundTag tag = TagParser.parseCompoundFully(snbt);
         RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
         return (ItemStack)ItemStack.CODEC.parse(ops, tag).getOrThrow();
      } catch (Exception var5) {
         LOGGER.warn("Failed to decode auction item", var5);
         return null;
      }
   }

   public static final class Lot {
      public final long id;
      public final UUID seller;
      public final String sellerName;
      public final boolean engine;
      public final String kind;
      public final int slot;
      public String displayName;
      public long price;
      public long basePrice;
      public int stock;
      public int maxStock;
      public long restockAt;
      public long expiresAt;
      public long nextPriceChange;
      public ItemStack stack;
      private String encodedStack;

      private Lot(
         long id,
         UUID seller,
         String sellerName,
         boolean engine,
         String kind,
         int slot,
         String displayName,
         long price,
         long basePrice,
         int stock,
         int maxStock,
         long restockAt,
         long expiresAt,
         long nextPriceChange,
         ItemStack stack,
         String encodedStack
      ) {
         this.id = id;
         this.seller = seller;
         this.sellerName = sellerName;
         this.engine = engine;
         this.kind = kind;
         this.slot = slot;
         this.displayName = displayName;
         this.price = price;
         this.basePrice = basePrice;
         this.stock = stock;
         this.maxStock = maxStock;
         this.restockAt = restockAt;
         this.expiresAt = expiresAt;
         this.nextPriceChange = nextPriceChange;
         this.stack = stack;
         this.encodedStack = encodedStack;
      }

      public String name() {
         return this.displayName != null && !this.displayName.isBlank() ? this.displayName : this.stack.getHoverName().getString();
      }

      public boolean active() {
         return this.stock > 0;
      }

      public String category() {
         if (!this.engine) {
            return "player";
         } else {
            String var1 = this.kind;

            return switch (var1) {
               case "UNIQUE" -> "unique";
               case "BOOK" -> "books";
               case "GEAR_BOX" -> "gear";
               case "DISCOUNT" -> "discounts";
               case "ENCHANTED_GEAR" -> "equip";
               case "SUPER_ITEM" -> "super";
               default -> "other";
            };
         }
      }

      public String priceTrend() {
         if (this.basePrice <= 0L) {
            return "Stable Market";
         } else {
            if ("DISCOUNT".equals(this.kind)) {
               String path = BuiltInRegistries.ITEM.getKey(this.stack.getItem()).getPath();
               EconomyData.ShopEntry shop = EconomyData.get(path);
               if (shop != null && shop.buy > 0L) {
                  long pct = Math.round((1.0 - (double)this.price / shop.buy) * 100.0);
                  if (pct > 0L) {
                     return pct + "% OFF Shop Price!";
                  }
               }
            }

            double diff = (double)(this.price - this.basePrice) / this.basePrice;
            long pct = Math.round(diff * 100.0);
            if (pct <= -8L) {
               return Math.abs(pct) + "% Market Dip";
            } else {
               return pct >= 8L ? "+" + pct + "% High Demand" : "Stable Market";
            }
         }
      }
   }
}
