package io.github.restioson.koth.game;

import com.google.common.collect.Sets;
import io.github.restioson.koth.game.map.KothMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.util.ItemStackBuilder;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.stimuli.event.item.ItemUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;
import xyz.nucleoid.stimuli.event.projectile.ArrowFireEvent;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class KothActive {
    private final KothConfig config;

    private final ServerWorld world;
    public final GameSpace gameSpace;
    private final KothMap gameMap;

    private final Object2ObjectMap<ServerPlayerEntity, KothPlayer> participants;
    private final KothSpawnLogic spawnLogic;
    private final KothStageManager stageManager;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // i like ifPresent
    private final Optional<KothTimerBar> timerBar;
    private final KothScoreboard scoreboard;
    private OvertimeState overtimeState = OvertimeState.NOT_IN_OVERTIME;
    private int totalWins = 0;
    private boolean gameFinished;
    private static final int LEAP_INTERVAL_TICKS = 5 * 20; // 5 second cooldown
    private static final double LEAP_VELOCITY = 1.0;
    private boolean pvpEnabled = false;

    private KothActive(ServerWorld world, GameSpace gameSpace, KothMap map, KothConfig config, Set<ServerPlayerEntity> participants, GlobalWidgets widgets) {
        this.world = world;
        this.gameSpace = gameSpace;
        this.config = config;
        this.gameMap = map;
        this.stageManager = new KothStageManager(config);
        this.spawnLogic = new KothSpawnLogic(world, map);
        this.participants = new Object2ObjectOpenHashMap<>();

        for (ServerPlayerEntity player : participants) {
            this.participants.put(player, new KothPlayer(player));
        }

        String name;
        if (config.deathmatch()) {
            name = "Deathmatch!";
        } else if (config.winnerTakesAll()) {
            name = "Winner Takes All";
        } else if (config.knockoff()) {
            name = "Knock everyone off arena!";
        } else {
            name = "Longest-reigning Ruler";
        }

        this.scoreboard = new KothScoreboard(widgets, name, this.config.winnerTakesAll(), this.config.deathmatch(), this.config.knockoff());

        if (this.config.deathmatch() || this.config.knockoff()) {
            this.timerBar = Optional.empty();
        } else {
            this.timerBar = Optional.of(new KothTimerBar(widgets));
        }
    }

    public static void open(ServerWorld world, GameSpace gameSpace, KothMap map, KothConfig config) {
        gameSpace.setActivity(activity -> {
            Set<ServerPlayerEntity> participants = Sets.newHashSet(gameSpace.getPlayers());
            GlobalWidgets widgets = GlobalWidgets.addTo(activity);
            KothActive active = new KothActive(world, gameSpace, map, config, participants, widgets);

            activity.deny(GameRuleType.CRAFTING);
            activity.deny(GameRuleType.PORTALS);
            activity.allow(GameRuleType.PVP);
            activity.deny(GameRuleType.HUNGER);
            activity.deny(GameRuleType.FALL_DAMAGE);
            activity.allow(GameRuleType.INTERACTION);
            activity.deny(GameRuleType.BLOCK_DROPS);
            activity.deny(GameRuleType.THROW_ITEMS);
            activity.deny(GameRuleType.UNSTABLE_TNT);

            activity.listen(GameActivityEvents.ENABLE, active::onOpen);

            activity.listen(GamePlayerEvents.OFFER, active::offerPlayer);
            activity.listen(GamePlayerEvents.REMOVE, active::removePlayer);

            activity.listen(GameActivityEvents.TICK, active::tick);
            activity.listen(ItemUseEvent.EVENT, active::onUseItem);

            activity.listen(PlayerDeathEvent.EVENT, active::onPlayerDeath);
            activity.listen(PlayerDamageEvent.EVENT, active::onPlayerDamage);
            activity.listen(ArrowFireEvent.EVENT, active::onPlayerFireArrow);
        });
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float value) {
        KothPlayer participant = this.participants.get(player);

        if (participant != null && source.getAttacker() != null && source.getAttacker() instanceof ServerPlayerEntity) {
            long time = this.world.getTime();
            PlayerRef attacker = PlayerRef.of((ServerPlayerEntity) source.getAttacker());
            participant.lastTimeWasAttacked = new AttackRecord(attacker, time);
        }

        if (!player.isSpectator() && source.isFire()) {
            this.spawnDeadParticipant(player, source, this.world.getTime());
        }

        if (!this.pvpEnabled || this.hasSpawnInvulnerability(player)) {
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    private void maybeGiveBow(ServerPlayerEntity player) {
        if (this.config.hasBow()) {
            ItemStack bow = ItemStackBuilder.of(Items.BOW)
                    .addEnchantment(Enchantments.PUNCH, 2)
                    .addEnchantment(Enchantments.INFINITY, 1)
                    .setUnbreakable()
                    .addLore(Text.literal("Uzoba dutyulwa"))
                    .build();

            player.getInventory().insertStack(bow);
        }
    }

    private void onOpen() {
        ServerWorld world = this.world;

        int index = 0;
        for (ServerPlayerEntity player : this.participants.keySet()) {
            this.spawnParticipant(player, index);

            if (this.config.hasStick()) {
                ItemStack stick = ItemStackBuilder.of(Items.STICK)
                        .addEnchantment(Enchantments.KNOCKBACK, 2)
                        .addLore(Text.literal("Ndiza kumbetha"))
                        .build();
                player.getInventory().insertStack(stick);
            }

            if (this.config.hasBow()) {
                ItemStack arrow = ItemStackBuilder.of(Items.ARROW)
                        .addLore(Text.literal("It seems to always come back to me..."))
                        .build();

                player.getInventory().insertStack(arrow);
            }

            if (this.config.hasFeather()) {
                ItemStack feather = ItemStackBuilder.of(Items.FEATHER)
                        .addLore(Text.literal("Bukelani, ndiyinkosi yesibhakabhaka!"))
                        .build();

                if (this.config.hasBow()) {
                    player.getInventory().insertStack(feather);
                } else {
                    player.equipStack(EquipmentSlot.OFFHAND, feather);
                }
            }

            index++;
        }
        this.stageManager.onOpen(world.getTime(), this.config, this.gameSpace);
        this.scoreboard.renderTitle();
    }

    private PlayerOfferResult offerPlayer(PlayerOffer offer) {
        return this.spawnLogic.acceptPlayer(offer, GameMode.SPECTATOR, this.stageManager);
    }

    private void removePlayer(ServerPlayerEntity player) {
        this.participants.remove(player);
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        this.spawnDeadParticipant(player, source, this.world.getTime());
        return ActionResult.FAIL;
    }

    private TypedActionResult<ItemStack> onUseItem(ServerPlayerEntity player, Hand hand) {
        ItemStack heldStack = player.getStackInHand(hand);

        if (heldStack.getItem() == Items.FEATHER) {
            ItemCooldownManager cooldown = player.getItemCooldownManager();
            if (!cooldown.isCoolingDown(heldStack.getItem())) {
                KothPlayer state = this.participants.get(player);
                if (state != null) {
                    Vec3d rotationVec = player.getRotationVec(1.0F);
                    player.setVelocity(rotationVec.multiply(LEAP_VELOCITY));
                    Vec3d oldVel = player.getVelocity();
                    player.setVelocity(oldVel.x, oldVel.y + 0.1f, oldVel.z);
                    player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));

                    player.playSound(SoundEvents.ENTITY_HORSE_SADDLE, SoundCategory.PLAYERS, 1.0F, 1.0F);
                    cooldown.set(heldStack.getItem(), LEAP_INTERVAL_TICKS);
                }
            }
        }

        return TypedActionResult.pass(ItemStack.EMPTY);
    }

    private ActionResult onPlayerFireArrow(
            ServerPlayerEntity user,
            ItemStack tool,
            ArrowItem arrowItem,
            int ticks,
            PersistentProjectileEntity projectile
    ) {
        if (this.hasSpawnInvulnerability(user)) {
            return ActionResult.FAIL;
        } else {
            return ActionResult.PASS;
        }
    }

    private void spawnDeadParticipant(ServerPlayerEntity player, DamageSource damageSource, long time) {
        this.spawnLogic.resetAndRespawnRandomly(player, GameMode.SPECTATOR, this.stageManager);

        Inventories.remove(player.getInventory(), it -> it.getItem() == Items.BOW, 1, false);
        KothPlayer participant = this.participants.get(player);
        ServerWorld world = this.world;

        if (this.config.deathmatch()) {
            PlayerSet players = this.gameSpace.getPlayers();
            MutableText eliminationMessage = Text.literal(" has been eliminated by ");

            if (damageSource.getAttacker() != null) {
                eliminationMessage.append(damageSource.getAttacker().getDisplayName());
            } else if (participant != null && participant.attacker(time, world) != null) {
                eliminationMessage.append(participant.attacker(time, world).getDisplayName());
            } else if (damageSource.isFire()) {
                eliminationMessage.append("taking a swim in lava!");
            } else if (damageSource.isOutOfWorld()) {
                eliminationMessage.append("staring into the abyss!");
            } else {
                eliminationMessage = Text.literal(" has been eliminated!");
            }

            players.sendMessage(Text.literal("").append(player.getDisplayName()).append(eliminationMessage).formatted(Formatting.GOLD));
            players.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);
        } else if (this.config.knockoff() && !this.gameFinished) {
            KothPlayer attacker = this.participants.get(participant.attacker(time, world));
            if (attacker != null) {
                attacker.score += 1;
                attacker.player.addExperienceLevels(1);
                attacker.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
                if (attacker.score >= this.config.firstTo()) {
                    this.gameFinished = true;
                    this.stageManager.finishTime = world.getTime();

                }
            }
        } else {
            this.participants.get(player).deadTime = time;
        }
    }

    private void spawnParticipant(ServerPlayerEntity player, int index) {
        if (this.config.hasBow() && !player.getInventory().containsAny(new HashSet<>(Collections.singletonList(Items.BOW)))) {
            this.maybeGiveBow(player);
        }

        if (index < 0) {
            this.spawnLogic.resetAndRespawnRandomly(player, GameMode.ADVENTURE, this.stageManager);
        } else {
            this.spawnLogic.resetAndRespawn(player, GameMode.ADVENTURE, this.stageManager, index);
        }
    }

    private void tick() {
        ServerWorld world = this.world;
        long time = world.getTime();

        for (ArrowEntity arrow : world.getEntitiesByType(EntityType.ARROW, this.gameMap.bounds.asBox(), e -> e.inGround)) {
            arrow.remove(Entity.RemovalReason.DISCARDED);
        }

        boolean overtime = false;
        int alivePlayers = 0;
        int playersOnThrone = 0;

        for (ServerPlayerEntity player : this.participants.keySet()) {
            if (!player.isSpectator()) {
                alivePlayers += 1;
            } else {
                continue;
            }

            boolean onThrone = this.gameMap.throne.asBox().intersects(player.getBoundingBox());

            if (onThrone) {
                playersOnThrone += 1;
            }

            if (onThrone && player != this.getWinner()) {
                overtime = true;
            }
        }

        overtime |= playersOnThrone != 1;
        overtime |= this.config.deathmatch() && alivePlayers > 1;
        overtime |= this.config.knockoff();

        KothStageManager.TickResult result = this.stageManager.tick(time, gameSpace, overtime, this.gameFinished);

        switch (result) {
            case CONTINUE_TICK:
                this.pvpEnabled = true;
                this.timerBar.ifPresent(bar -> bar.update(this.stageManager.finishTime - time, this.config.timeLimitSecs() * 20));
                break;
            case OVERTIME:
                if (this.overtimeState == OvertimeState.NOT_IN_OVERTIME) {
                    this.overtimeState = OvertimeState.IN_OVERTIME;
                    this.gameSpace.getPlayers().showTitle(Text.literal("Overtime!"), 20);
                    this.timerBar.ifPresent(KothTimerBar::setOvertime);
                } else if (this.overtimeState == OvertimeState.JUST_ENTERED_OVERTIME) {
                    this.overtimeState = OvertimeState.IN_OVERTIME;
                }

                break;
            case NEXT_ROUND:
                int index = 0;
                for (ServerPlayerEntity participant : this.participants.keySet()) {
                    this.spawnParticipant(participant, index);
                    index++;
                }
            case TICK_FINISHED_PLAYERS_FROZEN:
                this.pvpEnabled = false;
            case TICK_FINISHED:
                return;
            case ROUND_FINISHED:
                this.broadcastWin(this.getWinner());
                return;
            case GAME_CLOSED:
                this.gameSpace.close(GameCloseReason.FINISHED);
                return;
        }

        for (ServerPlayerEntity player : this.participants.keySet()) {
            player.setHealth(20.0f);

            BlockBounds bounds = this.gameMap.bounds;
            BlockPos pos = player.getBlockPos();
            if (!bounds.contains(pos)) {
                BlockPos max = this.gameMap.bounds.max();
                BlockPos playerBoundedY = new BlockPos(pos.getX(), max.getY(), pos.getZ());

                // Allow the player to jump above the bounds but not go out of its x and z bounds
                boolean justAbove = player.getY() > max.getY() && bounds.contains(playerBoundedY);

                if (player.isSpectator()) {
                    this.spawnLogic.resetAndRespawnRandomly(player, GameMode.SPECTATOR, this.stageManager);
                } else if (!justAbove) {
                    this.spawnDeadParticipant(player, DamageSource.OUT_OF_WORLD, time);
                }
            }

            if (this.config.deathmatch()) {
                continue;
            }

            KothPlayer state = this.participants.get(player);
            assert state != null;

            if (player.isSpectator()) {
                this.tickDead(player, state, time);
                continue;
            }

            if (this.config.winnerTakesAll()) {
                List<KothPlayer> top = this.participants.values().stream()
                        .sorted(Comparator.comparingDouble(p -> -p.player.getY())) // Descending sort
                        .limit(1)
                        .collect(Collectors.toList());
                this.scoreboard.render(top);
                continue;
            } else if (this.config.knockoff()) {
                this.scoreboard.render(this.buildLeaderboard());
                continue;
            }

            if (this.gameMap.throne.asBox().intersects(player.getBoundingBox()) && time % 20 == 0) {
                state.score += 1;
                player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
                player.addExperienceLevels(1);
                this.scoreboard.render(this.buildLeaderboard());
            }
        }
    }

    private void tickDead(ServerPlayerEntity player, KothPlayer state, long time) {
        int sec = 5 - (int) Math.floor((time - state.deadTime) / 20.0f);

        if (sec > 0 && (time - state.deadTime) % 20 == 0) {
            Text text = Text.literal(String.format("Respawning in %ds", sec)).formatted(Formatting.BOLD);
            player.sendMessage(text, true);
        }

        if (time - state.deadTime > 5 * 20) {
            this.spawnParticipant(player, -1);
        }
    }

    private boolean hasSpawnInvulnerability(ServerPlayerEntity player) {
        if (this.config.spawnInvuln()) {
            for (BlockBounds noPvp : this.gameMap.noPvp) {
                if (noPvp.contains(player.getBlockPos())) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<KothPlayer> buildLeaderboard() {
        assert !this.config.winnerTakesAll();
        return this.participants.values().stream()
                .filter(player -> player.score != 0)
                .sorted(Comparator.comparingInt(player -> -player.score)) // Descending sort
                .limit(5)
                .collect(Collectors.toList());
    }

    private void broadcastWin(ServerPlayerEntity winner) {
        PlayerSet players = this.gameSpace.getPlayers();
        KothPlayer participant = this.participants.get(winner);

        if (participant != null) {
            participant.wins++;
        }
        totalWins++;

        String wonThe;

        if (participant == null && this.config.firstTo() == 1) {
            wonThe = "game";
            this.gameFinished = true;
        } else if (participant != null && (participant.wins == this.config.firstTo() || this.config.knockoff())) {
            wonThe = "game";
            if (participant.wins == totalWins) {
                wonThe += " perfectly";
            }
            this.gameFinished = true;
        } else {
            wonThe = "round";
        }

        if (winner == null) {
            players.sendMessage(Text.literal("The ").append(wonThe).append(" ended, but nobody won!").formatted(Formatting.GOLD));
            players.playSound(SoundEvents.ENTITY_VILLAGER_NO);
            return;
        }

        if (this.config.deathmatch()) {
            List<KothPlayer> top = this.participants.values().stream()
                    .sorted(Comparator.comparingDouble(p -> -p.wins)) // Descending sort
                    .limit(5)
                    .collect(Collectors.toList());
            this.scoreboard.render(top);
        }


        Text message = winner.getDisplayName().copy().append(" has won the ").append(wonThe).append("!").formatted(Formatting.GOLD);

        players.sendMessage(message);
        players.playSound(SoundEvents.ENTITY_VILLAGER_YES);
    }

    private ServerPlayerEntity getWinner() {
        if (this.config.deathmatch()) {
            for (ServerPlayerEntity player : this.participants.keySet()) {
                if (!player.isSpectator()) {
                    return player;
                }
            }

            // No players are alive
            return null;
        }

        Map.Entry<ServerPlayerEntity, KothPlayer> winner = null;
        for (Map.Entry<ServerPlayerEntity, KothPlayer> entry : this.participants.entrySet()) {
            if (this.config.winnerTakesAll()) {
                if (entry.getKey().isSpectator()) {
                    continue;
                }

                if (winner == null || winner.getKey().getBlockPos().getY() < entry.getKey().getBlockPos().getY() ) {
                    winner = entry;
                }
            } else if (this.config.knockoff()) {
                if (winner == null || entry.getValue().score >= this.config.firstTo() ) {
                    winner = entry;
                }
            } else {
                if (winner == null || winner.getValue().score < entry.getValue().score) {
                    winner = entry;
                }
            }
        }

        return winner != null ? winner.getKey() : null;
    }

    enum OvertimeState {
        NOT_IN_OVERTIME,
        JUST_ENTERED_OVERTIME,
        IN_OVERTIME,
    }
}
