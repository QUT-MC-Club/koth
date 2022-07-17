package io.github.restioson.koth.game;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.common.widget.SidebarWidget;

import java.util.List;

public class KothScoreboard {
    private final SidebarWidget sidebar;
    private final boolean winnerTakesAll;
    private final boolean deathMatch;
    private final boolean knockoff;

    public KothScoreboard(GlobalWidgets widgets, String name, boolean wta, boolean dm, boolean ko) {
        this.sidebar = widgets.addSidebar(
                Text.literal(name).formatted(Formatting.BLUE, Formatting.BOLD)
        );
        this.winnerTakesAll = wta;
        this.deathMatch = dm;
        this.knockoff = ko;
    }

    public void renderTitle() {
        this.sidebar.set(content -> {
        });
    }

    public void render(List<KothPlayer> leaderboard) {
        this.sidebar.set(content -> {
            for (KothPlayer entry : leaderboard) {
                String line;

                if (this.winnerTakesAll) {
                    line = String.format("Ruler: %s%s%s", Formatting.AQUA, entry.player.getEntityName(), Formatting.RESET);
                } else if (this.deathMatch) {
                    line = String.format(
                            "%s%s%s: %d rounds",
                            Formatting.AQUA,
                            entry.player.getEntityName(),
                            Formatting.RESET,
                            entry.wins
                    );
                } else if (this.knockoff) {
                    line = String.format(
                            "%s%s%s: %d points",
                            Formatting.AQUA,
                            entry.player.getEntityName(),
                            Formatting.RESET,
                            entry.score
                    );
                } else {
                    line = String.format(
                            "%s%s%s: %ds",
                            Formatting.AQUA,
                            entry.player.getEntityName(),
                            Formatting.RESET,
                            entry.score
                    );
                }

                content.add(Text.literal(line));
            }
        });
    }
}
