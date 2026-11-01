/*      This file is part of the PlaytimeLink project.
        Copyright (C) 2024-2025 _1ms

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful,
        but WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
        GNU General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program.  If not, see <https://www.gnu.org/licenses/>. */

package _1ms.playtimelink;

import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

public final class Main extends JavaPlugin {

    public final HashMap<Long, String> rewardsH = new HashMap<>();
    private final RewardTracker rewardTracker = new RewardTracker();
    public RequestSender requestSender;
    public PlaceholderAPI placeholderAPI;
    public UpdateHandler updateHandler;
    @Getter
    private String loadingMsg;
    @Getter
    private String notfoundMsg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadingMsg = getConfig().getString("Messages.LOADING", "loading...");
        notfoundMsg = getConfig().getString("Messages.NOT_IN_TOPLIST", "Not in toplist");
        InitInstances();
        final boolean isUpdate = getConfig().getBoolean("Data.CHECK_FOR_UPDATES");
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderAPI.register();
        }
        if (getConfig().getBoolean("Data.BSTATS")) {
            Metrics metrics = new Metrics(this, 22917);
            metrics.addCustomChart(new SimplePie("updater", ()-> String.valueOf(isUpdate)));
            metrics.addCustomChart(new SimplePie("rewards", ()-> String.valueOf(rewardsH.size())));
        }
        getServer().getMessenger().registerIncomingPluginChannel(this, "velocity:playtime", requestSender);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "velocity:playtime");
        requestSender.runPlaytimeUpdates();
        if (isUpdate)
            Thread.ofVirtual().start(updateHandler::checkForUpdates);
        final ConfigurationSection confSec = getConfig().getConfigurationSection("Rewards");
        if (confSec != null) {
            final Set<String> set = Objects.requireNonNull(confSec).getKeys(true);
            set.iterator().forEachRemaining(key ->
                    rewardsH.put(Long.valueOf(key), getConfig().getString("Rewards." + key))
            );

            Bukkit.getScheduler().runTaskTimer(this, () -> {
                for(var player : getServer().getOnlinePlayers()) {
                    var commands = rewardTracker.update(player.getName(), requestSender.getPlayTime(player.getName()), rewardsH);
                    if(player.hasPermission("vptlink.rewards.exempt")) continue;
                    for(String command : commands)
                        Bukkit.dispatchCommand(getServer().getConsoleSender(), command.replace("%player%", player.getName()));
                }
            }, 0, 20);
        }

        getLogger().info("PlaytimeLink has been loaded.");
    }

    private void InitInstances() {
        requestSender = new RequestSender(this);
        placeholderAPI = new PlaceholderAPI(requestSender, this);
        updateHandler = new UpdateHandler(this);
    }

    @Override
    public void onDisable() {
        if(requestSender != null) requestSender.close();
        if(placeholderAPI != null) placeholderAPI.unregister();
    }
}
