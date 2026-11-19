package minecraft.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent.RunCommand;
import net.minecraft.network.chat.HoverEvent.ShowItem;
import net.minecraft.network.chat.HoverEvent.ShowText;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class EconomyCommands {
   private static final int SHOP_PAGE_SIZE = 10;
   private static final int AUCTION_PAGE_SIZE = 10;
   private static final int MAX_TRANSACTION_AMOUNT = 100000;
   private static final List<String> CATEGORY_ORDER = List.of(
      "all", "blocks", "tools", "armor", "food", "ores", "redstone", "nether", "end", "misc", "rare", "auction", "creative"
   );

   private EconomyCommands() {
   }

   private static void send(CommandSourceStack source, Supplier<Component> message, boolean broadcast) {
      source.sendSuccess(message, broadcast);
   }

   private static void send(CommandSourceStack source, Component message, boolean broadcast) {
      source.sendSuccess((Supplier<Component>)() -> message, broadcast);
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      if (dispatcher.getRoot().getChild("bal") == null) {
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("bal").executes(EconomyCommands::balanceSelf))
               .then(Commands.argument("player", EntityArgument.player()).executes(EconomyCommands::balanceOther))
         );
         dispatcher.register(
            (LiteralArgumentBuilder)Commands.literal("pay")
               .then(
                  Commands.argument("player", EntityArgument.player())
                     .then(
                        Commands.argument("amount", IntegerArgumentType.integer(1, 1000000000))
                           .executes(
                              ctx -> pay(
                                 (CommandSourceStack)ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), IntegerArgumentType.getInteger(ctx, "amount")
                              )
                           )
                     )
               )
         );
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("fine").requires(src -> isOperator(src, src.getPlayer())))
               .then(
                  Commands.argument("player", EntityArgument.player())
                     .then(
                        ((RequiredArgumentBuilder)Commands.argument("amount", IntegerArgumentType.integer(1, 1000000000))
                              .executes(
                                 ctx -> finePlayer(
                                    (CommandSourceStack)ctx.getSource(),
                                    EntityArgument.getPlayer(ctx, "player"),
                                    IntegerArgumentType.getInteger(ctx, "amount"),
                                    "Fine penalty"
                                 )
                              ))
                           .then(
                              Commands.argument("reason", StringArgumentType.greedyString())
                                 .executes(
                                    ctx -> finePlayer(
                                       (CommandSourceStack)ctx.getSource(),
                                       EntityArgument.getPlayer(ctx, "player"),
                                       IntegerArgumentType.getInteger(ctx, "amount"),
                                       StringArgumentType.getString(ctx, "reason")
                                    )
                                 )
                           )
                     )
               )
         );
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("shop").executes(ctx -> shopCategories((CommandSourceStack)ctx.getSource())))
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("category", StringArgumentType.word())
                        .suggests(EconomyCommands::suggestCategories)
                        .executes(ctx -> shopList((CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "category"), 1)))
                     .then(
                        Commands.argument("page", IntegerArgumentType.integer(1))
                           .executes(
                              ctx -> shopList(
                                 (CommandSourceStack)ctx.getSource(),
                                 StringArgumentType.getString(ctx, "category"),
                                 IntegerArgumentType.getInteger(ctx, "page")
                              )
                           )
                     )
               )
         );
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("buy")
                  .executes(
                     ctx -> {
                        ServerPlayer patt0$temp = ((CommandSourceStack)ctx.getSource()).getPlayer();
                        if (patt0$temp instanceof ServerPlayer) {
                           EconomyData.bindServer(((CommandSourceStack)ctx.getSource()).getServer());
                           BuyGui.open(patt0$temp, "all", 1);
                           return 1;
                        } else {
                           ((CommandSourceStack)ctx.getSource())
                              .sendFailure(Component.literal("Only players can open the buy catalog. Console usage: /buy <item> [amount]"));
                           return 0;
                        }
                     }
                  ))
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("item", StringArgumentType.string())
                        .suggests((ctx, builder) -> suggestItems(builder, true))
                        .executes(ctx -> {
                           ServerPlayer patt0$temp = ((CommandSourceStack)ctx.getSource()).getPlayer();
                           if (patt0$temp instanceof ServerPlayer) {
                              EconomyData.bindServer(((CommandSourceStack)ctx.getSource()).getServer());
                              String query = StringArgumentType.getString(ctx, "item");
                              BuyGui.openSearch(patt0$temp, query);
                              return 1;
                           } else {
                              ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Only players can use /buy <item>."));
                              return 0;
                           }
                        }))
                     .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000)).executes(ctx -> {
                        ServerPlayer patt0$temp = ((CommandSourceStack)ctx.getSource()).getPlayer();
                        if (patt0$temp instanceof ServerPlayer) {
                           EconomyData.bindServer(((CommandSourceStack)ctx.getSource()).getServer());
                           String query = StringArgumentType.getString(ctx, "item");
                           int amount = IntegerArgumentType.getInteger(ctx, "amount");
                           return buy((CommandSourceStack)ctx.getSource(), query, amount);
                        } else {
                           ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Only players can use /buy <item> [amount]."));
                           return 0;
                        }
                     }))
               )
         );
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("sell")
                  .executes(
                     ctx -> {
                        ServerPlayer patt0$temp = ((CommandSourceStack)ctx.getSource()).getPlayer();
                        if (patt0$temp instanceof ServerPlayer) {
                           EconomyData.bindServer(((CommandSourceStack)ctx.getSource()).getServer());
                           SellGui.open(patt0$temp);
                           return 1;
                        } else {
                           ((CommandSourceStack)ctx.getSource())
                              .sendFailure(Component.literal("Only players can open the sell chest. Console usage: /sell <item> [amount] or /sell hand"));
                           return 0;
                        }
                     }
                  ))
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("item", StringArgumentType.string())
                        .suggests((ctx, builder) -> suggestItems(builder, false))
                        .executes(ctx -> sell((CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "item"), 1)))
                     .then(
                        Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                           .executes(
                              ctx -> sell(
                                 (CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "item"), IntegerArgumentType.getInteger(ctx, "amount")
                              )
                           )
                     )
               )
         );
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                       "ah"
                                    )
                                    .executes(ctx -> auctionList((CommandSourceStack)ctx.getSource(), null, null, 1)))
                                 .then(
                                    Commands.literal("sell")
                                       .then(
                                          Commands.argument("price", IntegerArgumentType.integer(1, 1000000000))
                                             .executes(ctx -> auctionSell((CommandSourceStack)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "price")))
                                       )
                                 ))
                              .then(
                                 Commands.literal("buy")
                                    .then(
                                       ((RequiredArgumentBuilder)Commands.argument("id", IntegerArgumentType.integer(1))
                                             .executes(ctx -> auctionBuy((CommandSourceStack)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), 1)))
                                          .then(
                                             Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(
                                                   ctx -> auctionBuy(
                                                      (CommandSourceStack)ctx.getSource(),
                                                      IntegerArgumentType.getInteger(ctx, "id"),
                                                      IntegerArgumentType.getInteger(ctx, "amount")
                                                   )
                                                )
                                          )
                                    )
                              ))
                           .then(
                              Commands.literal("cancel")
                                 .then(
                                    Commands.argument("id", IntegerArgumentType.integer(1))
                                       .executes(ctx -> auctionCancel((CommandSourceStack)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))
                                 )
                           ))
                        .then(
                           Commands.literal("view")
                              .then(
                                 Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(ctx -> auctionView((CommandSourceStack)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))
                              )
                        ))
                     .then(
                        Commands.literal("info")
                           .then(
                              Commands.argument("id", IntegerArgumentType.integer(1))
                                 .executes(ctx -> auctionView((CommandSourceStack)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))
                           )
                     ))
                  .then(
                     Commands.literal("search")
                        .then(
                           ((RequiredArgumentBuilder)Commands.argument("query", StringArgumentType.string())
                                 .executes(ctx -> auctionList((CommandSourceStack)ctx.getSource(), null, StringArgumentType.getString(ctx, "query"), 1)))
                              .then(
                                 Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(
                                       ctx -> auctionList(
                                          (CommandSourceStack)ctx.getSource(),
                                          null,
                                          StringArgumentType.getString(ctx, "query"),
                                          IntegerArgumentType.getInteger(ctx, "page")
                                       )
                                    )
                              )
                        )
                  ))
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("filterOrPage", StringArgumentType.word())
                        .suggests(EconomyCommands::suggestAuctionCategories)
                        .executes(ctx -> {
                           String arg = StringArgumentType.getString(ctx, "filterOrPage");

                           try {
                              int page = Integer.parseInt(arg);
                              return auctionList((CommandSourceStack)ctx.getSource(), null, null, page);
                           } catch (NumberFormatException var3) {
                              return auctionList((CommandSourceStack)ctx.getSource(), arg, null, 1);
                           }
                        }))
                     .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> {
                        String cat = StringArgumentType.getString(ctx, "filterOrPage");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        return auctionList((CommandSourceStack)ctx.getSource(), cat, null, page);
                     }))
               )
         );
         dispatcher.register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("eco")
                           .requires(
                              src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                                 || src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                           ))
                        .then(
                           Commands.literal("give")
                              .then(
                                 Commands.argument("player", EntityArgument.player())
                                    .then(
                                       Commands.argument("amount", IntegerArgumentType.integer(1, 1000000000))
                                          .executes(
                                             ctx -> adminGive(
                                                (CommandSourceStack)ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount")
                                             )
                                          )
                                    )
                              )
                        ))
                     .then(
                        Commands.literal("take")
                           .then(
                              Commands.argument("player", EntityArgument.player())
                                 .then(
                                    Commands.argument("amount", IntegerArgumentType.integer(1, 1000000000))
                                       .executes(
                                          ctx -> adminTake(
                                             (CommandSourceStack)ctx.getSource(),
                                             EntityArgument.getPlayer(ctx, "player"),
                                             IntegerArgumentType.getInteger(ctx, "amount")
                                          )
                                       )
                                 )
                           )
                     ))
                  .then(
                     Commands.literal("set")
                        .then(
                           Commands.argument("player", EntityArgument.player())
                              .then(
                                 Commands.argument("amount", IntegerArgumentType.integer(0, 1000000000))
                                    .executes(
                                       ctx -> adminSet(
                                          (CommandSourceStack)ctx.getSource(),
                                          EntityArgument.getPlayer(ctx, "player"),
                                          IntegerArgumentType.getInteger(ctx, "amount")
                                       )
                                    )
                              )
                        )
                  ))
               .then(Commands.literal("reload").executes(ctx -> adminReload((CommandSourceStack)ctx.getSource())))
         );
         dispatcher.register((LiteralArgumentBuilder)Commands.literal("ecosb").executes(ctx -> {
            ServerPlayer patt0$temp = ((CommandSourceStack)ctx.getSource()).getPlayer();
            if (patt0$temp instanceof ServerPlayer) {
               EconomyScoreboard.get().toggle(patt0$temp);
               return 1;
            } else {
               ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Only players can toggle the economy scoreboard."));
               return 0;
            }
         }));
      }
   }

   private static int balanceSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      EconomyData.bindServer(((CommandSourceStack)ctx.getSource()).getServer());
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      sendBalance((CommandSourceStack)ctx.getSource(), player.getName().getString(), EconomyData.getBalance(player.getUUID()));
      return 1;
   }

   private static int balanceOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      EconomyData.bindServer(((CommandSourceStack)ctx.getSource()).getServer());
      ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      sendBalance((CommandSourceStack)ctx.getSource(), target.getName().getString(), EconomyData.getBalance(target.getUUID()));
      return 1;
   }

   public static boolean isOperator(CommandSourceStack source, ServerPlayer sender) {
      if (source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
         || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
         || source.permissions().hasPermission(Permissions.COMMANDS_OWNER)
         || source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
         return true;
      } else {
         if (source.getServer() != null && sender != null) {
            try {
               return source.getServer().getPlayerList().isOp(sender.nameAndId());
            } catch (Throwable var3) {
            }
         }

         return sender == null;
      }
   }

   private static int pay(CommandSourceStack source, ServerPlayer target, int amount) throws CommandSyntaxException {
      EconomyData.bindServer(source.getServer());
      ServerPlayer sender = source.getPlayer();
      boolean isOp = isOperator(source, sender);
      if (amount <= 0) {
         source.sendFailure(Component.literal("Amount must be greater than zero.").withStyle(ChatFormatting.RED));
         return 0;
      } else {
         boolean isSelf = sender != null && sender.getUUID().equals(target.getUUID());
         if (isSelf && !isOp) {
            source.sendFailure(Component.literal("You cannot pay yourself.").withStyle(ChatFormatting.RED));
            return 0;
         } else if (isOp) {
            EconomyData.add(target.getUUID(), amount);
            long targetNew = EconomyData.getBalance(target.getUUID());
            if (isSelf) {
               send(source, 
                  () -> Component.literal("Paid yourself ")
                     .withStyle(ChatFormatting.GRAY)
                     .append(Component.literal(EconomyData.formatMoney(amount)).withStyle(ChatFormatting.GOLD))
                     .append(Component.literal(" (Operator Infinite Pay). New Balance: ").withStyle(ChatFormatting.GRAY))
                     .append(Component.literal(EconomyData.formatMoney(targetNew)).withStyle(ChatFormatting.GOLD)),
                  false
               );
            } else {
               String senderName = sender != null ? sender.getName().getString() : "Server";
               send(source, 
                  () -> Component.literal("Sent ")
                     .withStyle(ChatFormatting.GRAY)
                     .append(Component.literal(EconomyData.formatMoney(amount)).withStyle(ChatFormatting.GOLD))
                     .append(Component.literal(" to ").withStyle(ChatFormatting.GRAY))
                     .append(Component.literal(target.getName().getString()).withStyle(ChatFormatting.AQUA))
                     .append(Component.literal(" (Operator Infinite Pay). Target Balance: ").withStyle(ChatFormatting.GRAY))
                     .append(Component.literal(EconomyData.formatMoney(targetNew)).withStyle(ChatFormatting.GOLD)),
                  false
               );
               target.sendSystemMessage(
                  Component.literal("Received ")
                     .withStyle(ChatFormatting.GRAY)
                     .append(Component.literal(EconomyData.formatMoney(amount)).withStyle(ChatFormatting.GOLD))
                     .append(Component.literal(" from ").withStyle(ChatFormatting.GRAY))
                     .append(Component.literal(senderName).withStyle(ChatFormatting.AQUA))
                     .append(Component.literal(". Balance: " + targetNew).withStyle(ChatFormatting.GRAY))
               );
            }

            return amount;
         } else if (sender == null) {
            source.sendFailure(Component.literal("Only players can pay normally.").withStyle(ChatFormatting.RED));
            return 0;
         } else {
            long balance = EconomyData.getBalance(sender.getUUID());
            if (balance < amount) {
               source.sendFailure(Component.literal("Not enough money. Need " + amount + ", have " + balance + ".").withStyle(ChatFormatting.RED));
               return 0;
            } else if (!EconomyData.take(sender.getUUID(), amount)) {
               source.sendFailure(Component.literal("Transaction failed.").withStyle(ChatFormatting.RED));
               return 0;
            } else {
               EconomyData.add(target.getUUID(), amount);
               EconomyScoreboard.get().update(sender);
               EconomyScoreboard.get().update(target);
               long senderNew = EconomyData.getBalance(sender.getUUID());
               long targetNew = EconomyData.getBalance(target.getUUID());
               send(source, 
                  () -> Component.literal("Sent ")
                     .withStyle(ChatFormatting.GRAY)
                     .append(Component.literal(EconomyData.formatMoney(amount)).withStyle(ChatFormatting.GOLD))
                     .append(Component.literal(" to ").withStyle(ChatFormatting.GRAY))
                     .append(Component.literal(target.getName().getString()).withStyle(ChatFormatting.AQUA))
                     .append(Component.literal(". Balance: " + senderNew).withStyle(ChatFormatting.GRAY)),
                  false
               );
               target.sendSystemMessage(
                  Component.literal("Received ")
                     .withStyle(ChatFormatting.GRAY)
                     .append(Component.literal(EconomyData.formatMoney(amount)).withStyle(ChatFormatting.GOLD))
                     .append(Component.literal(" from ").withStyle(ChatFormatting.GRAY))
                     .append(Component.literal(sender.getName().getString()).withStyle(ChatFormatting.AQUA))
                     .append(Component.literal(". Balance: " + targetNew).withStyle(ChatFormatting.GRAY))
               );
               return amount;
            }
         }
      }
   }

   private static int finePlayer(CommandSourceStack source, ServerPlayer target, int amount, String reason) {
      EconomyData.bindServer(source.getServer());
      if (amount <= 0) {
         source.sendFailure(Component.literal("Fine amount must be greater than zero.").withStyle(ChatFormatting.RED));
         return 0;
      } else {
         long oldBal = EconomyData.getBalance(target.getUUID());
         long newBal = EconomyData.fine(target.getUUID(), amount);
         EconomyScoreboard.get().update(target);
         String targetName = target.getName().getString();
         String senderName = source.getTextName();
         if (newBal < 0L) {
            long debt = -newBal;
            long seized = Math.max(0L, oldBal);
            send(source, 
               () -> Component.literal("Fined ")
                  .withStyle(ChatFormatting.GOLD)
                  .append(Component.literal(targetName).withStyle(ChatFormatting.AQUA))
                  .append(
                     Component.literal(
                           " "
                              + amount
                              + "$. Seized: "
                              + EconomyData.formatMoney(seized)
                              + ", New Debt/Loan: "
                              + EconomyData.formatMoney(debt)
                              + " (Balance: "
                              + EconomyData.formatMoney(newBal)
                              + "). Reason: "
                              + reason
                        )
                        .withStyle(ChatFormatting.RED)
                  ),
               true
            );
            target.sendSystemMessage(Component.literal("§c§l[FINANCE PENALTY] §7You were fined §6" + amount + " §7 by §e" + senderName + "§7."));
            target.sendSystemMessage(Component.literal("§cReason: §f" + reason));
            target.sendSystemMessage(
               Component.literal(
                  "§cAll your existing money was taken. You now have an active loan/debt of §4"
                     + EconomyData.formatMoney(debt)
                     + " §c (Balance: §c-"
                     + EconomyData.formatMoney(debt)
                     + "§c)."
               )
            );
            target.sendSystemMessage(Component.literal("§7Any money earned from sales, rewards, or payments will automatically go toward repaying this loan."));
         } else {
            send(source, 
               () -> Component.literal("Fined ")
                  .withStyle(ChatFormatting.GOLD)
                  .append(Component.literal(targetName).withStyle(ChatFormatting.AQUA))
                  .append(Component.literal(" " + amount + "$. New balance: " + newBal + "$. Reason: " + reason).withStyle(ChatFormatting.GREEN)),
               true
            );
            target.sendSystemMessage(Component.literal("§c§l[FINANCE PENALTY] §7You were fined §6" + amount + " §7 by §e" + senderName + "§7."));
            target.sendSystemMessage(Component.literal("§cReason: §f" + reason));
            target.sendSystemMessage(Component.literal("§7Remaining balance: §6" + newBal + " §7."));
         }

         return amount;
      }
   }

   private static int adminGive(CommandSourceStack source, ServerPlayer target, int amount) {
      EconomyData.bindServer(source.getServer());
      EconomyData.add(target.getUUID(), amount);
      EconomyScoreboard.get().update(target);
      long newBal = EconomyData.getBalance(target.getUUID());
      send(source, 
         () -> Component.literal("Gave " + amount + "  to " + target.getName().getString() + ". New balance: " + newBal).withStyle(ChatFormatting.GREEN), true
      );
      target.sendSystemMessage(Component.literal("You were given " + amount + "  by an admin. Balance: " + newBal).withStyle(ChatFormatting.GOLD));
      return 1;
   }

   private static int adminTake(CommandSourceStack source, ServerPlayer target, int amount) {
      EconomyData.bindServer(source.getServer());
      EconomyData.take(target.getUUID(), amount);
      long newBal = EconomyData.getBalance(target.getUUID());
      send(source, 
         () -> Component.literal("Took " + amount + "  from " + target.getName().getString() + ". New balance: " + newBal).withStyle(ChatFormatting.YELLOW),
         true
      );
      target.sendSystemMessage(Component.literal(amount + "  were taken by an admin. Balance: " + newBal).withStyle(ChatFormatting.RED));
      return 1;
   }

   private static int adminSet(CommandSourceStack source, ServerPlayer target, int amount) {
      EconomyData.bindServer(source.getServer());
      EconomyData.setBalance(target.getUUID(), amount);
      send(source, 
         () -> Component.literal("Set balance of " + target.getName().getString() + " to " + EconomyData.formatMoney(amount) + ".")
            .withStyle(ChatFormatting.GREEN),
         true
      );
      target.sendSystemMessage(Component.literal("Your balance was set to " + amount + "  by an admin.").withStyle(ChatFormatting.GOLD));
      return 1;
   }

   private static int adminReload(CommandSourceStack source) {
      EconomyData.bindServer(source.getServer());
      EconomyData.saveBalances();
      Auctions.save();
      Auctions.ensureLoaded(source.getServer());
      AuctionEngine.ensureInitialized(source.getServer());
      send(source, () -> Component.literal("Economy data and auction engine reloaded successfully.").withStyle(ChatFormatting.GREEN), true);
      return 1;
   }

   private static void sendBalance(CommandSourceStack source, String name, long balance) {
      if (balance < 0L) {
         long debt = -balance;
         Component message = Component.literal(name + "'s balance: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("-" + EconomyData.formatMoney(debt)).withStyle(new ChatFormatting[]{ChatFormatting.RED, ChatFormatting.BOLD}))
            .append(Component.literal(" (Active Loan/Debt: ").withStyle(ChatFormatting.DARK_RED))
            .append(Component.literal(EconomyData.formatMoney(debt)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(")").withStyle(ChatFormatting.DARK_RED));
         send(source, () -> message, false);
      } else {
         Component message = Component.literal(name + "'s balance: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(Long.toString(balance)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("").withStyle(ChatFormatting.GRAY));
         send(source, () -> message, false);
      }
   }

   private static int shopCategories(CommandSourceStack source) {
      ServerPlayer balance = source.getPlayer();
      if (balance instanceof ServerPlayer) {
         EconomyData.bindServer(source.getServer());
         ShopGui.openMainMenu(balance);
         return 1;
      } else {
         send(source, () -> Component.literal("=== Economy Shop ===").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
         Map<String, Integer> counts = new LinkedHashMap<>();

         for (String category : CATEGORY_ORDER) {
            counts.put(category, categoryCount(category));
         }

         for (String category : CATEGORY_ORDER) {
            int count = counts.getOrDefault(category, 0);
            if (count != 0 || category.equals("auction") || category.equals("creative")) {
               ChatFormatting color = categoryColor(category);
               MutableComponent line = Component.literal("[" + category + "]")
                  .withStyle(style -> style.withColor(color).withClickEvent(new RunCommand("/shop " + category + " 1")))
                  .append(Component.literal(" (" + count + " items)").withStyle(ChatFormatting.GRAY));
               send(source, () -> line, false);
            }
         }

         send(source, () -> Component.literal("Use /shop <category> [page] to browse, or /buy <item> [amount].").withStyle(ChatFormatting.GRAY), false);
         if (source.getPlayer() != null) {
            long balancex = EconomyData.getBalance(source.getPlayer().getUUID());
            send(source, () -> Component.literal("Your " + EconomyData.formatMoney(balancex)).withStyle(ChatFormatting.GOLD), false);
         }

         return 1;
      }
   }

   private static int shopList(CommandSourceStack source, String rawCategory, int requestedPage) {
      ServerPlayer items = source.getPlayer();
      if (items instanceof ServerPlayer) {
         EconomyData.bindServer(source.getServer());
         ShopGui.openCategoryPage(items, rawCategory.toLowerCase(Locale.ROOT), requestedPage);
         return 1;
      } else {
         String category = rawCategory.toLowerCase(Locale.ROOT);
         if (!CATEGORY_ORDER.contains(category)) {
            source.sendFailure(Component.literal("Unknown shop category: " + rawCategory).withStyle(ChatFormatting.RED));
            source.sendFailure(Component.literal("Categories: " + String.join(", ", CATEGORY_ORDER)).withStyle(ChatFormatting.GRAY));
            return 0;
         } else if ("auction".equals(category)) {
            send(source, 
               () -> Component.literal("=== Shop: auction ===").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false
            );
            send(source, 
               () -> Component.literal("Enchanted books, unique items, discounted shop items and gear boxes are traded in the live auction house.")
                  .withStyle(ChatFormatting.GRAY),
               false
            );
            send(source, () -> button("[Open Auction House]", "/ah", ChatFormatting.LIGHT_PURPLE), false);
            return 1;
         } else {
            List<EconomyData.ShopEntry> itemsx = new ArrayList<>();

            for (EconomyData.ShopEntry entry : EconomyData.all()) {
               if (matchesCategory(entry, category)) {
                  itemsx.add(entry);
               }
            }

            itemsx.sort(Comparator.comparing(entryx -> entryx.name.toLowerCase(Locale.ROOT)));
            int pages = Math.max(1, (itemsx.size() + 10 - 1) / 10);
            int page = Math.max(1, Math.min(requestedPage, pages));
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, itemsx.size());
            send(source, 
               () -> Component.literal("=== Shop: " + category + " (page " + page + "/" + pages + ", " + itemsx.size() + " items) ===")
                  .withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}),
               false
            );
            if (itemsx.isEmpty()) {
               send(source, () -> Component.literal("No items in this category.").withStyle(ChatFormatting.GRAY), false);
               return 1;
            } else {
               for (int i = start; i < end; i++) {
                  EconomyData.ShopEntry entryx = itemsx.get(i);
                  MutableComponent name = Component.literal(entryx.name)
                     .withStyle(style -> style.withColor(ChatFormatting.WHITE).withHoverEvent(shopHover(entryx)));
                  MutableComponent line = Component.literal("- ").withStyle(ChatFormatting.DARK_GRAY).append(name);
                  if (entryx.buyable) {
                     line.append(Component.literal("  buy " + EconomyData.formatMoney(entryx.buy)).withStyle(ChatFormatting.GOLD));
                  } else {
                     line.append(Component.literal("  not buyable").withStyle(ChatFormatting.DARK_GRAY));
                  }

                  line.append(Component.literal("  sell " + EconomyData.formatMoney(entryx.sell)).withStyle(ChatFormatting.YELLOW));
                  if (entryx.buyable) {
                     line.append(
                        Component.literal("  ")
                           .append(
                              button(
                                 "[Buy]",
                                 "/buy " + entryx.id + " 1",
                                 ChatFormatting.GREEN,
                                 Component.literal("Buy 1x " + entryx.name + " for " + EconomyData.formatMoney(entryx.buy) + ".")
                                    .withStyle(ChatFormatting.GREEN)
                              )
                           )
                     );
                  }

                  if (entryx.sellable() && !"enchanted_book".equals(entryx.id)) {
                     line.append(
                        Component.literal(" ")
                           .append(
                              button(
                                 "[Sell]",
                                 "/sell " + entryx.id + " 1",
                                 ChatFormatting.AQUA,
                                 Component.literal("Sell 1x " + entryx.name + " for " + EconomyData.formatMoney(entryx.sell) + ".")
                                    .withStyle(ChatFormatting.AQUA)
                              )
                           )
                     );
                  }

                  if (!entryx.note.isEmpty()) {
                     line.append(Component.literal("  (" + entryx.note + ")").withStyle(ChatFormatting.DARK_GRAY));
                  }

                  send(source, () -> line, false);
               }

               MutableComponent footer = Component.literal("Page " + page + "/" + pages + "  ");
               if (page > 1) {
                  footer.append(button("[<< Prev]", "/shop " + category + " " + (page - 1), ChatFormatting.GRAY));
               }

               if (page < pages) {
                  footer.append(Component.literal(" ")).append(button("[Next >>]", "/shop " + category + " " + (page + 1), ChatFormatting.GRAY));
               }

               send(source, () -> footer, false);
               if (source.getPlayer() != null) {
                  long balance = EconomyData.getBalance(source.getPlayer().getUUID());
                  send(source, () -> Component.literal("Your " + EconomyData.formatMoney(balance)).withStyle(ChatFormatting.GOLD), false);
               }

               return 1;
            }
         }
      }
   }

   private static int buy(CommandSourceStack source, String rawId, int amount) throws CommandSyntaxException {
      EconomyData.bindServer(source.getServer());
      ServerPlayer player = source.getPlayerOrException();
      EconomyData.ShopEntry entry = EconomyData.get(rawId);
      if (entry == null) {
         String query = rawId.toLowerCase(Locale.ROOT);
         List<EconomyData.ShopEntry> matches = EconomyData.all()
            .stream()
            .filter(e -> e.buyable)
            .filter(e -> e.id.contains(query) || e.name.toLowerCase(Locale.ROOT).contains(query))
            .sorted(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)))
            .toList();
         if (matches.isEmpty()) {
            source.sendFailure(Component.literal("Unknown item: " + rawId).withStyle(ChatFormatting.RED));
            return 0;
         }

         if (matches.size() > 1) {
            String suggestions = matches.stream().map(e -> e.id + " (" + e.name + ")").collect(Collectors.joining(", "));
            source.sendFailure(
               Component.literal(
                     "Ambiguous item: " + rawId + ". Matches: " + suggestions + ". Use the exact item id, e.g. /buy " + matches.get(0).id + " " + amount + "."
                  )
                  .withStyle(ChatFormatting.RED)
            );
            return 0;
         }

         entry = matches.get(0);
         String matchedName = entry.name;
         send(source, () -> Component.literal("Matched \"" + rawId + "\" to " + matchedName + ".").withStyle(ChatFormatting.GRAY), false);
      }

      if (!entry.buyable) {
         String reason = entry.note.isEmpty() ? "not buyable" : entry.note;
         source.sendFailure(Component.literal(entry.name + " is not buyable: " + reason).withStyle(ChatFormatting.RED));
         return 0;
      } else {
         long cost = entry.buy * amount;
         long balance = EconomyData.getBalance(player.getUUID());
         if (balance < cost) {
            source.sendFailure(
               Component.literal("Not enough money. Need " + EconomyData.formatMoney(cost) + ", have " + EconomyData.formatMoney(balance) + ".")
                  .withStyle(ChatFormatting.RED)
            );
            return 0;
         } else {
            Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.fullId())).orElse(null);
            if (item == null) {
               source.sendFailure(Component.literal("Item is not registered: " + entry.fullId()).withStyle(ChatFormatting.RED));
               return 0;
            } else {
               int maxStack = Math.max(1, item.getDefaultMaxStackSize());
               int requiredSlots = (amount + maxStack - 1) / maxStack;
               int freeSlots = countFreeSlots(player.getInventory());
               if (freeSlots < requiredSlots) {
                  source.sendFailure(
                     Component.literal("Not enough inventory space. Need " + requiredSlots + " free slot(s), have " + freeSlots + ".")
                        .withStyle(ChatFormatting.RED)
                  );
                  return 0;
               } else if (!EconomyData.take(player.getUUID(), cost)) {
                  source.sendFailure(Component.literal("Not enough money.").withStyle(ChatFormatting.RED));
                  return 0;
               } else {
                  int remaining = amount;
                  boolean success = true;

                  while (remaining > 0) {
                     int count = Math.min(remaining, maxStack);
                     ItemStack stack = new ItemStack(item, count);
                     if (!player.getInventory().add(stack)) {
                        success = false;
                        break;
                     }

                     remaining -= count;
                  }

                  if (!success) {
                     EconomyData.add(player.getUUID(), cost);
                     source.sendFailure(Component.literal("Could not add the item to your inventory.").withStyle(ChatFormatting.RED));
                     return 0;
                  } else {
                     long newBalance = EconomyData.getBalance(player.getUUID());
                     Component message = Component.literal("Bought ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(amount + "x " + entry.name).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" for ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(EconomyData.formatMoney(cost)).withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(". Balance: " + EconomyData.formatMoney(newBalance)).withStyle(ChatFormatting.GRAY));
                     send(source, () -> message, false);
                     return amount;
                  }
               }
            }
         }
      }
   }

   private static int sell(CommandSourceStack source, String rawId, int amount) throws CommandSyntaxException {
      EconomyData.bindServer(source.getServer());
      ServerPlayer player = source.getPlayerOrException();
      if ("hand".equalsIgnoreCase(rawId)) {
         ItemStack held = player.getInventory().getSelectedItem();
         if (held.isEmpty()) {
            source.sendFailure(Component.literal("You are not holding an item.").withStyle(ChatFormatting.RED));
            return 0;
         } else {
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
            EconomyData.ShopEntry entry = EconomyData.get(itemId);
            if (entry == null) {
               source.sendFailure(Component.literal("This item has no economy price.").withStyle(ChatFormatting.RED));
               return 0;
            } else if ("enchanted_book".equals(entry.id)) {
               source.sendFailure(Component.literal("Enchanted books can only be sold in the auction house: /ah sell <price>").withStyle(ChatFormatting.RED));
               return 0;
            } else if (!entry.sellable()) {
               source.sendFailure(Component.literal("This item cannot be sold.").withStyle(ChatFormatting.RED));
               return 0;
            } else {
               int sold = Math.min(amount, held.getCount());
               long earned = entry.sell * sold;
               held.shrink(sold);
               if (held.isEmpty()) {
                  player.getInventory().setSelectedItem(ItemStack.EMPTY);
               }

               EconomyData.add(player.getUUID(), earned);
               sendSold(source, player, sold, entry.name, earned);
               return sold;
            }
         }
      } else {
         EconomyData.ShopEntry entry = EconomyData.get(rawId);
         if (entry == null) {
            source.sendFailure(Component.literal("Unknown item: " + rawId).withStyle(ChatFormatting.RED));
            return 0;
         } else if ("enchanted_book".equals(entry.id)) {
            source.sendFailure(Component.literal("Enchanted books can only be sold in the auction house: /ah sell <price>").withStyle(ChatFormatting.RED));
            return 0;
         } else if (!entry.sellable()) {
            source.sendFailure(Component.literal(entry.name + " cannot be sold.").withStyle(ChatFormatting.RED));
            return 0;
         } else {
            Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.fullId())).orElse(null);
            if (item == null) {
               source.sendFailure(Component.literal("Item is not registered: " + entry.fullId()).withStyle(ChatFormatting.RED));
               return 0;
            } else {
               int available = countItem(player.getInventory(), item);
               if (available < amount) {
                  source.sendFailure(Component.literal("You only have " + available + "x " + entry.name + ".").withStyle(ChatFormatting.RED));
                  return 0;
               } else {
                  int remaining = amount;
                  Inventory inventory = player.getInventory();

                  for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                     ItemStack stack = inventory.getItem(slot);
                     if (!stack.isEmpty() && stack.getItem() == item) {
                        int take = Math.min(remaining, stack.getCount());
                        stack.shrink(take);
                        if (stack.isEmpty()) {
                           inventory.setItem(slot, ItemStack.EMPTY);
                        }

                        remaining -= take;
                     }
                  }

                  long earned = entry.sell * amount;
                  EconomyData.add(player.getUUID(), earned);
                  sendSold(source, player, amount, entry.name, earned);
                  return amount;
               }
            }
         }
      }
   }

   private static void sendSold(CommandSourceStack source, ServerPlayer player, int amount, String name, long earned) {
      long newBalance = EconomyData.getBalance(player.getUUID());
      Component message = Component.literal("Sold ")
         .withStyle(ChatFormatting.GRAY)
         .append(Component.literal(amount + "x " + name).withStyle(ChatFormatting.AQUA))
         .append(Component.literal(" for ").withStyle(ChatFormatting.GRAY))
         .append(Component.literal(EconomyData.formatMoney(earned)).withStyle(ChatFormatting.GOLD))
         .append(Component.literal(". Balance: " + EconomyData.formatMoney(newBalance)).withStyle(ChatFormatting.GRAY));
      send(source, () -> message, false);
   }

   private static int auctionList(CommandSourceStack source, String categoryFilter, String queryFilter, int requestedPage) {
      ServerPlayer lots = source.getPlayer();
      if (lots instanceof ServerPlayer) {
         EconomyData.bindServer(source.getServer());
         AuctionEngine.ensureInitialized(source.getServer());
         Auctions.ensureLoaded(source.getServer());
         AuctionGui.openAuctionHouse(lots, categoryFilter != null ? categoryFilter : "", requestedPage);
         return 1;
      } else {
         MinecraftServer server = source.getServer();
         AuctionEngine.ensureInitialized(server);
         Auctions.ensureLoaded(server);
         List<Auctions.Lot> lotsx = Auctions.listActiveFiltered(categoryFilter, queryFilter);
         String headerTitle = "=== Auction House";
         if (queryFilter != null && !queryFilter.isBlank()) {
            headerTitle = headerTitle + " (Search: \"" + queryFilter + "\")";
         } else if (categoryFilter != null && !categoryFilter.isBlank() && !"all".equalsIgnoreCase(categoryFilter)) {
            headerTitle = headerTitle + " [" + categoryFilter.toUpperCase(Locale.ROOT) + "]";
         }

         headerTitle = headerTitle + " ===";
         String emptyTitle = headerTitle;
         if (lotsx.isEmpty()) {
            send(source, () -> Component.literal(emptyTitle).withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
            send(source, () -> Component.literal("No active auctions found matching your filter.").withStyle(ChatFormatting.GRAY), false);
            send(source, 
               () -> Component.literal("Categories: unique, books, gear, discounts, equip, player. Or use /ah for all.").withStyle(ChatFormatting.DARK_GRAY),
               false
            );
            return 1;
         } else {
            int pages = Math.max(1, (lotsx.size() + 10 - 1) / 10);
            int page = Math.max(1, Math.min(requestedPage, pages));
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, lotsx.size());
            String fHeader2 = headerTitle.replace(" ===", "") + " (page " + page + "/" + pages + ", " + lotsx.size() + " active lots) ===";
            send(source, () -> Component.literal(fHeader2).withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
            ServerPlayer viewer = source.getPlayer();

            for (int i = start; i < end; i++) {
               Auctions.Lot lot = lotsx.get(i);
               ChatFormatting catColor = auctionCategoryColor(lot.category());
               String catBadge = "[" + lot.category().toUpperCase(Locale.ROOT) + "]";
               MutableComponent nameComponent = Component.literal(lot.name())
                  .withStyle(
                     style -> style.withColor(lot.engine ? ChatFormatting.WHITE : ChatFormatting.YELLOW)
                        .withHoverEvent(auctionHover(lot))
                        .withClickEvent(new RunCommand("/ah view " + lot.id))
                  );
               MutableComponent line = Component.literal("#" + lot.id + " ")
                  .withStyle(ChatFormatting.DARK_GRAY)
                  .append(Component.literal(catBadge + " ").withStyle(catColor))
                  .append(nameComponent);
               if (lot.stack.getCount() > 1) {
                  line.append(Component.literal(" x" + lot.stack.getCount()).withStyle(ChatFormatting.GRAY));
               }

               line.append(Component.literal(" - " + EconomyData.formatMoney(lot.price) + (lot.engine ? " each" : "")).withStyle(ChatFormatting.GOLD));
               if (lot.engine) {
                  line.append(Component.literal(" [stock: " + lot.stock + "]").withStyle(ChatFormatting.GREEN));
                  if (!"Stable Market".equals(lot.priceTrend())) {
                     line.append(Component.literal(" (" + lot.priceTrend() + ")").withStyle(ChatFormatting.DARK_AQUA));
                  }
               } else {
                  line.append(Component.literal(" (by " + lot.sellerName + ")").withStyle(ChatFormatting.GRAY));
               }

               line.append(Component.literal("  "))
                  .append(
                     button(
                        "[View]",
                        "/ah view " + lot.id,
                        ChatFormatting.AQUA,
                        Component.literal("Inspect full contents and enchantments of auction #" + lot.id).withStyle(ChatFormatting.AQUA)
                     )
                  );
               line.append(Component.literal(" ")).append(button("[Buy]", "/ah buy " + lot.id + " 1", ChatFormatting.GREEN, auctionBuyHover(lot, 1)));
               if (!lot.engine && viewer != null && viewer.getUUID().equals(lot.seller)) {
                  line.append(Component.literal(" "))
                     .append(
                        button(
                           "[Cancel]",
                           "/ah cancel " + lot.id,
                           ChatFormatting.RED,
                           Component.literal("Return this auction item to your inventory.").withStyle(ChatFormatting.RED)
                        )
                     );
               }

               send(source, () -> line, false);
            }

            String baseCmd = "/ah";
            if (queryFilter != null && !queryFilter.isBlank()) {
               baseCmd = "/ah search " + queryFilter;
            } else if (categoryFilter != null && !categoryFilter.isBlank() && !"all".equalsIgnoreCase(categoryFilter)) {
               baseCmd = "/ah " + categoryFilter;
            }

            MutableComponent footer = Component.literal("Page " + page + "/" + pages + "  ");
            if (page > 1) {
               footer.append(button("[<< Prev]", baseCmd + " " + (page - 1), ChatFormatting.GRAY));
            }

            if (page < pages) {
               footer.append(Component.literal(" ")).append(button("[Next >>]", baseCmd + " " + (page + 1), ChatFormatting.GRAY));
            }

            send(source, () -> footer, false);
            send(source, 
               () -> Component.literal("Hover item for summary, click [View] or item name for full 27-slot contents.").withStyle(ChatFormatting.DARK_GRAY),
               false
            );
            return 1;
         }
      }
   }

   private static int auctionView(CommandSourceStack source, int id) {
      ServerPlayer player = source.getPlayer();
      if (player instanceof ServerPlayer) {
         EconomyData.bindServer(source.getServer());
         AuctionGui.openLotView(player, id);
         return 1;
      } else {
         MinecraftServer server = source.getServer();
         AuctionEngine.ensureInitialized(server);
         Auctions.ensureLoaded(server);
         Auctions.Lot lotx = Auctions.get(id);
         if (lotx != null && lotx.active()) {
            send(source, 
               () -> Component.literal("=== Auction #" + lotx.id + ": " + lotx.name() + " ===")
                  .withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}),
               false
            );
            send(source, 
               () -> Component.literal("Seller: ")
                  .withStyle(ChatFormatting.GRAY)
                  .append(Component.literal(lotx.sellerName).withStyle(lotx.engine ? ChatFormatting.AQUA : ChatFormatting.WHITE))
                  .append(Component.literal("  |  Category: ").withStyle(ChatFormatting.GRAY))
                  .append(Component.literal(lotx.category().toUpperCase(Locale.ROOT)).withStyle(auctionCategoryColor(lotx.category()))),
               false
            );
            send(source, 
               () -> Component.literal("Price: ")
                  .withStyle(ChatFormatting.GRAY)
                  .append(Component.literal(EconomyData.formatMoney(lotx.price)).withStyle(ChatFormatting.GOLD))
                  .append(Component.literal(lotx.engine ? " each" : ""))
                  .append(Component.literal("  (" + lotx.priceTrend() + ")").withStyle(ChatFormatting.GREEN)),
               false
            );
            send(source, 
               () -> Component.literal("Stock: ")
                  .withStyle(ChatFormatting.GRAY)
                  .append(Component.literal(Integer.toString(lotx.stock)).withStyle(ChatFormatting.GREEN))
                  .append(Component.literal(lotx.expiresAt > 0L ? "  |  Expires in: " + formatDuration(lotx.expiresAt - System.currentTimeMillis()) : ""))
                  .withStyle(ChatFormatting.DARK_GRAY),
               false
            );
            ItemContainerContents container = (ItemContainerContents)lotx.stack.get(DataComponents.CONTAINER);
            if (container != null) {
               List<ItemStack> contents = container.nonEmptyItemCopyStream().toList();
               if (!contents.isEmpty()) {
                  send(source, 
                     () -> Component.literal("--- Shulker Box Contents (" + contents.size() + " items) ---")
                        .withStyle(new ChatFormatting[]{ChatFormatting.YELLOW, ChatFormatting.BOLD}),
                     false
                  );
                  int slotIndex = 1;

                  for (ItemStack item : contents) {
                     MutableComponent line = Component.literal(" " + slotIndex + ". ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(item.getCount() + "x ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(item.getHoverName().getString()).withStyle(ChatFormatting.WHITE));
                     ItemEnchantments enchants = item.getEnchantments();
                     if (!enchants.isEmpty()) {
                        List<String> encList = new ArrayList<>();

                        for (Entry<Holder<Enchantment>> encEntry : enchants.entrySet()) {
                           String encId = encEntry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                           encList.add(formatEnchantName(encId) + " " + toRoman(encEntry.getIntValue()));
                        }

                        line.append(Component.literal(" [" + String.join(", ", encList) + "]").withStyle(ChatFormatting.AQUA));
                     }

                     send(source, () -> line, false);
                     slotIndex++;
                  }
               }
            } else {
               ItemEnchantments enchants = lotx.stack.getEnchantments();
               if (!enchants.isEmpty()) {
                  send(source, () -> Component.literal("Enchantments:").withStyle(ChatFormatting.YELLOW), false);

                  for (Entry<Holder<Enchantment>> encEntry : enchants.entrySet()) {
                     String encId = encEntry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                     send(source, 
                        () -> Component.literal(" • " + formatEnchantName(encId) + " " + toRoman(encEntry.getIntValue())).withStyle(ChatFormatting.AQUA), false
                     );
                  }
               }

               ItemEnchantments stored = (ItemEnchantments)lotx.stack.get(DataComponents.STORED_ENCHANTMENTS);
               if (stored != null && !stored.isEmpty()) {
                  send(source, () -> Component.literal("Stored Enchantments:").withStyle(ChatFormatting.YELLOW), false);

                  for (Entry<Holder<Enchantment>> encEntry : stored.entrySet()) {
                     String encId = encEntry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                     send(source, 
                        () -> Component.literal(" • " + formatEnchantName(encId) + " " + toRoman(encEntry.getIntValue())).withStyle(ChatFormatting.AQUA), false
                     );
                  }
               }
            }

            MutableComponent actionLine = button(
               "[Buy 1]", "/ah buy " + lotx.id + " 1", ChatFormatting.GREEN, Component.literal("Buy 1x for " + EconomyData.formatMoney(lotx.price) + ".")
            );
            if (lotx.engine && lotx.stock > 1) {
               actionLine.append(Component.literal("  "))
                  .append(
                     button(
                        "[Buy All (" + lotx.stock + ")]",
                        "/ah buy " + lotx.id + " " + lotx.stock,
                        ChatFormatting.LIGHT_PURPLE,
                        Component.literal("Buy all " + lotx.stock + " for " + EconomyData.formatMoney(lotx.price * lotx.stock) + ".")
                     )
                  );
            }

            actionLine.append(Component.literal("  "))
               .append(button("[Back to /ah]", "/ah", ChatFormatting.GRAY, Component.literal("Return to auction listings.")));
            send(source, () -> actionLine, false);
            return 1;
         } else {
            source.sendFailure(Component.literal("Auction #" + id + " is not currently active.").withStyle(ChatFormatting.RED));
            return 0;
         }
      }
   }

   private static int auctionSell(CommandSourceStack source, int price) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      ItemStack held = player.getInventory().getSelectedItem();
      if (held.isEmpty()) {
         source.sendFailure(Component.literal("You are not holding an item. Hold the stack you want to auction.").withStyle(ChatFormatting.RED));
         return 0;
      } else if (price <= 0) {
         source.sendFailure(Component.literal("Price must be greater than zero.").withStyle(ChatFormatting.RED));
         return 0;
      } else {
         ItemStack stack = held.copy();
         player.getInventory().setSelectedItem(ItemStack.EMPTY);
         Auctions.Lot lot = Auctions.addPlayer(player.getUUID(), player.getName().getString(), price, stack, source.getServer());
         if (lot == null) {
            player.getInventory().add(stack);
            source.sendFailure(Component.literal("Could not create the auction. The item was returned.").withStyle(ChatFormatting.RED));
            return 0;
         } else {
            Component message = Component.literal("Auction #" + lot.id + " created for ")
               .withStyle(ChatFormatting.GRAY)
               .append(Component.literal(lot.stack.getHoverName().getString()).withStyle(ChatFormatting.WHITE))
               .append(Component.literal(" at ").withStyle(ChatFormatting.GRAY))
               .append(Component.literal(EconomyData.formatMoney(price)).withStyle(ChatFormatting.GOLD))
               .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
            send(source, () -> message, false);
            return 1;
         }
      }
   }

   private static int auctionBuy(CommandSourceStack source, int id, int amount) throws CommandSyntaxException {
      MinecraftServer server = source.getServer();
      AuctionEngine.ensureInitialized(server);
      Auctions.ensureLoaded(server);
      Auctions.Lot lot = Auctions.get(id);
      if (lot != null && lot.active()) {
         ServerPlayer buyer = source.getPlayerOrException();
         if (!lot.engine && buyer.getUUID().equals(lot.seller)) {
            source.sendFailure(Component.literal("You cannot buy your own auction.").withStyle(ChatFormatting.RED));
            return 0;
         } else {
            int buying = lot.engine ? Math.min(amount, lot.stock) : 1;
            if (buying <= 0) {
               source.sendFailure(Component.literal("This auction is out of stock.").withStyle(ChatFormatting.RED));
               return 0;
            } else {
               long total = lot.price * buying;
               long balance = EconomyData.getBalance(buyer.getUUID());
               if (balance < total) {
                  source.sendFailure(Component.literal("Not enough money. Need " + total + ", have " + balance + ".").withStyle(ChatFormatting.RED));
                  return 0;
               } else {
                  int itemCount = lot.stack.getCount() * buying;
                  int maxStack = Math.max(1, lot.stack.getItem().getDefaultMaxStackSize());
                  int requiredSlots = (itemCount + maxStack - 1) / maxStack;
                  int freeSlots = countFreeSlots(buyer.getInventory());
                  if (freeSlots < requiredSlots) {
                     source.sendFailure(
                        Component.literal("Not enough inventory space. Need " + requiredSlots + " free slot(s), have " + freeSlots + ".")
                           .withStyle(ChatFormatting.RED)
                     );
                     return 0;
                  } else if (!EconomyData.take(buyer.getUUID(), total)) {
                     source.sendFailure(Component.literal("Not enough money.").withStyle(ChatFormatting.RED));
                     return 0;
                  } else {
                     int added = 0;
                     boolean success = true;
                     int remaining = itemCount;

                     while (remaining > 0) {
                        int count = Math.min(remaining, maxStack);
                        ItemStack copy = lot.stack.copy();
                        copy.setCount(count);
                        if (!buyer.getInventory().add(copy)) {
                           success = false;
                           break;
                        }

                        added += count;
                        remaining -= count;
                     }

                     if (!success) {
                        if (added > 0) {
                           removeMatching(buyer.getInventory(), lot.stack, added);
                        }

                        EconomyData.add(buyer.getUUID(), total);
                        source.sendFailure(
                           Component.literal("Could not add the item to your inventory. Your money was refunded.").withStyle(ChatFormatting.RED)
                        );
                        return 0;
                     } else {
                        Component buyerMessage = Component.literal("Bought auction #" + id + ": ")
                           .withStyle(ChatFormatting.GRAY)
                           .append(Component.literal(buying + "x " + lot.name()).withStyle(ChatFormatting.GREEN))
                           .append(Component.literal(" for ").withStyle(ChatFormatting.GRAY))
                           .append(Component.literal(EconomyData.formatMoney(total)).withStyle(ChatFormatting.GOLD))
                           .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
                        send(source, () -> buyerMessage, false);
                        if (lot.engine) {
                           Auctions.consume(lot, buying);
                           AuctionEngine.onPurchased(lot);
                        } else {
                           Auctions.remove(id);
                           EconomyData.add(lot.seller, total);
                           ServerPlayer seller = server.getPlayerList().getPlayer(lot.seller);
                           if (seller != null) {
                              seller.sendSystemMessage(
                                 Component.literal("Your auction #" + id + " sold for " + EconomyData.formatMoney(total) + ".").withStyle(ChatFormatting.GOLD)
                              );
                           }
                        }

                        return buying;
                     }
                  }
               }
            }
         }
      } else {
         source.sendFailure(Component.literal("Auction #" + id + " is not available.").withStyle(ChatFormatting.RED));
         return 0;
      }
   }

   private static int auctionCancel(CommandSourceStack source, int id) throws CommandSyntaxException {
      Auctions.ensureLoaded(source.getServer());
      Auctions.Lot lot = Auctions.get(id);
      if (lot == null || !lot.active()) {
         source.sendFailure(Component.literal("Auction #" + id + " is not available.").withStyle(ChatFormatting.RED));
         return 0;
      } else if (lot.engine) {
         source.sendFailure(Component.literal("Auction House lots cannot be cancelled.").withStyle(ChatFormatting.RED));
         return 0;
      } else {
         ServerPlayer player = source.getPlayerOrException();
         if (!player.getUUID().equals(lot.seller)) {
            source.sendFailure(Component.literal("You can only cancel your own auctions.").withStyle(ChatFormatting.RED));
            return 0;
         } else if (countFreeSlots(player.getInventory()) < 1) {
            source.sendFailure(Component.literal("You need at least one free inventory slot to cancel this auction.").withStyle(ChatFormatting.RED));
            return 0;
         } else {
            ItemStack stack = lot.stack.copy();
            if (!player.getInventory().add(stack)) {
               source.sendFailure(Component.literal("Could not return the item to your inventory.").withStyle(ChatFormatting.RED));
               return 0;
            } else {
               Auctions.remove(id);
               send(source, () -> Component.literal("Auction #" + id + " cancelled; item returned.").withStyle(ChatFormatting.GRAY), false);
               return 1;
            }
         }
      }
   }

   private static int categoryCount(String category) {
      int count = 0;

      for (EconomyData.ShopEntry entry : EconomyData.all()) {
         if (matchesCategory(entry, category)) {
            count++;
         }
      }

      return count;
   }

   private static boolean matchesCategory(EconomyData.ShopEntry entry, String category) {
      if ("all".equals(category)) {
         return entry.buyable;
      } else if ("auction".equals(category)) {
         return "auction".equals(entry.category);
      } else {
         return "creative".equals(category) ? "creative".equals(entry.category) : entry.buyable && category.equals(entry.category);
      }
   }

   private static ChatFormatting categoryColor(String category) {
      return switch (category) {
         case "auction" -> ChatFormatting.LIGHT_PURPLE;
         case "creative" -> ChatFormatting.DARK_PURPLE;
         case "rare" -> ChatFormatting.GOLD;
         case "ores" -> ChatFormatting.AQUA;
         case "redstone" -> ChatFormatting.RED;
         case "nether" -> ChatFormatting.DARK_RED;
         case "end" -> ChatFormatting.DARK_PURPLE;
         case "food" -> ChatFormatting.GREEN;
         case "tools" -> ChatFormatting.YELLOW;
         case "armor" -> ChatFormatting.BLUE;
         default -> ChatFormatting.WHITE;
      };
   }

   private static CompletableFuture<Suggestions> suggestCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
      for (String category : CATEGORY_ORDER) {
         builder.suggest(category);
      }

      return builder.buildFuture();
   }

   private static CompletableFuture<Suggestions> suggestItems(SuggestionsBuilder builder, boolean buyableOnly) {
      for (EconomyData.ShopEntry entry : EconomyData.all()) {
         if ((!buyableOnly || entry.buyable) && (buyableOnly || entry.sellable())) {
            builder.suggest(entry.id);
         }
      }

      if (!buyableOnly) {
         builder.suggest("hand");
      }

      return builder.buildFuture();
   }

   private static HoverEvent shopHover(EconomyData.ShopEntry entry) {
      List<Component> lines = new ArrayList<>();
      if (entry.buyable) {
         lines.add(Component.literal("Buy: " + EconomyData.formatMoney(entry.buy)).withStyle(ChatFormatting.GOLD));
      } else {
         lines.add(Component.literal("Buy: not available").withStyle(ChatFormatting.GRAY));
      }

      lines.add(Component.literal("Sell: " + (entry.sellable() ? EconomyData.formatMoney(entry.sell) : "not sellable")).withStyle(ChatFormatting.YELLOW));
      lines.add(Component.literal("Category: " + entry.category).withStyle(ChatFormatting.GRAY));
      lines.add(Component.literal(entry.fullId()).withStyle(ChatFormatting.DARK_GRAY));
      if (!entry.note.isEmpty()) {
         lines.add(Component.literal(entry.note).withStyle(ChatFormatting.DARK_GRAY));
      }

      Item item = (Item)BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.fullId())).orElse(null);
      return (HoverEvent)(item != null ? itemHover(new ItemStack(item, 1), lines) : new ShowText(textTooltip(entry.name, lines)));
   }

   private static HoverEvent auctionHover(Auctions.Lot lot) {
      List<Component> lines = new ArrayList<>();
      lines.add(
         Component.literal("Auction #" + lot.id + " [" + lot.category().toUpperCase(Locale.ROOT) + "]")
            .withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD})
      );
      if (lot.engine) {
         lines.add(Component.literal("Seller: Auction House").withStyle(ChatFormatting.GRAY));
         lines.add(Component.literal("Price: " + lot.price + "  each").withStyle(ChatFormatting.GOLD));
         lines.add(Component.literal("Market Trend: " + lot.priceTrend()).withStyle(ChatFormatting.AQUA));
         lines.add(Component.literal("Stock: " + lot.stock).withStyle(ChatFormatting.GREEN));
         if (lot.expiresAt > 0L) {
            lines.add(Component.literal("Refreshes in: " + formatDuration(lot.expiresAt - System.currentTimeMillis())).withStyle(ChatFormatting.GRAY));
         }
      } else {
         lines.add(Component.literal("Seller: " + lot.sellerName).withStyle(ChatFormatting.GRAY));
         lines.add(Component.literal("Price: " + EconomyData.formatMoney(lot.price)).withStyle(ChatFormatting.GOLD));
         lines.add(Component.literal("Quantity: " + lot.stack.getCount()).withStyle(ChatFormatting.GRAY));
      }

      lines.add(Component.literal("Click item or [View] to inspect full contents").withStyle(ChatFormatting.DARK_PURPLE));
      lines.add(Component.literal("Use /ah buy " + lot.id + " [amount]").withStyle(ChatFormatting.DARK_GRAY));
      return itemHover(lot.stack, lines);
   }

   private static String formatEnchantName(String id) {
      if (id != null && !id.isEmpty()) {
         String[] parts = id.replace("minecraft:", "").split("_");
         StringBuilder sb = new StringBuilder();

         for (String part : parts) {
            if (!part.isEmpty()) {
               if (sb.length() > 0) {
                  sb.append(" ");
               }

               sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
         }

         return sb.toString();
      } else {
         return "";
      }
   }

   private static String toRoman(int num) {
      return switch (num) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         case 4 -> "IV";
         case 5 -> "V";
         default -> Integer.toString(num);
      };
   }

   private static ChatFormatting auctionCategoryColor(String category) {
      String var1 = category.toLowerCase(Locale.ROOT);

      return switch (var1) {
         case "unique" -> ChatFormatting.LIGHT_PURPLE;
         case "books" -> ChatFormatting.AQUA;
         case "gear" -> ChatFormatting.GOLD;
         case "discounts" -> ChatFormatting.GREEN;
         case "equip" -> ChatFormatting.BLUE;
         case "player" -> ChatFormatting.WHITE;
         default -> ChatFormatting.GRAY;
      };
   }

   private static CompletableFuture<Suggestions> suggestAuctionCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
      for (String s : List.of("all", "unique", "books", "gear", "discounts", "equip", "player", "1", "2", "3")) {
         builder.suggest(s);
      }

      return builder.buildFuture();
   }

   private static HoverEvent itemHover(ItemStack stack, List<Component> lines) {
      if (stack.isEmpty()) {
         return new ShowText(textTooltip("Item", lines));
      } else {
         ItemStack display = stack.copy();
         ItemLore existing = (ItemLore)display.get(DataComponents.LORE);
         List<Component> lore = new ArrayList<>();
         if (existing != null) {
            lore.addAll(existing.lines());
         }

         lore.addAll(lines);
         display.set(DataComponents.LORE, new ItemLore(lore));
         return new ShowItem(ItemStackTemplate.fromNonEmptyStack(display));
      }
   }

   private static Component textTooltip(String title, List<Component> lines) {
      MutableComponent tooltip = Component.literal(title).withStyle(new ChatFormatting[]{ChatFormatting.WHITE, ChatFormatting.BOLD});

      for (Component line : lines) {
         tooltip.append(Component.literal("\n")).append(line);
      }

      return tooltip;
   }

   private static Component auctionBuyHover(Auctions.Lot lot, int amount) {
      int buying = lot.engine ? Math.min(amount, lot.stock) : 1;
      return Component.literal("Buy " + buying + "x " + lot.name() + " for " + lot.price * buying + "$.").withStyle(ChatFormatting.GREEN);
   }

   private static String formatDuration(long millis) {
      if (millis <= 0L) {
         return "now";
      } else {
         long minutes = Math.max(1L, millis / 60000L);
         if (minutes < 60L) {
            return minutes + "m";
         } else {
            long hours = minutes / 60L;
            long remainder = minutes % 60L;
            if (hours < 24L) {
               return remainder == 0L ? hours + "h" : hours + "h " + remainder + "m";
            } else {
               long days = hours / 24L;
               long hourRemainder = hours % 24L;
               return hourRemainder == 0L ? days + "d" : days + "d " + hourRemainder + "h";
            }
         }
      }
   }

   private static MutableComponent button(String label, String command, ChatFormatting color) {
      return button(label, command, color, null);
   }

   private static MutableComponent button(String label, String command, ChatFormatting color, Component tooltip) {
      return Component.literal(label).withStyle(style -> {
         Style styled = style.withColor(color).withClickEvent(new RunCommand(command));
         if (tooltip != null) {
            styled = styled.withHoverEvent(new ShowText(tooltip));
         }

         return styled;
      });
   }

   private static void removeMatching(Inventory inventory, ItemStack template, int amount) {
      int remaining = amount;

      for (int slot = 0; slot < 36 && remaining > 0; slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
               inventory.setItem(slot, ItemStack.EMPTY);
            }

            remaining -= take;
         }
      }
   }

   private static int countFreeSlots(Inventory inventory) {
      int free = 0;

      for (int slot = 0; slot < 36; slot++) {
         if (inventory.getItem(slot).isEmpty()) {
            free++;
         }
      }

      return free;
   }

   private static int countItem(Inventory inventory, Item item) {
      int count = 0;

      for (int slot = 0; slot < 36; slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (!stack.isEmpty() && stack.getItem() == item) {
            count += stack.getCount();
         }
      }

      return count;
   }
}
