package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class RecommendationSourcesTest {
    @Test public void groupingUsesPackageIdentityAndKeepsEveryEligibleShelfInOrder() throws Exception {
        ChannelRepository.Channel a = channel("pkg.alpha", 1), b = channel("pkg.beta", 2), c = channel("pkg.alpha", 3);
        List<RecommendationSources.Source> groups = RecommendationSources.group(discovery(Arrays.asList(a, b, c, a)));
        assertEquals(2, groups.size()); assertEquals("pkg.alpha", groups.get(0).packageName);
        assertEquals(Arrays.asList(a, c), groups.get(0).channels); assertEquals(Collections.singletonList(b), groups.get(1).channels);
        try { groups.get(0).channels.clear(); fail("Channels must be immutable"); } catch (UnsupportedOperationException expected) { }
        try { groups.clear(); fail("Groups must be immutable"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void mergeInterleavesShelvesDeduplicatesExactContentAndRetainsLaunchBindings() throws Exception {
        ChannelRepository.Channel a = channel("pkg.video", 1), b = channel("pkg.video", 2);
        ChannelRepository.Program first = program(a, 1, "Same title", "fixture://shared");
        ChannelRepository.Program next = program(a, 2, "Same title", "fixture://different");
        ChannelRepository.Program duplicate = program(b, 3, "Other title", "fixture://shared");
        ChannelRepository.Program fromSecond = program(b, 4, "Same title", "fixture://second");
        RecommendationsSession.AppPrograms merged = RecommendationsSession.aggregatePrograms("pkg.video", Arrays.asList(a, b),
                Arrays.asList(programs(b, Arrays.asList(duplicate, fromSecond)), programs(a, Arrays.asList(first, next))), 0, false);
        assertEquals(Arrays.asList(first, fromSecond, next), merged.items);
        assertSame(a, merged.channelFor(first)); assertSame(b, merged.channelFor(fromSecond));
        assertEquals(2, merged.loadedChannels); assertEquals(0, merged.failedChannels);
        assertEquals(ChannelRepository.Status.READY, merged.status);
        assertEquals(ChannelRepository.Completeness.FULL, merged.completeness);
        assertNull(merged.channelFor(program(channel("pkg.other", 1), 1, "Same title", "fixture://shared")));
    }
    @Test public void mixedFailureKeepsSuccessfulPublicationsAndReportsPartial() throws Exception {
        ChannelRepository.Channel a = channel("pkg.video", 1), b = channel("pkg.video", 2);
        ChannelRepository.Program item = program(a, 1, "Good", "fixture://good");
        RecommendationsSession.AppPrograms merged = RecommendationsSession.aggregatePrograms("pkg.video", Arrays.asList(a, b),
                Arrays.asList(programs(a, Collections.singletonList(item)), result(b, ChannelRepository.Status.QUERY_FAILED, Collections.emptyList())), 0, false);
        assertEquals(Collections.singletonList(item), merged.items); assertSame(a, merged.channelFor(item));
        assertEquals(1, merged.loadedChannels); assertEquals(1, merged.failedChannels);
        assertEquals(ChannelRepository.Status.PARTIAL, merged.status);
        assertEquals(ChannelRepository.Completeness.UNKNOWN, merged.completeness);
    }
    @Test public void foreignChannelItemsAreRejectedInsteadOfAcquiringAnotherLaunchBinding() throws Exception {
        ChannelRepository.Channel a = channel("pkg.video", 1), b = channel("pkg.video", 2);
        RecommendationsSession.AppPrograms merged = RecommendationsSession.aggregatePrograms("pkg.video", Arrays.asList(a, b),
                Collections.singletonList(programs(a, Collections.singletonList(program(b, 1, "Wrong", "fixture://wrong")))), 0, false);
        assertTrue(merged.items.isEmpty()); assertEquals(1, merged.failedChannels);
        assertEquals(ChannelRepository.Status.QUERY_FAILED, merged.status);
    }
    @Test public void cappedAggregateRetainsOnlyItsBoundedItemsNotTheOriginalPublisherLists() throws Exception {
        List<ChannelRepository.Channel> channels = new ArrayList<>(); List<ChannelRepository.Programs> results = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            ChannelRepository.Channel channel = channel("pkg.video", i); channels.add(channel);
            List<ChannelRepository.Program> items = new ArrayList<>();
            for (int j = 1; j <= 50; j++) items.add(program(channel, j, "Same title", "fixture://" + i + "/" + j));
            results.add(programs(channel, items));
        }
        RecommendationsSession.AppPrograms merged = RecommendationsSession.aggregatePrograms("pkg.video", channels, results, 0, false);
        assertEquals(RecommendationsSession.MAX_APP_ITEMS, merged.items.size());
        assertEquals(35, merged.totalChannels); assertEquals(32, merged.loadedChannels);
        assertEquals(ChannelRepository.Completeness.CAPPED, merged.completeness);
        Set<ChannelRepository.Program> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        collectPrograms(merged, Collections.newSetFromMap(new IdentityHashMap<>()), retained);
        assertEquals("The aggregate must not retain full Programs payloads behind its channel lookup", merged.items.size(), retained.size());
        Set<String> represented = new java.util.HashSet<>();
        for (ChannelRepository.Program item : merged.items) { represented.add(item.channelKey); assertNotNull(merged.channelFor(item)); }
        assertEquals("Every read shelf contributes before extra items fill the app cap", 32, represented.size());
    }
    private static void collectPrograms(Object value, Set<Object> seen, Set<ChannelRepository.Program> programs) throws Exception {
        if (value == null || !seen.add(value)) return;
        if (value instanceof ChannelRepository.Program) { programs.add((ChannelRepository.Program) value); return; }
        if (value instanceof Iterable<?>) { for (Object child : (Iterable<?>) value) collectPrograms(child, seen, programs); return; }
        if (value instanceof Map<?, ?>) { for (Object child : ((Map<?, ?>) value).values()) collectPrograms(child, seen, programs); return; }
        if (!value.getClass().getName().startsWith("io.github.ekkzy.evebox.data.")) return;
        for (Field field : value.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true); collectPrograms(field.get(value), seen, programs);
        }
    }
    private static ChannelRepository.Channel channel(String pkg, long id) { return new ChannelRepository.Channel(0, pkg, 100, 1, id, "Shelf" + id, "Video app"); }
    private static ChannelRepository.Discovery discovery(List<ChannelRepository.Channel> channels) throws Exception {
        Constructor<ChannelRepository.Discovery> constructor = ChannelRepository.Discovery.class.getDeclaredConstructor(
                ChannelRepository.Status.class, ChannelRepository.Completeness.class, List.class, List.class, List.class, ChannelRepository.Stats.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ChannelRepository.Status.READY, ChannelRepository.Completeness.FULL, channels, channels, Collections.emptyList(), null);
    }
    private static ChannelRepository.Program program(ChannelRepository.Channel channel, long id, String title, String uri) throws Exception {
        Constructor<ChannelRepository.Program> constructor = ChannelRepository.Program.class.getDeclaredConstructor(ChannelRepository.Channel.class,
                long.class, String.class, String.class, String.class, String.class, Long.class, Integer.class, Integer.class, String.class);
        constructor.setAccessible(true); return constructor.newInstance(channel, id, title, "", uri, "", null, null, null, "");
    }
    private static ChannelRepository.Programs programs(ChannelRepository.Channel channel, List<ChannelRepository.Program> items) throws Exception {
        return result(channel, ChannelRepository.Status.READY, items);
    }
    private static ChannelRepository.Programs result(ChannelRepository.Channel channel, ChannelRepository.Status status, List<ChannelRepository.Program> items) throws Exception {
        Constructor<ChannelRepository.Programs> constructor = ChannelRepository.Programs.class.getDeclaredConstructor(ChannelRepository.Status.class,
                ChannelRepository.Completeness.class, ChannelRepository.Channel.class, List.class, ChannelRepository.Stats.class);
        constructor.setAccessible(true); return constructor.newInstance(status, ChannelRepository.Completeness.FULL, channel, items, null);
    }
}
