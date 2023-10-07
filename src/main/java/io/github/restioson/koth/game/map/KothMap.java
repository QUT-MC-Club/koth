package io.github.restioson.koth.game.map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.plasmid.game.world.generator.TemplateChunkGenerator;

import java.util.ArrayList;
import java.util.List;

public class KothMap {
    private final MapTemplate template;
    public final List<BlockBounds> spawns;
    public final int spawnAngle;
    public final BlockBounds bounds;
    public final List<BlockBounds> noPvp;
    public final Box throne;

    public KothMap(MapTemplate template, List<BlockBounds> spawns, BlockBounds throne, int spawnAngle) {
        this.template = template;
        this.spawns = spawns;
        this.spawnAngle = spawnAngle;
        this.bounds = template.getBounds();
        this.throne = throne.asBox();

        this.noPvp = new ArrayList<>(spawns.size());
        for (BlockBounds spawn : spawns) {
            BlockPos max = spawn.max();
            this.noPvp.add(BlockBounds.of(spawn.min(), new BlockPos(max.getX(), max.getY() + 3, max.getZ())));
        }
    }

    public ChunkGenerator asGenerator(MinecraftServer server) {
        return new TemplateChunkGenerator(server, this.template);
    }

    public BlockBounds getSpawn(int index) {
        return this.spawns.get(index % this.spawns.size());
    }

    public BlockBounds getSpawn(Random random) {
        return Util.getRandom(this.spawns, random);
    }
}
