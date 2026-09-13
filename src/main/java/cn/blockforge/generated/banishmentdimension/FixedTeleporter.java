package cn.blockforge.generated.banishmentdimension;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * 定点跨维度传送器：不管原版传送逻辑，把到达目标维度的玩家/实体
 * 直接放到指定坐标并清零速度。作者：VIASR。
 *
 * 构造时传入【目标维度】的 WorldServer（Teleporter 基类需要它在目标世界工作）。
 */
public class FixedTeleporter extends Teleporter {

    private final double x, y, z;
    private final float yaw, pitch;

    public FixedTeleporter(WorldServer destWorld, double x, double y, double z, float yaw, float pitch) {
        super(destWorld);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public boolean isVanilla() {
        return false;
    }

    /** 原版与 Forge 的维度切换最终都走这里放置实体：直接钉到指定坐标。 */
    @Override
    public void placeEntity(World world, Entity entity, float yaw) {
        entity.motionX = 0.0D;
        entity.motionY = 0.0D;
        entity.motionZ = 0.0D;
        entity.setLocationAndAngles(x, y, z, this.yaw, this.pitch);
        if (entity instanceof EntityPlayerMP) {
            EntityPlayerMP mp = (EntityPlayerMP) entity;
            if (mp.connection != null) {
                mp.connection.setPlayerLocation(x, y, z, this.yaw, this.pitch);
            }
        }
    }

    /** 屏蔽原版传送门逻辑，保证不会把玩家塞进任何门户结构。 */
    @Override
    public void placeInPortal(Entity entity, float yaw) {
        placeEntity(world, entity, yaw);
    }

    @Override
    public boolean placeInExistingPortal(Entity entity, float yaw) {
        return false;
    }

    @Override
    public boolean makePortal(Entity entity) {
        return false;
    }
}
