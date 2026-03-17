package com.aiproxyoauth.usage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UsageTrackerTest {

    @Test void emptySnapshot() {
        UsageTracker tracker = new UsageTracker();
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test void recordSingleKey() {
        UsageTracker tracker = new UsageTracker();
        tracker.record("cursor", 100, 50);

        Map<String, UsageTracker.KeyStats> snap = tracker.snapshot();
        assertEquals(1, snap.size());
        assertEquals(100, snap.get("cursor").promptTokens());
        assertEquals(50, snap.get("cursor").completionTokens());
        assertEquals(150, snap.get("cursor").totalTokens());
    }

    @Test void recordAccumulatesAcrossCalls() {
        UsageTracker tracker = new UsageTracker();
        tracker.record("cursor", 100, 50);
        tracker.record("cursor", 200, 80);

        UsageTracker.KeyStats stats = tracker.snapshot().get("cursor");
        assertEquals(300, stats.promptTokens());
        assertEquals(130, stats.completionTokens());
        assertEquals(430, stats.totalTokens());
    }

    @Test void recordMultipleKeysIsolated() {
        UsageTracker tracker = new UsageTracker();
        tracker.record("cursor", 100, 50);
        tracker.record("vscode", 200, 80);

        Map<String, UsageTracker.KeyStats> snap = tracker.snapshot();
        assertEquals(2, snap.size());
        assertEquals(100, snap.get("cursor").promptTokens());
        assertEquals(200, snap.get("vscode").promptTokens());
    }

    @Test void nullKeyName_recordsUnderAggregateKey() {
        UsageTracker tracker = new UsageTracker();
        tracker.record(null, 100, 50);
        Map<String, UsageTracker.KeyStats> snap = tracker.snapshot();
        assertEquals(1, snap.size());
        UsageTracker.KeyStats stats = snap.get(UsageTracker.OPEN_MODE_KEY);
        assertNotNull(stats);
        assertEquals(100, stats.promptTokens());
        assertEquals(50, stats.completionTokens());
    }

    @Test void snapshotIsSortedByName() {
        UsageTracker tracker = new UsageTracker();
        tracker.record("zapp", 1, 1);
        tracker.record("alpha", 2, 2);
        tracker.record("middle", 3, 3);

        var keys = tracker.snapshot().keySet().toArray();
        assertEquals("alpha",  keys[0]);
        assertEquals("middle", keys[1]);
        assertEquals("zapp",   keys[2]);
    }

    @Test void snapshotIsIndependentOfTracker() {
        UsageTracker tracker = new UsageTracker();
        tracker.record("cursor", 100, 50);
        Map<String, UsageTracker.KeyStats> snap1 = tracker.snapshot();

        tracker.record("cursor", 50, 25);
        Map<String, UsageTracker.KeyStats> snap2 = tracker.snapshot();

        // snap1 reflects state at time of first snapshot call
        assertEquals(100, snap1.get("cursor").promptTokens());
        // snap2 reflects accumulated state
        assertEquals(150, snap2.get("cursor").promptTokens());
    }
}
