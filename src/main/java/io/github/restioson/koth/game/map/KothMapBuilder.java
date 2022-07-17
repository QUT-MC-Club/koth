package io.github.restioson.koth.game.map;

import io.github.restioson.koth.Koth;
import io.github.restioson.koth.game.KothConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.world.biome.BiomeKeys;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.map_templates.MapTemplateMetadata;
import xyz.nucleoid.map_templates.MapTemplateSerializer;
import xyz.nucleoid.map_templates.TemplateRegion;
import xyz.nucleoid.plasmid.game.GameOpenException;

import java.io.IOException;
import java.util.List;

public class KothMapBuilder {

    private final KothConfig.MapConfig config;

    public KothMapBuilder(KothConfig.MapConfig config) {
        this.config = config;
    }

    public KothMap create(MinecraftServer server) throws GameOpenException {
        try {
            MapTemplate template = MapTemplateSerializer.loadFromResource(server, this.config.id());
            MapTemplateMetadata metadata = template.getMetadata();

            List<BlockBounds> spawns = getSpawns(metadata);

            BlockBounds throne = metadata.getFirstRegionBounds("throne");

            KothMap map = new KothMap(template, spawns, throne, this.config.spawnAngle());
            template.setBiome(BiomeKeys.PLAINS);

            return map;
        } catch (IOException e) {
            throw new GameOpenException(Text.literal("Failed to load template"), e);
        }
    }

    private static List<BlockBounds> getSpawns(MapTemplateMetadata metadata) {
        List<BlockBounds> spawns = metadata.getRegions("spawn").sorted((a, b) -> {
            return getPriority(b) - getPriority(a);
        }).map(TemplateRegion::getBounds).toList();

        if (spawns.isEmpty()) {
            Koth.LOGGER.error("No spawn is defined on the map! The game will not work.");
            throw new GameOpenException(Text.literal("no spawn defined"));
        } else {
            return spawns;
        }
    }

    private static int getPriority(TemplateRegion region) {
        return region == null || region.getData() == null ? 1 : region.getData().getInt("Priority");
    }
}
