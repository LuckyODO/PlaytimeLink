package _1ms.playtimeprotocol;

import java.io.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Version 2 snapshot protocol. Keep this file identical in both plugins. */
public final class SnapshotProtocol {
    public static final int MAX_PACKET_BYTES = 32766;
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_CHUNKS = 4096;
    public static final long TIMEOUT_NANOS = 30_000_000_000L;
    private SnapshotProtocol() {}

    public record Snapshot(String type, UUID session, long sequence, boolean replace, Map<String, Long> values) {}
    private record Chunk(String type, UUID session, long sequence, int index, int count,
                         boolean replace, Map<String, Long> values) {}

    public static List<byte[]> encode(String type, UUID session, long sequence,
                                      boolean replace, Map<String, Long> values) {
        if((!type.equals("pt2") && !type.equals("ptt2")) || sequence <= 0)
            throw new IllegalArgumentException("Invalid snapshot header");
        try {
            List<byte[]> payloads = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            int entries = 0;
            int total = 0;
            // UTF type, UUID, sequence, index, count, replace flag, entry count.
            int header = 2 + type.length() + 16 + 8 + 4 + 4 + 1 + 4;
            for(var entry : values.entrySet()) {
                if(entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 0)
                    throw new IllegalArgumentException("Invalid player record");
                ByteArrayOutputStream record = new ByteArrayOutputStream();
                DataOutputStream recordOut = new DataOutputStream(record);
                recordOut.writeUTF(entry.getKey());
                recordOut.writeLong(entry.getValue());
                if(record.size() + header > MAX_PACKET_BYTES)
                    throw new IllegalArgumentException("Player record exceeds packet limit");
                if(payload.size() + record.size() + header > MAX_PACKET_BYTES) {
                    payloads.add(payload.toByteArray());
                    sizes.add(entries);
                    total += payload.size() + header;
                    payload.reset();
                    entries = 0;
                    if(total > MAX_SNAPSHOT_BYTES || payloads.size() >= MAX_CHUNKS)
                        throw new IllegalArgumentException("Snapshot exceeds protocol limit");
                }
                record.writeTo(payload);
                entries++;
            }
            payloads.add(payload.toByteArray());
            sizes.add(entries);
            if(total + payload.size() + header > MAX_SNAPSHOT_BYTES)
                throw new IllegalArgumentException("Snapshot exceeds protocol limit");
            List<byte[]> packets = new ArrayList<>();
            for(int i = 0; i < payloads.size(); i++) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(type);
                out.writeLong(session.getMostSignificantBits());
                out.writeLong(session.getLeastSignificantBits());
                out.writeLong(sequence);
                out.writeInt(i);
                out.writeInt(payloads.size());
                out.writeBoolean(replace);
                out.writeInt(sizes.get(i));
                out.write(payloads.get(i));
                packets.add(bytes.toByteArray());
            }
            return List.copyOf(packets);
        } catch(IOException error) {
            throw new IllegalArgumentException("Cannot encode snapshot", error);
        }
    }

    private static Chunk decode(byte[] bytes) throws IOException {
        if(bytes.length > MAX_PACKET_BYTES)
            throw new IOException("Packet exceeds protocol limit");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        String type = in.readUTF();
        if(!type.equals("pt2") && !type.equals("ptt2"))
            throw new IOException("Unknown snapshot type");
        UUID session = new UUID(in.readLong(), in.readLong());
        long sequence = in.readLong();
        int index = in.readInt();
        int count = in.readInt();
        boolean replace = in.readBoolean();
        int entries = in.readInt();
        if(sequence <= 0 || count < 1 || count > MAX_CHUNKS || index < 0 || index >= count
                || entries < 0 || entries > MAX_PACKET_BYTES / 10)
            throw new IOException("Invalid snapshot header");
        Map<String, Long> values = new LinkedHashMap<>();
        for(int i = 0; i < entries; i++) {
            String name = in.readUTF();
            long time = in.readLong();
            if(name.isBlank() || time < 0 || values.putIfAbsent(name, time) != null)
                throw new IOException("Invalid or duplicate player record");
        }
        if(in.available() != 0)
            throw new IOException("Unexpected trailing snapshot bytes");
        return new Chunk(type, session, sequence, index, count, replace, values);
    }

    /** One assembler per snapshot type. Incomplete snapshots never escape this object. */
    public static final class Assembler {
        private final String type;
        private final LongSupplier clock;
        private UUID session;
        private long discardedThrough;
        private long activeSequence;
        private boolean replace;
        private int chunkCount;
        private int bytesReceived;
        private long started;
        private final Map<Integer, Map<String, Long>> chunks = new HashMap<>();

        public Assembler(String type) { this(type, System::nanoTime); }
        public Assembler(String type, LongSupplier clock) {
            this.type = type;
            this.clock = clock;
        }

        public synchronized void reset(UUID session) {
            this.session = session;
            discardedThrough = 0;
            clearPending();
        }

        private void clearPending() {
            chunks.clear();
            activeSequence = 0;
            bytesReceived = 0;
        }

        public synchronized void expire() {
            if(activeSequence != 0 && clock.getAsLong() - started >= TIMEOUT_NANOS) {
                discardedThrough = Math.max(discardedThrough, activeSequence);
                clearPending();
            }
        }

        public synchronized Optional<Snapshot> accept(byte[] bytes) throws IOException {
            expire();
            Chunk chunk = decode(bytes);
            if(!chunk.type.equals(type) || !chunk.session.equals(session) || chunk.sequence <= discardedThrough)
                return Optional.empty();
            if(activeSequence != 0 && chunk.sequence < activeSequence)
                return Optional.empty();
            if(chunk.sequence != activeSequence) {
                if(activeSequence != 0)
                    discardedThrough = Math.max(discardedThrough, activeSequence);
                clearPending();
                activeSequence = chunk.sequence;
                chunkCount = chunk.count;
                replace = chunk.replace;
                started = clock.getAsLong();
            }
            if(chunk.count != chunkCount || chunk.replace != replace)
                throw new IOException("Inconsistent snapshot header");
            Map<String, Long> existing = chunks.get(chunk.index);
            if(existing != null) {
                if(!new ArrayList<>(existing.entrySet()).equals(new ArrayList<>(chunk.values.entrySet())))
                    throw new IOException("Conflicting duplicate chunk");
                return Optional.empty();
            }
            bytesReceived += bytes.length;
            if(bytesReceived > MAX_SNAPSHOT_BYTES) {
                discardedThrough = activeSequence;
                clearPending();
                throw new IOException("Snapshot exceeds memory limit");
            }
            chunks.put(chunk.index, chunk.values);
            if(chunks.size() != chunkCount)
                return Optional.empty();
            Map<String, Long> complete = new LinkedHashMap<>();
            for(int i = 0; i < chunkCount; i++) {
                for(var entry : chunks.get(i).entrySet()) {
                    if(complete.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                        throw new IOException("Player occurs in multiple chunks");
                }
            }
            Snapshot snapshot = new Snapshot(type, session, activeSequence, replace,
                    Collections.unmodifiableMap(complete));
            discardedThrough = activeSequence;
            clearPending();
            return Optional.of(snapshot);
        }
    }
}
