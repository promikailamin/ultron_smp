package minecraft.economy;

import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EconomyMod extends JavaPlugin {
   public static final Logger LOGGER = LoggerFactory.getLogger("economy");
   private MinecraftServer server;

   @Override
   public void onEnable() {
      this.server = ((CraftServer)this.getServer()).getServer();
      EconomyData.bindServer(this.server);
      EconomyData.load();
      EconomyCommands.register(this.server.getCommands().getDispatcher());
      if (Boolean.getBoolean("economy.selftest")) {
         this.server.getCommands().getDispatcher().register(
            net.minecraft.commands.Commands.literal("ecoselftest").executes(context -> {
               EconomySelfTest.run(this.server);
               return 1;
            })
         );
      }

      this.getServer().getPluginManager().registerEvents(new EconomyListeners(), this);
      this.getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
      LOGGER.info("Economy plugin ready. Commands: /bal /pay /fine /shop /buy /sell /ah /eco /ecosb | Seller Axe ready");
   }

   @Override
   public void onDisable() {
      EconomyData.saveBalances();
      Auctions.save();
      LOGGER.info("Economy plugin disabled; balances and auctions saved.");
   }

   private void tick() {
      if (this.server == null) {
         return;
      }

      try {
         AuctionEngine.tick(this.server);
         EconomyLore.tick(this.server);
         EconomyScoreboard.get().updateAll(this.server);
      } catch (Throwable throwable) {
         LOGGER.error("Economy tick failed", throwable);
      }
   }
}
