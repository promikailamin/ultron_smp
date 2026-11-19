package minecraft.economy;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

public final class EconomyScoreboard {
   private static final String OBJECTIVE_NAME = "economy_balance";
   private static final Component DISPLAY_NAME = Component.literal("§6Balance");
   private static final EconomyScoreboard INSTANCE = new EconomyScoreboard();
   private final Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();

   public static EconomyScoreboard get() {
      return INSTANCE;
   }

   private EconomyScoreboard() {
   }

   public void toggle(ServerPlayer player) {
      UUID uuid = player.getUUID();
      if (this.visiblePlayers.contains(uuid)) {
         this.visiblePlayers.remove(uuid);
         this.hide(player);
         player.sendSystemMessage(Component.literal("§7Economy scoreboard §cdisabled§7."));
      } else {
         this.visiblePlayers.add(uuid);
         this.show(player);
         player.sendSystemMessage(Component.literal("§7Economy scoreboard §aenabled§7."));
      }
   }

   public boolean isVisible(ServerPlayer player) {
      return this.visiblePlayers.contains(player.getUUID());
   }

   public void show(ServerPlayer player) {
      Scoreboard scoreboard = player.level().getScoreboard();
      Objective objective = scoreboard.getObjective("economy_balance");
      if (objective == null) {
         objective = scoreboard.addObjective("economy_balance", ObjectiveCriteria.DUMMY, DISPLAY_NAME, RenderType.INTEGER, false, BlankFormat.INSTANCE);
      }

      player.connection.send(new ClientboundSetObjectivePacket(objective, 0));
      player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
      this.update(player);
   }

   public void hide(ServerPlayer player) {
      Scoreboard scoreboard = player.level().getScoreboard();
      Objective objective = scoreboard.getObjective("economy_balance");
      if (objective != null) {
         player.connection.send(new ClientboundSetObjectivePacket(objective, 1));
      }

      this.visiblePlayers.remove(player.getUUID());
   }

   public void update(ServerPlayer player) {
      if (this.isVisible(player)) {
         Scoreboard scoreboard = player.level().getScoreboard();
         Objective objective = scoreboard.getObjective("economy_balance");
         if (objective != null) {
            long balance = EconomyData.getBalance(player.getUUID());
            ScoreAccess scoreAccess = scoreboard.getOrCreatePlayerScore(player, objective);
            scoreAccess.set((int)Math.min(balance, 2147483647L));
            scoreAccess.display(Component.literal(EconomyData.formatMoney(balance)));
            ClientboundSetScorePacket packet = new ClientboundSetScorePacket(
               player.getScoreboardName(),
               "economy_balance",
               (int)Math.min(balance, 2147483647L),
               Optional.of(Component.literal(EconomyData.formatMoney(balance))),
               Optional.of(BlankFormat.INSTANCE)
            );
            player.connection.send(packet);
         }
      }
   }

   public void updateAll(MinecraftServer server) {
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         this.update(player);
      }
   }
}
