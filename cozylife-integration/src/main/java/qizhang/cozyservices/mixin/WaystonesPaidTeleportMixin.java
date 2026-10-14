package qizhang.cozyservices.mixin;

import qizhang.cozyservices.server.WaystonesTravelFees;
import java.util.Set;
import net.blay09.mods.waystones.api.TeleportDestination;
import net.blay09.mods.waystones.api.WaystoneTeleportContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Exact installed Waystones 26.3 hook: preserve its Pre/cancel/Post/Complete execution order. */
@Mixin(targets="net.blay09.mods.waystones.core.WaystoneTeleportManager",remap=false)
public abstract class WaystonesPaidTeleportMixin {
    @Redirect(method="teleportEntity",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z"),require=1)
    private static boolean qizhang$paidTeleport(Entity entity,ServerLevel target,double x,double y,double z,Set<Relative> flags,float yaw,float pitch,boolean dismount,
                                               WaystoneTeleportContext context,Entity originalEntity,TeleportDestination originalDestination){
        return WaystonesTravelFees.teleport(context,entity,target,new Vec3(x,y,z),()->entity.teleportTo(target,x,y,z,flags,yaw,pitch,dismount));
    }
}
