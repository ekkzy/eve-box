package io.github.ekkzy.softlumi.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Groups eligible provider rows without turning discovery into user consent. */
public final class RecommendationSources {
    private RecommendationSources() { }

    public static final class Source {
        public final String packageName, appLabel;
        public final List<ChannelRepository.Channel> channels;
        private Source(String packageName, String appLabel, List<ChannelRepository.Channel> channels) {
            this.packageName = packageName;
            this.appLabel = appLabel == null || appLabel.isEmpty() ? packageName : appLabel;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        }
    }

    public static List<Source> group(ChannelRepository.Discovery discovery) {
        if (discovery == null) return Collections.emptyList();
        Map<String, List<ChannelRepository.Channel>> groups = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        int visited = 0;
        for (ChannelRepository.Channel channel : discovery.selectableChannels) {
            if (++visited > ChannelRepository.MAX_CHANNEL_ROWS) break;
            if (channel == null || !LauncherPrefs.validPackageName(channel.packageName) || !seen.add(channel.key)) continue;
            groups.computeIfAbsent(channel.packageName, ignored -> new ArrayList<>()).add(channel);
        }
        List<Source> result = new ArrayList<>();
        for (Map.Entry<String, List<ChannelRepository.Channel>> group : groups.entrySet()) {
            List<ChannelRepository.Channel> channels = group.getValue();
            result.add(new Source(group.getKey(), channels.get(0).appLabel, channels));
        }
        return Collections.unmodifiableList(result);
    }
}
