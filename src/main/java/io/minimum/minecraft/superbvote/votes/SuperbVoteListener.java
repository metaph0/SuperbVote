package io.minimum.minecraft.superbvote.votes;

import com.vexsoftware.votifier.model.VotifierEvent;
import io.minimum.minecraft.superbvote.SuperbVote;
import io.minimum.minecraft.superbvote.commands.SuperbVoteCommand;
import io.minimum.minecraft.superbvote.configuration.message.MessageContext;
import io.minimum.minecraft.superbvote.signboard.TopPlayerSignFetcher;
import io.minimum.minecraft.superbvote.storage.MysqlVoteStorage;
import io.minimum.minecraft.superbvote.storage.VoteStorage;
import io.minimum.minecraft.superbvote.util.BrokenNag;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import io.minimum.minecraft.superbvote.votes.rewards.VoteReward;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class SuperbVoteListener implements Listener {
    private final SuperbVote plugin;
    
    public SuperbVoteListener(SuperbVote plugin) {
        this.plugin = plugin;
        }
    
    @EventHandler
    public void onVote(final VotifierEvent event) {
        if (plugin.getConfiguration().isConfigurationError()) {
            plugin.getLogger().severe("Refusing to process vote because your configuration is invalid. Please check your logs.");
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(event.getVote().getUsername());

            if (op == null) {
                op = Bukkit.getOfflinePlayerIfCached("." + event.getVote().getUsername());
            }

            if (op == null || !op.hasPlayedBefore()) {
                op = Bukkit.getOfflinePlayer(event.getVote().getUsername());

                if (!op.hasPlayedBefore()) {
                    op = Bukkit.getOfflinePlayer("." + event.getVote().getUsername());
                }
            }
            
            if (!op.hasPlayedBefore()) {
                return;
            }
            
            String worldName = null;
            if (op.isOnline()) {
                worldName = op.getPlayer().getWorld().getName();
            }

            VoteStorage voteStorage = plugin.getVoteStorage();
            VoteStreak voteStreak = voteStorage.getVoteStreakIfSupported(op.getUniqueId(), false);
            PlayerVotes pvCurrent = voteStorage.getVotes(op.getUniqueId());
            PlayerVotes pv = new PlayerVotes(op.getUniqueId(), op.getName(), pvCurrent.getVotes() + 1, PlayerVotes.Type.FUTURE);
            Vote vote = new Vote(op.getName(), op.getUniqueId(), event.getVote().getServiceName(),
                    event.getVote().getAddress().equals(SuperbVoteCommand.FAKE_HOST_NAME_FOR_VOTE), worldName, new Date());

            if (!vote.isFakeVote()) {
                if (plugin.getConfiguration().getStreaksConfiguration().isSharedCooldownPerService()) {
                    if (voteStreak == null) {
                        // becomes a required value
                        voteStreak = voteStorage.getVoteStreakIfSupported(op.getUniqueId(), true);
                    }
                    if (voteStreak != null && voteStreak.getServices().containsKey(vote.getServiceName())) {
                        long difference = plugin.getVoteServiceCooldown().getMax() - voteStreak.getServices().get(vote.getServiceName());
                        if (difference > 0) {
                            plugin.getLogger().log(Level.WARNING, "Ignoring vote from " + vote.getName() + " (service: " +
                                    vote.getServiceName() + ") due to [shared] service cooldown.");
                            return;
                        }
                    }
                }

                if (plugin.getVoteServiceCooldown().triggerCooldown(vote)) {
                    plugin.getLogger().log(Level.WARNING, "Ignoring vote from " + vote.getName() + " (service: " +
                            vote.getServiceName() + ") due to service cooldown.");
                    return;
                }
            }

            processVote(pv, voteStreak, vote, plugin.getConfig().getBoolean("broadcast.enabled"),
                    !op.isOnline() && plugin.getConfiguration().requirePlayersOnline(),
                    false);
        });
    }

    private void processVote(PlayerVotes pv, VoteStreak voteStreak, Vote vote, boolean broadcast, boolean queue, boolean wasQueued) {
        List<VoteReward> bestRewards = plugin.getConfiguration().getBestRewards(vote, pv);
        MessageContext context = new MessageContext(vote, pv, voteStreak, Bukkit.getOfflinePlayer(vote.getUuid()));
        boolean canBroadcast = plugin.getRecentVotesStorage().canBroadcast(vote.getUuid());
        plugin.getRecentVotesStorage().updateLastVote(vote.getUuid());

        Optional<Player> player = context.getPlayer().map(OfflinePlayer::getPlayer);
        boolean hideBroadcast = player.isPresent() && player.get().hasPermission("superbvote.bypassbroadcast");

        if (bestRewards.isEmpty()) {
            throw new RuntimeException("No vote rewards found for '" + vote + "'");
        }

        if (queue) {
            if (!plugin.getConfiguration().shouldQueueVotes()) {
                plugin.getLogger().log(Level.WARNING, "Ignoring vote from " + vote.getName() + " (service: " +
                        vote.getServiceName() + ") because they aren't online.");
                return;
            }

            plugin.getLogger().log(Level.INFO, "Queuing vote from " + vote.getName() + " to be run later");
            for (VoteReward reward : bestRewards) {
                reward.broadcastVote(context, false, broadcast && plugin.getConfig().getBoolean("broadcast.queued") && canBroadcast && !hideBroadcast);
            }
            plugin.getQueuedVotes().addVote(vote);
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> afterVoteProcessing());
        } else {
            if (!vote.isFakeVote() || plugin.getConfig().getBoolean("votes.process-fake-votes")) {
                plugin.getVoteStorage().addVote(vote);
            }

            if (!wasQueued) {
                for (VoteReward reward : bestRewards) {
                    reward.broadcastVote(context, plugin.getConfig().getBoolean("broadcast.message-player"), broadcast && canBroadcast && !hideBroadcast);
                }

                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> afterVoteProcessing());
            }

            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> bestRewards.forEach(reward -> reward.runCommands(vote)));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        
        if (plugin.getConfiguration().isConfigurationError() && player.hasPermission("superbvote.admin")) {
            player.getScheduler().runDelayed(plugin, task -> BrokenNag.nag(player), () -> {}, 40L);
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            // Update names in MySQL, if it is being used.
            if (plugin.getVoteStorage() instanceof MysqlVoteStorage sqlStorage) {
                sqlStorage.updateName(event.getPlayer());
            }

            // Process queued votes.
            VoteStorage voteStorage = plugin.getVoteStorage();
            UUID playerUUID = event.getPlayer().getUniqueId();
            PlayerVotes pv = voteStorage.getVotes(playerUUID);
            VoteStreak voteStreak = voteStorage.getVoteStreakIfSupported(playerUUID, false);
            List<Vote> votes = plugin.getQueuedVotes().getAndRemoveVotes(playerUUID);
            if (!votes.isEmpty()) {
                for (Vote vote : votes) {
                    processVote(pv, voteStreak, vote, false, false, true);
                    pv = new PlayerVotes(pv.getUuid(), event.getPlayer().getName(),pv.getVotes() + 1, PlayerVotes.Type.CURRENT);
                }
                // afterVoteProcessing();
            }

            // Remind players to vote.
            if (plugin.getConfig().getBoolean("vote-reminder.on-join") &&
                    event.getPlayer().hasPermission("superbvote.notify") &&
                    !plugin.getVoteStorage().hasVotedToday(event.getPlayer().getUniqueId())) {
                MessageContext context = new MessageContext(null, pv, voteStreak, event.getPlayer());
                plugin.getConfiguration().getReminderMessage().sendAsReminder(event.getPlayer(), context);
            }
        });
    }

    private void afterVoteProcessing() {
        plugin.getScoreboardHandler().doPopulate();
        new TopPlayerSignFetcher(plugin.getTopPlayerSignStorage().getSignList()).run();

        plugin.getVoteParty().countVote();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) {
            plugin.getLogger().info("Using clip's PlaceholderAPI to provide extra placeholders.");
        }
    }
}
