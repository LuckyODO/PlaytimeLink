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

import _1ms.BuildConstants;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static _1ms.playtimelink.TimeConverter.calcTotalPT;
import static _1ms.playtimelink.TimeConverter.convert;

public class PlaceholderAPI extends PlaceholderExpansion {

    private final RequestSender requestSender;
    private final Main main;

    public PlaceholderAPI(RequestSender requestSender, Main main) {
        this.requestSender = requestSender;
        this.main = main;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "VPTlink";
    }

    @Override
    public @NotNull String getAuthor() {
        return "_1ms";
    }

    @Override
    public @NotNull String getVersion() {
        return BuildConstants.VERSION;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public boolean persist() {
        return true;
    }

    private static final java.util.regex.Pattern TOP = java.util.regex.Pattern.compile(
            "top(name|time)([1-9][0-9]*)(?:_(total)?(years|months|weeks|days|hours|minutes|seconds))?");
    private static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile(
            "(total)?(years|months|weeks|days|hours|minutes|seconds)");

    @Override
    public String onPlaceholderRequest(@Nullable Player player, @NotNull String id) {
        if(id.equals("place")) {
            if(player == null) return null;
            requestSender.startGetTL();
            Map<String, Long> snapshot = requestSender.getPttop();
            if(!requestSender.hasLeaderboard()) return main.getLoadingMsg();
            int place = 0;
            for(String name : snapshot.keySet()) {
                place++;
                if(name.equalsIgnoreCase(player.getName())) return Integer.toString(place);
            }
            return main.getNotfoundMsg();
        }
        var top = TOP.matcher(id);
        if(top.matches()) {
            boolean name = top.group(1).equals("name");
            if(name != (top.group(4) == null)) return null;
            final int rank;
            try { rank = Integer.parseInt(top.group(2)); }
            catch(NumberFormatException invalid) { return null; }
            requestSender.startGetTL();
            Map<String, Long> snapshot = requestSender.getPttop();
            if(!requestSender.hasLeaderboard()) return main.getLoadingMsg();
            int index = 0;
            for(var entry : snapshot.entrySet()) {
                if(++index == rank) {
                    if(name) return entry.getKey();
                    return top.group(3) != null ? calcTotalPT(entry.getValue(), top.group(4))
                            : convert(entry.getValue(), top.group(4));
                }
            }
            return main.getNotfoundMsg();
        }
        var time = TIME.matcher(id);
        if(!time.matches() || player == null) return null;
        long value = requestSender.getPlayTime(player.getName());
        if(value < 0) return main.getLoadingMsg();
        return time.group(1) != null ? calcTotalPT(value, time.group(2)) : convert(value, time.group(2));
    }
}
