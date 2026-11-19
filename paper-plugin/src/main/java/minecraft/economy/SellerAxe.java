package minecraft.economy;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class SellerAxe {
   private static final Map<Long, Long> LAST_SELL = new ConcurrentHashMap<>();

   private static long sellKey(UUID id, BlockPos pos) {
      long uuidPart = (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) & 4294967295L;
      return uuidPart << 32 ^ pos.asLong() & 4294967295L;
   }

   public static boolean isSellerAxe(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
         if (key != null && key.getPath().endsWith("_axe")) {
            Component customName = (Component)stack.get(DataComponents.CUSTOM_NAME);
            String name = "";
            if (customName != null) {
               name = ChatFormatting.stripFormatting(customName.getString());
            }

            if (name == null || name.isBlank()) {
               name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            }

            if (name == null) {
               return false;
            } else {
               name = name.trim().toLowerCase(Locale.ROOT);
               return name.equals("sell") || name.equals("seller axe") || name.equals("sell axe") || name.equals("seller") || name.contains("sell");
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static boolean handleChestBreak(ServerPlayer player, ServerLevel level, BlockPos pos) {
      if (player != null && level != null && pos != null) {
         ItemStack held = player.getMainHandItem();
         if (!isSellerAxe(held)) {
            return false;
         } else {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock chestBlock) {
               long var24 = System.currentTimeMillis();
               long sellCacheKey = sellKey(player.getUUID(), pos);
               Long last = LAST_SELL.get(sellCacheKey);
               if (last != null && var24 - last < 1500L) {
                  resyncChest(player, level, pos, state);
                  return true;
               } else {
                  LAST_SELL.put(sellCacheKey, var24);
                  EconomyData.bindServer(player.level().getServer());
                  Container container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
                  if (container == null) {
                     resyncChest(player, level, pos, state);
                     return true;
                  } else {
                     long totalEarned = 0L;
                     int totalSold = 0;
                     int totalItems = 0;

                     for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                           totalItems += stack.getCount();
                           Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                           String itemId = key != null ? key.getPath() : "";
                           EconomyData.ShopEntry entry = EconomyData.get(itemId);
                           if (entry != null && entry.sellable() && !"enchanted_book".equals(entry.id)) {
                              int count = stack.getCount();
                              long earned = entry.sell * count;
                              totalEarned += earned;
                              totalSold += count;
                              container.setItem(i, ItemStack.EMPTY);
                           }
                        }
                     }

                     container.setChanged();
                     if (totalEarned > 0L) {
                        EconomyData.add(player.getUUID(), totalEarned);
                        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                        level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1.0F, 1.2F);
                        long newBal = EconomyData.getBalance(player.getUUID());
                        if (newBal < 0L) {
                           long debt = -newBal;
                           player.sendSystemMessage(
                              Component.literal(
                                 "§6§l[Seller Axe] §a✔ Sold §f"
                                    + totalSold
                                    + " items§a from chest for §6+"
                                    + totalEarned
                                    + " §a (Paid toward loan)! Remaining Debt: §c"
                                    + EconomyData.formatMoney(debt)
                                    + "."
                              )
                           );
                        } else {
                           player.sendSystemMessage(
                              Component.literal(
                                 "§6§l[Seller Axe] §a✔ Sold §f"
                                    + totalSold
                                    + " items§a from chest for §6+"
                                    + totalEarned
                                    + " §a! New Balance: §e"
                                    + EconomyData.formatMoney(newBal)
                                    + "."
                              )
                           );
                        }
                     } else if (totalItems == 0) {
                        player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
                        player.sendSystemMessage(Component.literal("§6§l[Seller Axe] §cThis chest is completely empty!"));
                     } else {
                        player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
                        player.sendSystemMessage(Component.literal("§6§l[Seller Axe] §cNo sellable items found in this chest!"));
                     }

                     resyncChest(player, level, pos, state);
                     return true;
                  }
               }
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private static void resyncChest(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
      player.connection.send(new ClientboundBlockUpdatePacket(pos, state));

      try {
         if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherPos = ChestBlock.getConnectedBlockPos(pos, state);
            if (otherPos != null && !otherPos.equals(pos)) {
               player.connection.send(new ClientboundBlockUpdatePacket(otherPos, level.getBlockState(otherPos)));
            }
         }
      } catch (Throwable var5) {
      }

      BlockEntity be = level.getBlockEntity(pos);
      if (be != null && be.getUpdatePacket() != null) {
         player.connection.send(be.getUpdatePacket());
      }
   }

   private SellerAxe() {
   }
}
