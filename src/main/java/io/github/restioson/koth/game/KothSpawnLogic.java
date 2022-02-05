package io.github.restioson.koth.game;

import io.github.restioson.koth.game.map.KothMap;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;

public class KothSpawnLogic {
    private final ServerWorld world;
    private final KothMap map;

    public KothSpawnLogic(ServerWorld world, KothMap map) {
        this.world = world;
        this.map = map;
    }

    public PlayerOfferResult acceptPlayer(PlayerOffer offer, GameMode gameMode, @Nullable KothStageManager stageManager) {
        var player = offer.player();
        return offer.accept(this.world, this.findSpawnFor(player, this.map.getSpawn(player.getRandom())))
                .and(() -> {
                    player.setYaw(this.map.spawnAngle);
                    this.resetPlayer(player, gameMode, stageManager);
                });
    }

    public void resetAndRespawn(ServerPlayerEntity player, GameMode gameMode, @Nullable KothStageManager stageManager, int index) {
        this.resetAndRespawn(player, gameMode, stageManager, this.map.getSpawn(index));
    }

    public void resetAndRespawnRandomly(ServerPlayerEntity player, GameMode gameMode, @Nullable KothStageManager stageManager) {
        this.resetAndRespawn(player, gameMode, stageManager, this.map.getSpawn(player.getRandom()));
    }

    private void resetAndRespawn(ServerPlayerEntity player, GameMode gameMode, @Nullable KothStageManager stageManager, BlockBounds bounds) {
        Vec3d spawn = this.findSpawnFor(player, bounds);
        player.teleport(this.world, spawn.x, spawn.y, spawn.z, this.map.spawnAngle, 0.0F);

        this.resetPlayer(player, gameMode, stageManager);
    }

    public void resetPlayer(ServerPlayerEntity player, GameMode gameMode, @Nullable KothStageManager stageManager) {
        player.changeGameMode(gameMode);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0f;
        player.setFireTicks(0);

        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.NIGHT_VISION,
                20 * 60 * 60,
                1,
                true,
                false
        ));

        player.networkHandler.syncWithPlayerPosition();

        if (stageManager != null) {
            KothStageManager.FrozenPlayer state = stageManager.frozen.computeIfAbsent(player, p -> new KothStageManager.FrozenPlayer());
            state.lastPos = player.getPos();
        }
    }

    public Vec3d findSpawnFor(ServerPlayerEntity player, BlockBounds bounds) {
        var world = this.world;
        BlockPos min = bounds.min();
        BlockPos max = bounds.max();

        boolean validSpawn = false;

        double x = 0;
        double z = 0;

        while (!validSpawn) {
            x = MathHelper.nextDouble(player.getRandom(), min.getX(), max.getX());
            z = MathHelper.nextDouble(player.getRandom(), min.getZ(), max.getZ());

            validSpawn = (!world.getBlockState(new BlockPos(x, min.getY(), z)).isAir()) ||
                    (!world.getBlockState(new BlockPos(x, min.getY() - 1, z)).isAir()) ||
                    (!world.getBlockState(new BlockPos(x, min.getY() - 2, z)).isAir());
        }

        return new Vec3d(x, min.getY(), z);
    }
}
