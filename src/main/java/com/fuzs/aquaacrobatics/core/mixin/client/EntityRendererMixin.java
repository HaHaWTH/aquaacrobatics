package com.fuzs.aquaacrobatics.core.mixin.client;

import com.fuzs.aquaacrobatics.integration.IntegrationManager;
import com.fuzs.aquaacrobatics.util.math.MathHelperNew;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("unused")
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Shadow
    @Final
    private Minecraft mc;

    private float eyeHeight;
    private float previousEyeHeight;
    private float entityEyeHeight;
    private float partialTicks;

    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void orientCamera(float partialTicks, CallbackInfo callbackInfo) {

        // field for passing on partialTicks, workaround as @ModifyVariable is unable to handle method arguments in Mixin <0.8
        this.partialTicks = partialTicks;
    }

    @ModifyVariable(method = "orientCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevPosX:D", ordinal = 0), ordinal = 1)
    public float getEyeHeight(float eyeHeight) {
        Entity entity = this.mc.getRenderViewEntity();

        // Do not apply eye height patch if the camera is not a player, or if Random Patches is installed
        if (!(entity instanceof EntityPlayer) || IntegrationManager.isRandomPatchesEnabled()) {
            return eyeHeight;
        }

        // need to do it like this to prevent crash with wings mod
        this.entityEyeHeight = eyeHeight;
        return MathHelperNew.lerp(this.partialTicks, this.previousEyeHeight, this.eyeHeight);
    }

    @Inject(method = "updateRenderer", at = @At("TAIL"))
    public void updateRenderer(CallbackInfo callbackInfo) {

        this.interpolateHeight();
    }

    private void interpolateHeight() {

        this.previousEyeHeight = this.eyeHeight;
        this.eyeHeight += (this.entityEyeHeight - this.eyeHeight) * 0.5F;
    }

    // Backport start: Camera logic from modern versions
    @Redirect(
            method = {"updateFogColor", "setupFog", "getFOVModifier"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ActiveRenderInfo;getBlockStateAtEntityViewpoint(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;F)Lnet/minecraft/block/state/IBlockState;"
            )
    )
    private IBlockState getBlockStateAtCameraForFog(World world, Entity entity, float partialTicks) {
        IBlockState state = ActiveRenderInfo.getBlockStateAtEntityViewpoint(world, entity, partialTicks);
        if (state.getMaterial() == Material.WATER && !this.aquaAcrobatics$isCameraInWater(world, entity, partialTicks)) {
            return Blocks.AIR.getDefaultState();
        }
        return state;
    }

    @Redirect(
            method = "updateFogColor",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;getFogColor(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;F)Lnet/minecraft/util/math/Vec3d;"
            )
    )
    private Vec3d getFogColor(Block block, World world, BlockPos pos, IBlockState state, Entity entity, Vec3d originalColor, float partialTicks) {
        if (state.getMaterial() == Material.WATER && !this.aquaAcrobatics$isCameraInWater(world, entity, partialTicks)) {
            return originalColor;
        }
        return block.getFogColor(world, pos, state, entity, originalColor, partialTicks);
    }

    @Unique
    private boolean aquaAcrobatics$isCameraInWater(World world, Entity entity, float partialTicks) {
        Vec3d cameraPos = this.aquaAcrobatics$getCameraPosition(entity, partialTicks);
        BlockPos blockPos = new BlockPos(cameraPos);
        IBlockState state = world.getBlockState(blockPos);
        if (state.getMaterial() != Material.WATER) {
            return false;
        }

        return cameraPos.y < (double) blockPos.getY() + this.aquaAcrobatics$getWaterHeight(world, blockPos, state);
    }

    @Unique
    private Vec3d aquaAcrobatics$getCameraPosition(Entity entity, float partialTicks) {
        double x = entity.prevPosX + (entity.posX - entity.prevPosX) * (double) partialTicks;
        double y = entity.prevPosY + (entity.posY - entity.prevPosY) * (double) partialTicks;
        double z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * (double) partialTicks;
        return new Vec3d(x, y + (double) this.aquaAcrobatics$getCameraEyeHeight(entity, partialTicks), z);
    }

    @Unique
    private float aquaAcrobatics$getCameraEyeHeight(Entity entity, float partialTicks) {
        if (entity instanceof EntityPlayer && !IntegrationManager.isRandomPatchesEnabled()) {
            return MathHelperNew.lerp(partialTicks, this.previousEyeHeight, this.eyeHeight);
        }
        return entity.getEyeHeight();
    }

    @Unique
    private float aquaAcrobatics$getWaterHeight(World world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block instanceof IFluidBlock) {
            float filled = ((IFluidBlock) block).getFilledPercentage(world, pos);
            return filled < 0.0F ? filled + 1.0F : filled;
        }
        if (block instanceof BlockLiquid) {
            return BlockLiquid.getBlockLiquidHeight(state, world, pos);
        }
        float height = block.getBlockLiquidHeight(world, pos, state, Material.WATER);
        return height > 0.0F ? height : 1.0F;
    }
    // Backport end - Camera logic from modern versions

    /**
     * This mixin is marked as not required, as some mods patch this themselves.
     */
    @Redirect(
            method = "renderWorldPass",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;isInsideOfMaterial(Lnet/minecraft/block/material/Material;)Z",
                    ordinal = 0
            ),
            require = 0,
            expect = 0
    )
    private boolean ignoreWater(Entity entity, Material material) {
        /* 1.13 removed this check */
        if (material == Material.WATER)
            return false;
        return entity.isInsideOfMaterial(material);
    }
}
