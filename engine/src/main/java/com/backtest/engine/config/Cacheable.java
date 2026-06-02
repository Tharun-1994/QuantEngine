package com.backtest.engine.config;


/**
 * Contract for any resource that can be managed by {@link FileBackedCache}.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Track a "cached" flag so that {@code close()} is a no-op when the cache owns lifecycle.</li>
 *   <li>Release underlying resources (e.g. off-heap Arrow buffers) when {@code close()} is called
 *       AFTER the cache flips {@code cached} back to {@code false} during eviction.</li>
 * </ul>
 *
 * <p>Extending {@link AutoCloseable} is semantically correct (a cacheable thing is a closeable
 * thing) and lets the cache's RemovalListener handle eviction via the standard try-with-resources
 * pattern.
 */
public interface Cacheable extends AutoCloseable {

    /** Mark this resource as owned by a cache (or release ownership). */
    void setCached(boolean cached);

    /** True while this resource is cache-owned; close() should be a no-op. */
    boolean isCached();

    /**
     * Release underlying resources. Cache-owned instances should make this a no-op when
     * {@link #isCached()} is {@code true}; the cache will flip the flag before calling close
     * during eviction.
     */
    @Override
    void close() throws Exception;
}
