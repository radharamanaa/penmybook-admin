package dev.bookforge.adminapi.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryStepUpTokenStore} — the single-use replay guard for admin step-up
 * tokens. First use of a challenge id must succeed; a second use within TTL must be rejected as a
 * replay; and an entry whose TTL has elapsed must be evicted so the store stays bounded (and so a
 * fresh challenge that happens to reuse an old, expired id is honored).
 */
class InMemoryStepUpTokenStoreTest {

    private final InMemoryStepUpTokenStore store = new InMemoryStepUpTokenStore();

    private static long future() {
        return Instant.now().getEpochSecond() + 300;
    }

    private static long past() {
        return Instant.now().getEpochSecond() - 1;
    }

    @Test
    void firstUseSucceeds() {
        assertThat(store.markConsumedOnce("chal-1", future())).isTrue();
    }

    @Test
    void secondUseWithinTtlIsRejectedAsReplay() {
        assertThat(store.markConsumedOnce("chal-2", future())).isTrue();
        assertThat(store.markConsumedOnce("chal-2", future())).isFalse();
    }

    @Test
    void distinctChallengeIdsDoNotInterfere() {
        assertThat(store.markConsumedOnce("a", future())).isTrue();
        assertThat(store.markConsumedOnce("b", future())).isTrue();
    }

    @Test
    void expiredEntryIsEvictedAndCanBeConsumedAgain() {
        // Consume with an already-elapsed expiry: the entry is stored but the next call evicts it.
        assertThat(store.markConsumedOnce("chal-3", past())).isTrue();
        // Eviction runs at the head of markConsumedOnce → the stale entry is gone → first-use again.
        assertThat(store.markConsumedOnce("chal-3", future())).isTrue();
    }

    @Test
    void evictingOneExpiredEntryDoesNotDropALiveOne() {
        assertThat(store.markConsumedOnce("live", future())).isTrue();
        assertThat(store.markConsumedOnce("stale", past())).isTrue();
        // This call evicts "stale" but must keep "live" consumed (still a replay).
        assertThat(store.markConsumedOnce("trigger", future())).isTrue();
        assertThat(store.markConsumedOnce("live", future())).isFalse();
    }
}
