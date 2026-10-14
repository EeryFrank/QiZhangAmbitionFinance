package cozylife.economy;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A registered item, never a renamed emerald or client-supplied denomination. */
public final class CoinItem extends Item {
    private final long value;
    public CoinItem(Properties properties, long value) { super(properties); this.value = value; }
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (CozyEconomy.service() == null) player.sendSystemMessage(Component.literal("经济系统正在准备，请稍候再试"));
            else CozyEconomy.service().deposit(serverPlayer, hand);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        return player == null ? InteractionResult.PASS : use(context.getLevel(), player, context.getHand());
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                          Consumer<Component> output, TooltipFlag flag) {
        output.accept(Component.translatable("item.cozylife_economy.coin.value", value).withStyle(ChatFormatting.GOLD));
        output.accept(Component.translatable("item.cozylife_economy.coin.use").withStyle(ChatFormatting.GRAY));
    }
}
