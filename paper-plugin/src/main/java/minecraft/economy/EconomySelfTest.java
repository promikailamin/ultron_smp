package minecraft.economy;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * In-server integration checks used by the GitHub Actions paper-plugin-test
 * workflow. It is only registered when -Deconomy.selftest=true is set.
 */
public final class EconomySelfTest {
   private static int passed;

   private EconomySelfTest() {
   }

   public static void run(MinecraftServer server) {
      passed = 0;

      try {
         testEconomyData();
         testPrices();
         testGearBoxes(server);
         testAuctions(server);
         testSellerAxe();
         testCommands(server);
         testGuis(server);
         EconomyMod.LOGGER.info("SELFTEST PASS ({} checks)", passed);
      } catch (Throwable throwable) {
         EconomyMod.LOGGER.error("SELFTEST FAIL after {} checks", passed, throwable);
         throw new RuntimeException("Economy self-test failed", throwable);
      }
   }

   private static void check(boolean condition, String description) {
      if (!condition) {
         throw new IllegalStateException("Failed check: " + description);
      }

      passed++;
   }

   private static void testEconomyData() {
      UUID uuid = UUID.randomUUID();
      EconomyData.setBalance(uuid, 5000L);
      check(EconomyData.getBalance(uuid) == 5000L, "setBalance");
      EconomyData.add(uuid, 2500L);
      check(EconomyData.getBalance(uuid) == 7500L, "add");
      check(EconomyData.take(uuid, 1000L), "take");
      check(EconomyData.getBalance(uuid) == 6500L, "take balance");
      check(!EconomyData.take(uuid, 999999L), "reject overdraft");
      EconomyData.fine(uuid, 500L);
      check(EconomyData.getBalance(uuid) == 6000L, "fine");
      EconomyData.fine(uuid, 10000L);
      check(EconomyData.hasDebt(uuid), "debt flag");
      check(EconomyData.getDebt(uuid) == 4000L, "debt amount");
      EconomyData.setBalance(uuid, 1000L);
      check("$1.0K".equals(EconomyData.formatMoney(1000L)), "formatMoney");
   }

   private static void testPrices() {
      check(!EconomyData.all().isEmpty(), "prices loaded");
      check(EconomyData.get("stone") != null, "stone price");
      check(EconomyData.get("minecraft:stone") != null, "normalise id");
      check(EconomyData.get("stone").sellable(), "stone sellable");
   }

   private static void testGearBoxes(MinecraftServer server) {
      for (int slot = 0; slot < GearBoxes.TOTAL_BOXES; slot++) {
         ItemStack box = GearBoxes.createBox(slot, new java.util.Random(slot), server);
         check(!box.isEmpty(), "gear box " + slot + " created");
         check(box.get(DataComponents.CONTAINER) instanceof ItemContainerContents, "gear box " + slot + " contents");
      }
   }

   private static void testAuctions(MinecraftServer server) {
      UUID seller = UUID.randomUUID();
      ItemStack stack = new ItemStack(Items.DIAMOND, 3);
      Auctions.Lot lot = Auctions.addPlayer(seller, "EcoTestBot", 100L, stack, server);
      check(lot != null, "auction created");
      check(Auctions.get(lot.id) != null, "auction readable");
      check(Auctions.remove(lot.id) != null, "auction removed");
      AuctionEngine.tick(server);
      check(true, "auction engine tick");
   }

   private static void testSellerAxe() {
      ItemStack axe = new ItemStack(Items.IRON_AXE);
      check(!SellerAxe.isSellerAxe(axe), "plain axe is not a seller axe");
      axe.set(DataComponents.CUSTOM_NAME, Component.literal("§6Seller Axe"));
      check(SellerAxe.isSellerAxe(axe), "named seller axe");
   }

   private static void testCommands(MinecraftServer server) {
      var dispatcher = server.getCommands().getDispatcher();
      for (String command : new String[]{"bal", "pay", "fine", "shop", "buy", "sell", "ah", "eco", "ecosb"}) {
         check(dispatcher.getRoot().getChild(command) != null, "command /" + command + " registered");
      }
   }

   private static void testGuis(MinecraftServer server) throws Exception {
      ServerLevel level = server.overworld();
      GameProfile profile = new GameProfile(UUID.randomUUID(), "EcoTestBot");
      ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
      EmbeddedChannel channel = new EmbeddedChannel();
      Connection connection = new Connection(PacketFlow.SERVERBOUND);
      connection.channel = channel;
      connection.configurePacketHandler(channel.pipeline());
      player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));

      try {
         BuyGui.open(player, "blocks", 1);
         check(player.containerMenu instanceof EconomyGui.EcoMenu, "buy GUI opens");
         player.containerMenu.clicked(0, 0, ContainerInput.PICKUP, player);
         check(player.containerMenu instanceof EconomyGui.EcoMenu, "buy GUI click");

         ShopGui.openMainMenu(player);
         check(player.containerMenu instanceof EconomyGui.EcoMenu, "shop GUI opens");
         player.containerMenu.clicked(4, 0, ContainerInput.PICKUP, player);
         check(player.containerMenu instanceof EconomyGui.EcoMenu, "shop GUI click");

         SellGui.open(player);
         check(player.containerMenu instanceof SellGui.SellBinMenu, "sell GUI opens");
         player.containerMenu.clicked(50, 0, ContainerInput.PICKUP, player);
         check(player.containerMenu instanceof SellGui.SellBinMenu, "sell GUI click");

         AuctionGui.openAuctionHouse(player, "", 1);
         check(player.containerMenu instanceof EconomyGui.EcoMenu, "auction GUI opens");

         EconomyScoreboard.get().toggle(player);
         check(EconomyScoreboard.get().isVisible(player), "scoreboard show");
         EconomyScoreboard.get().toggle(player);
         check(!EconomyScoreboard.get().isVisible(player), "scoreboard hide");
         check(!channel.outboundMessages().isEmpty(), "packets sent to fake client");

         player.getInventory().clearContent();
         EconomyData.setBalance(player.getUUID(), 100000L);
         var source = player.createCommandSourceStack();
         server.getCommands().getDispatcher().execute("bal", source);
         passed++;
         int buyResult = server.getCommands().getDispatcher().execute("buy stone 2", source);
         check(buyResult > 0, "buy command result");
         check(player.getInventory().contains(stack -> stack.is(Items.STONE)), "bought stone in inventory");
         long afterBuy = EconomyData.getBalance(player.getUUID());
         check(afterBuy < 100000L, "buy charged balance");
         int sellResult = server.getCommands().getDispatcher().execute("sell stone 1", source);
         check(sellResult > 0, "sell command result");
         check(EconomyData.getBalance(player.getUUID()) > afterBuy, "sell credited balance");
         server.getCommands().getDispatcher().execute("ah", source);
      } finally {
         try {
            player.closeContainer();
         } catch (Throwable ignored) {
         }

         try {
            channel.close();
         } catch (Throwable ignored) {
         }
      }
   }
}
