package dev.bookforge.adminapi.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link StepUpTokenStore} backed by a {@link ConcurrentHashMap}. Suitable for the
 * dev/test single-task admin-api; production must replace this with a Redis or DynamoDB-backed
 * implementation so the dedupe carries across tasks.
 *
 * <p>The map maps {@code challengeId → expiresAtEpochSeconds}. Entries whose expiry has elapsed
 * are evicted lazily on every {@code markConsumedOnce} call so the store cannot grow without
 * bound under sustained admin traffic. Eviction does not race with the consume check itself —
 * the {@link ConcurrentHashMap#putIfAbsent} below is the single source of truth for first-use.
 */
@Component
public class InMemoryStepUpTokenStore implements StepUpTokenStore {

    private final ConcurrentHashMap<String, Long> consumed = new ConcurrentHashMap<>();

    @Override
    public boolean markConsumedOnce(String challengeId, long expiresAtEpochSeconds) {
        evictExpired();
        Long prior = consumed.putIfAbsent(challengeId, expiresAtEpochSeconds);
        return prior == null;
    }

    private void evictExpired() {
        long now = Instant.now().getEpochSecond();
        consumed.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}
