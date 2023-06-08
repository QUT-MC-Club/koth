package io.github.restioson.koth.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.plasmid.game.common.config.PlayerConfig;

public record KothConfig(
        PlayerConfig players,
        MapConfig map,
        int timeLimitSecs,
        Identifier dimension,
        int firstTo,
        boolean winnerTakesAll,
        boolean hasStick,
        boolean hasBow,
        boolean hasFeather,
        boolean deathmatch,
        boolean spawnInvuln,
        boolean knockoff
) {
    public static final Codec<KothConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PlayerConfig.CODEC.fieldOf("players").forGetter(KothConfig::players),
            MapConfig.CODEC.fieldOf("map").forGetter(KothConfig::map),
            Codec.INT.optionalFieldOf("time_limit_secs", 0).forGetter(KothConfig::timeLimitSecs),
            Identifier.CODEC.optionalFieldOf("dimension", Fantasy.DEFAULT_DIM_TYPE.getValue()).forGetter(KothConfig::dimension),
            Codec.INT.optionalFieldOf("first_to", 1).forGetter(KothConfig::firstTo),
            Codec.BOOL.optionalFieldOf("winner_takes_all", false).forGetter(KothConfig::winnerTakesAll),
            Codec.BOOL.optionalFieldOf("has_stick", false).forGetter(KothConfig::hasStick),
            Codec.BOOL.optionalFieldOf("has_bow", false).forGetter(KothConfig::hasStick),
            Codec.BOOL.optionalFieldOf("has_feather", false).forGetter(KothConfig::hasFeather),
            Codec.BOOL.optionalFieldOf("deathmatch", false).forGetter(KothConfig::deathmatch),
            Codec.BOOL.optionalFieldOf("spawn_invulnerability", true).forGetter(KothConfig::spawnInvuln),
            Codec.BOOL.optionalFieldOf("knockoff", false).forGetter(KothConfig::knockoff)
    ).apply(instance, KothConfig::new));

    public record MapConfig(Identifier id, int spawnAngle, long time) {
        public static final Codec<MapConfig> CODEC = RecordCodecBuilder.create(instance -> {
            return instance.group(
                    Identifier.CODEC.fieldOf("id").forGetter(MapConfig::id),
                    Codec.INT.fieldOf("spawn_angle").forGetter(MapConfig::spawnAngle),
                    Codec.LONG.optionalFieldOf("time", 6000L).forGetter(MapConfig::time)
            ).apply(instance, MapConfig::new);
        });
    }
}
