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

import _1ms.playtimeprotocol.SnapshotProtocol;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class RequestSender implements PluginMessageListener {
    private final Main main;
    private final LongSupplier clock;
    private BukkitTask pollTask;
    private volatile Map<String, Long> playtime = Map.of();
    private volatile Map<String, Long> pttop = Map.of();
    private volatile boolean reqTopList;
    private volatile boolean hasLeaderboard;
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<LinkedHashMap<String, Long>>(){}.getType();
    private final SnapshotProtocol.Assembler times;
    private final SnapshotProtocol.Assembler top;
    private UUID session;
    private boolean modern;
    private boolean isPreloaded;
    private boolean configured;
    private boolean requestedTop;
    private boolean helloSent;
    private boolean warned;
    private int attempts;
    private long lastHello;
    private long lastRequest;
    private long lastData;
    private long lastModernConfirmation;
    private long lastWarning;

    public RequestSender(Main main) { this(main, System::nanoTime); }
    RequestSender(Main main, LongSupplier clock) {
        this.main = main;
        this.clock = clock;
        times = new SnapshotProtocol.Assembler("pt2", clock);
        top = new SnapshotProtocol.Assembler("ptt2", clock);
        lastData = clock.getAsLong();
    }

    public Map<String, Long> getPlaytime() { return playtime; }
    public Map<String, Long> getPttop() { return pttop; }
    public boolean isReqTopList() { return reqTopList; }
    public void setReqTopList(boolean requested) { reqTopList = requested; }
    public boolean hasLeaderboard() { return hasLeaderboard; }
    public boolean isModernProtocol() { return modern; }

    public long getPlayTime(String name) {
        Map<String, Long> values = reqTopList && hasLeaderboard ? pttop : playtime;
        return values.getOrDefault(name, -1L);
    }

    public void runPlaytimeUpdates() {
        if(pollTask == null)
            pollTask = main.getServer().getScheduler().runTaskTimer(main, this::poll, 1L, 20L);
    }

    // PlaceholderAPI can invoke this from another thread; the polling task sends on the server thread.
    public void startGetTL() { reqTopList = true; }

    void poll() {
        times.expire();
        top.expire();
        if(main.getServer().getOnlinePlayers().isEmpty()) return;
        long now = clock.getAsLong();
        if(modern && now - lastModernConfirmation >= TimeUnit.SECONDS.toNanos(30)) {
            // Recover even if the proxy was downgraded and its restart packet was missed.
            modern = false;
            session = null;
            times.reset(null);
            top.reset(null);
            configured = false;
        }
        if(!helloSent || now - lastHello >= TimeUnit.SECONDS.toNanos(10)) {
            sendReq("hello2");
            helloSent = true;
            lastHello = now;
        }
        attempts++;
        // Give a new proxy a chance to negotiate before falling back to legacy requests.
        if(!modern && attempts < 2) return;
        if(!configured || (reqTopList && !requestedTop)
                || (modern && now - lastRequest >= TimeUnit.SECONDS.toNanos(10))
                || now - lastData >= TimeUnit.SECONDS.toNanos(5)) {
            String request = reqTopList ? "rtl" : "rpt";
            sendReq(modern ? request + "2" : request);
            requestedTop = reqTopList;
            lastRequest = now;
        }
    }

    private void sendReq(String message) {
        List<? extends Player> players = new ArrayList<>(main.getServer().getOnlinePlayers());
        if(players.isEmpty()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(message);
            players.getFirst().sendPluginMessage(main, "velocity:playtime", bytes.toByteArray());
        } catch(IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] bytes) {
        if(!channel.equals("velocity:playtime")) return;
        try {
            receive(bytes);
        } catch(IOException | RuntimeException failure) {
            long now = clock.getAsLong();
            if(!warned || now - lastWarning >= TimeUnit.SECONDS.toNanos(30)) {
                main.getLogger().warning("Ignored invalid playtime packet: " + failure.getMessage());
                lastWarning = now;
                warned = true;
            }
        }
    }

    void receive(byte[] bytes) throws IOException {
        if(bytes.length > SnapshotProtocol.MAX_PACKET_BYTES)
            throw new IOException("Packet exceeds size limit");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        String answer = in.readUTF();
        switch(answer) {
            case "conf2" -> {
                boolean preload = in.readBoolean();
                UUID incoming = new UUID(in.readLong(), in.readLong());
                if(!incoming.equals(session)) {
                    session = incoming;
                    times.reset(session);
                    top.reset(session);
                    playtime = Map.of();
                    pttop = Map.of();
                    hasLeaderboard = false;
                    configured = false;
                    requestedTop = false;
                }
                modern = true;
                isPreloaded = preload;
                lastModernConfirmation = clock.getAsLong();
            }
            case "pt2", "ptt2" -> {
                if(!modern) return;
                Optional<SnapshotProtocol.Snapshot> complete = (answer.equals("pt2") ? times : top).accept(bytes);
                if(complete.isEmpty()) return;
                var snapshot = complete.get();
                if(answer.equals("ptt2")) {
                    pttop = snapshot.values();
                    hasLeaderboard = true;
                } else if(snapshot.replace()) playtime = snapshot.values();
                else playtime = merged(playtime, snapshot.values());
                configured = true;
                lastData = clock.getAsLong();
            }
            case "pt", "ptt" -> {
                if(modern) return; // Never let a late legacy packet overwrite a v2 snapshot.
                Map<String, Long> values = gson.fromJson(in.readUTF(), mapType);
                validate(values);
                Map<String, Long> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(values));
                if(answer.equals("ptt")) {
                    pttop = immutable;
                    hasLeaderboard = true;
                } else playtime = isPreloaded ? merged(playtime, immutable) : immutable;
                configured = true;
                lastData = clock.getAsLong();
            }
            case "conf" -> {
                if(!modern) {
                    isPreloaded = in.readBoolean();
                    configured = true;
                }
            }
            case "rs" -> {
                sendReq("cc");
                session = null;
                times.reset(null);
                top.reset(null);
                modern = false;
                configured = false;
                requestedTop = false;
                helloSent = false;
                attempts = 0;
                hasLeaderboard = false;
                pttop = Map.of();
                playtime = Map.of();
            }
            default -> { }
        }
    }

    private static void validate(Map<String, Long> values) throws IOException {
        if(values == null) throw new IOException("Missing playtime map");
        for(var entry : values.entrySet()) {
            if(entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
                throw new IOException("Invalid playtime record");
        }
    }

    private static Map<String, Long> merged(Map<String, Long> previous, Map<String, Long> update) {
        Map<String, Long> merged = new LinkedHashMap<>(previous);
        merged.putAll(update);
        return Collections.unmodifiableMap(merged);
    }

    public void close() {
        if(pollTask != null) pollTask.cancel();
        pollTask = null;
        times.reset(null);
        top.reset(null);
    }
}
