package io.github.restioson.koth.game;

import io.github.restioson.koth.game.map.KothMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class KothSpawnLogic {
    private final ServerWorld world;
    private final KothMap map;
    private final Map<BlockBounds, LongList> spawnPositionsMap;

    public KothSpawnLogic(ServerWorld world, KothMap map) {
        this.world = world;
        this.map = map;
        this.spawnPositionsMap = collectSpawnPositions(world, map);
    }

    public JoinAcceptorResult.Teleport acceptPlayer(JoinAcceptor offer, GameMode gameMode, @Nullable KothStageManager stageManager) {
        return offer.teleport(this.world, this.findSpawnFor(this.map.getSpawn(world.random))).thenRunForEach(player -> {
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
        Vec3d spawn = this.findSpawnFor(bounds);
        player.teleport(this.world, spawn.x, spawn.y, spawn.z, Set.of(), this.map.spawnAngle, 0.0F, false);

        this.resetPlayer(player, gameMode, stageManager);
    }

    public void resetPlayer(ServerPlayerEntity player, GameMode gameMode, @Nullable KothStageManager stageManager) {
        player.changeGameMode(gameMode);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0f;
        player.setFireTicks(0);

        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.NIGHT_VISION,
                -1,
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

    public Vec3d findSpawnFor(BlockBounds bounds) {
        Random random = this.world.getRandom();

        LongList spawnPositions = this.spawnPositionsMap.get(bounds);
        long packedPos = spawnPositions.getLong(random.nextInt(spawnPositions.size()));
        BlockPos min = bounds.min();

        int x = BlockPos.unpackLongX(packedPos);
        int z = BlockPos.unpackLongZ(packedPos);

        return new Vec3d(x + random.nextDouble(), min.getY(), z + random.nextDouble());
    }

    private static Map<BlockBounds, LongList> collectSpawnPositions(ServerWorld world, KothMap map) {
        Map<BlockBounds, LongList> spawnPositionsMap = new HashMap<>();

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (BlockBounds spawn : map.spawns) {
            LongList spawnPositions = new LongArrayList(64);
            spawnPositionsMap.put(spawn, spawnPositions);

            BlockPos min = spawn.min();
            BlockPos max = spawn.max();

            for (int x = min.getX(); x < max.getX(); x++) {
                for (int z = min.getZ(); z < max.getZ(); z++) {
                    for (int y = min.getY(); y > min.getY() - 3; y--) {
                        pos.set(x, y, z);
                        if (!world.getBlockState(pos).isAir()) {
                            spawnPositions.add(pos.asLong());
                            continue;
                        }
                    }
                }
            }

            if (spawnPositions.isEmpty()) {
                BlockPos centerBottom = BlockPos.ofFloored(spawn.centerBottom());
                spawnPositions.add(centerBottom.asLong());
            }
        }

        return spawnPositionsMap;
    }
}
