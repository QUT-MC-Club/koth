package io.github.restioson.koth.game;

import io.github.restioson.koth.game.map.KothMap;
import io.github.restioson.koth.game.map.KothMapBuilder;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.ActionResult;
import net.minecraft.world.GameMode;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.plasmid.game.GameOpenContext;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.game.GameOpenProcedure;
import xyz.nucleoid.plasmid.game.GameResult;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GameWaitingLobby;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

public class KothWaiting {
    private final ServerWorld world;
    private final GameSpace gameSpace;
    private final KothMap map;
    private final KothConfig config;
    private final KothSpawnLogic spawnLogic;

    private KothWaiting(ServerWorld world, GameSpace gameSpace, KothMap map, KothConfig config) {
        this.world = world;
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;
        this.spawnLogic = new KothSpawnLogic(world, map);
    }

    public static GameOpenProcedure open(GameOpenContext<KothConfig> context) {
        KothConfig config = context.config();
        KothMapBuilder generator = new KothMapBuilder(context.config().map());
        KothMap map = generator.create(context.server());

        if (!config.winnerTakesAll() && map.throne == null) {
            throw new GameOpenException(new LiteralText("throne must exist if winner doesn't take all"));
        }

        RuntimeWorldConfig worldConfig = new RuntimeWorldConfig()
                .setGenerator(map.asGenerator(context.server()));

        return context.openWithWorld(worldConfig, (activity, world) -> {
            KothWaiting waiting = new KothWaiting(world, activity.getGameSpace(), map, context.config());

            GameWaitingLobby.addTo(activity, config.players());

            activity.listen(GameActivityEvents.REQUEST_START, waiting::requestStart);
            activity.listen(GameActivityEvents.TICK, waiting::tick);
            activity.listen(GamePlayerEvents.OFFER, waiting::offerPlayer);
            activity.listen(PlayerDamageEvent.EVENT, waiting::onPlayerDamage);
            activity.listen(PlayerDeathEvent.EVENT, waiting::onPlayerDeath);
            worldConfig.setTimeOfDay(config.map().time());
        });
    }

    private void tick() {
        for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
            if (!this.map.bounds.contains(player.getBlockPos())) {
                this.spawnPlayer(player);
            }
        }
    }

    private GameResult requestStart() {
        KothActive.open(this.world, this.gameSpace, this.map, this.config);
        return GameResult.ok();
    }

    private PlayerOfferResult offerPlayer(PlayerOffer offer) {
        return this.spawnLogic.acceptPlayer(offer, GameMode.ADVENTURE, null);
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float value) {
        if (source.isFire()) {
            this.spawnPlayer(player);
        }

        return ActionResult.FAIL;
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        this.spawnPlayer(player);
        return ActionResult.FAIL;
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        this.spawnLogic.resetAndRespawnRandomly(player, GameMode.ADVENTURE, null);
    }
}
