package io.github.restioson.koth.game;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket.Flag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.player.PlayerSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class KothStageManager {
    private final KothConfig config;
    private long closeTime = -1;
    public long finishTime = -1;
    private long startTime = -1;
    public final Object2ObjectMap<ServerPlayerEntity, FrozenPlayer> frozen;

    public KothStageManager(KothConfig config) {
        this.config = config;
        this.frozen = new Object2ObjectOpenHashMap<>();
    }

    private void start(long time) {
        this.startTime = time - (time % 20) + (4 * 20) + 19;
        this.finishTime = this.startTime + (config.timeLimitSecs() * 20);
        this.closeTime = -1;
    }

    public void onOpen(long time, KothConfig config, GameSpace space) {
        String line1 = "King of the Hill - get to the top of the hill and knock off others to win!";
        String line2;

        if (config.deathmatch()) {
            line1 = "King of the Hill Deathmatch! - knock off other players and stay on the platform!";
            line2 = "The last player standing wins!";
        } else if (config.knockoff()) {
            line1 = "King of the Hill Knock Off! - knock off other players and stay on the platform!";
            line2 = "Score points by knocking everyone from the platform!\n" +
                    "First player with " + config.firstTo() + " points wins!";
        } else if (!config.winnerTakesAll()) {
            line2 = "Score points by staying on top of the hill. Whoever reigns longest wins!\n" +
                    "If someone who is not the point-score leader is on the throne by the end, then the game will enter\n" +
                    " overtime until they become the ruler or are knocked off.";
        } else {
            line2 = "Whoever is on the throne when the game ends wins!\n"
                    + "If there are multiple people on the throne by the end, then the game will enter overtime.";
        }

        List<String> lines = new ArrayList<>();
        Collections.addAll(lines, line1, line2);

        if (this.config.firstTo() != 1 && !this.config.knockoff()) {
            lines.add("The game's winner will be whoever wins " + this.config.firstTo() + " rounds first.");
        }

        if (this.config.hasFeather()) {
            lines.add("Right-click with your feather to leap forwards.");
        }

        for (ServerPlayerEntity player : space.getPlayers()) {
            for (String line : lines) {
                Text text = Text.literal(line).formatted(Formatting.GOLD);
                player.sendMessage(text, false);
            }
        }

        this.start(time);
    }

    public TickResult tick(long time, GameSpace space, boolean overtime, boolean gameFinished) {
        // Game has finished. Wait a few seconds before finally closing the game.
        if (this.closeTime > 0) {
            if (time >= this.closeTime) {
                if (gameFinished) {
                    return TickResult.GAME_CLOSED;
                } else {
                    this.start(time); // Restart
                    return TickResult.NEXT_ROUND;
                }
            }
            return TickResult.TICK_FINISHED;
        }

        // Game hasn't started yet. Display a countdown before it begins.
        if (this.startTime > time) {
            this.tickStartWaiting(time, space);
            return TickResult.TICK_FINISHED;
        }

        if (this.config.knockoff() && gameFinished && this.closeTime == -1) {
            this.closeTime = time + (4 * 20);
            return TickResult.ROUND_FINISHED;
        }

        boolean noPlayers = space.getPlayers().size() == 0;
        if (this.config.deathmatch()) {
            int remainingPlayers = 0;
            for (ServerPlayerEntity player : space.getPlayers()) {
                if (!player.isSpectator()) {
                    remainingPlayers++;
                }
            }

            if (remainingPlayers <= 1) {
                noPlayers = true;
            }
        }

        // Game has just finished. Transition to the waiting-before-close state.
        if (time > this.finishTime || noPlayers) {
            if (!overtime) {
                this.closeTime = time + (2 * 20);
                return TickResult.ROUND_FINISHED;
            } else if (!this.config.deathmatch() && !this.config.knockoff()) {
                return TickResult.OVERTIME;
            } else if (noPlayers) { // Both eliminated at once
                this.closeTime = time + (2 * 20);
                return TickResult.ROUND_FINISHED;
            }
        }

        return TickResult.CONTINUE_TICK;
    }

    private void tickStartWaiting(long time, GameSpace space) {
        float sec_f = (this.startTime - time) / 20.0f;

        if (sec_f > 1) {
            for (ServerPlayerEntity player : space.getPlayers()) {
                if (player.isSpectator()) {
                    continue;
                }

                FrozenPlayer state = this.frozen.computeIfAbsent(player, p -> new FrozenPlayer());

                if (state.lastPos == null) {
                    state.lastPos = player.getPos();
                }

                double destX = state.lastPos.x;
                double destY = state.lastPos.y;
                double destZ = state.lastPos.z;

                // Set X and Y as relative so it will send 0 change when we pass yaw (yaw - yaw = 0) and pitch
                Set<Flag> flags = ImmutableSet.of(Flag.X_ROT, Flag.Y_ROT);

                // Teleport without changing the pitch and yaw
                player.networkHandler.requestTeleport(destX, destY, destZ, player.getYaw(), player.getPitch(), flags);
            }
        }

        int sec = (int) Math.floor(sec_f) - 1; // -1 because of "Go"

        PlayerSet players = space.getPlayers();
        if ((this.startTime - time) % 20 == 0) {
            if (sec > 0) {
                players.showTitle(Text.literal(Integer.toString(sec)).formatted(Formatting.BOLD), 20);
                players.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HARP);
            } else {
                players.showTitle(Text.literal("Go!").formatted(Formatting.BOLD), 20);
                players.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HARP, SoundCategory.PLAYERS, 1.0F, 2.0F);
            }
        }

        // 20 more ticks of invulnerability after this
    }

    public static class FrozenPlayer {
        public Vec3d lastPos;
    }

    public enum TickResult {
        CONTINUE_TICK,
        TICK_FINISHED,
        TICK_FINISHED_PLAYERS_FROZEN,
        NEXT_ROUND,
        ROUND_FINISHED,
        GAME_CLOSED,
        OVERTIME,
    }
}
