package minecraft.economy;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

public final class GuiHistoryManager {
   public static final int MAX_HISTORY = 10;
   private static final GuiHistoryManager INSTANCE = new GuiHistoryManager();
   private final Map<UUID, ArrayDeque<GuiHistoryManager.GuiState>> history = new ConcurrentHashMap<>();

   public static GuiHistoryManager get() {
      return INSTANCE;
   }

   private GuiHistoryManager() {
   }

   public void push(ServerPlayer player, GuiHistoryManager.GuiState state) {
      this.history.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).addLast(state);
      ArrayDeque<GuiHistoryManager.GuiState> stack = this.history.get(player.getUUID());

      while (stack.size() > 10) {
         stack.removeFirst();
      }
   }

   public GuiHistoryManager.GuiState pop(ServerPlayer player) {
      ArrayDeque<GuiHistoryManager.GuiState> stack = this.history.get(player.getUUID());
      return stack != null && !stack.isEmpty() ? stack.removeLast() : null;
   }

   public boolean canGoBack(ServerPlayer player) {
      ArrayDeque<GuiHistoryManager.GuiState> stack = this.history.get(player.getUUID());
      return stack != null && !stack.isEmpty();
   }

   public void clear(ServerPlayer player) {
      this.history.remove(player.getUUID());
   }

   public static void restore(ServerPlayer player, GuiHistoryManager.GuiState state) {
      String var2 = state.type();
      switch (var2) {
         case "main":
            ShopGui.openMainMenu(player);
            break;
         case "category":
            ShopGui.openCategoryPage(player, state.param1(), state.param2());
            break;
         case "confirm_buy":
            ShopGui.confirmBuy(player, state.param1(), state.param2());
            break;
         case "buy_catalog":
            BuyGui.open(player, state.param1(), state.param2());
            break;
         case "buy_search":
            BuyGui.openSearch(player, state.param1());
            break;
         case "auction":
            AuctionGui.openAuctionHouse(player, state.param1(), state.param2());
      }
   }

   public record GuiState(String type, String param1, int param2, String param3) {
      public GuiState(String type) {
         this(type, null, 0, null);
      }

      public GuiState(String type, String param1, int param2) {
         this(type, param1, param2, null);
      }
   }
}
