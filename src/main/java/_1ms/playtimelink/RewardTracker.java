package _1ms.playtimelink;

import java.util.*;

/** Observed progress, so a cached snapshot cannot repeatedly trigger the same reward. */
final class RewardTracker {
    private final Map<String, Long> previousTimes = new HashMap<>();

    List<String> update(String player, long time, Map<Long, String> rewards) {
        if(time < 0) return List.of();
        Long previous = previousTimes.put(player, time);
        return rewards.entrySet().stream()
                .filter(entry -> previous == null ? entry.getKey() == time
                        : previous < entry.getKey() && entry.getKey() <= time)
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).toList();
    }
}
