package com.backtest.engine.config;



import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import jakarta.annotation.PreDestroy;

/**
 * Generic read-through, mtime-invalidated, file-backed cache for any {@link Cacheable} resource.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Caching policy</b> (eviction algorithm, size cap, stats) lives entirely here. Subclasses
 *       only declare <i>how to load</i> their type from disk via {@link #loadFromDisk(String)}.</li>
 *   <li><b>Cache key</b> = canonical absolute file path. URIs (file:///...) and plain paths
 *       collapse to the same key.</li>
 *   <li><b>Freshness</b> via filesystem mtime: {@code File.lastModified()} is compared on every
 *       hit; mismatch invalidates and reloads. Handles overnight parquet refreshes without
 *       requiring engine restart.</li>
 *   <li><b>Concurrency</b>: Caffeine's {@code Cache.get(key, loader)} guarantees the loader runs
 *       at most once per key under concurrent access (thundering-herd prevention).</li>
 *   <li><b>Off-heap lifecycle</b>: the {@link Cacheable#setCached(boolean)} flag suppresses
 *       request-scoped close() calls. Eviction (via Caffeine's RemovalListener) is the only path
 *       that releases resources, by flipping the flag back to false and then calling close().</li>
 * </ul>
 *
 * <h2>Subclassing</h2>
 * <pre>
 * &#64;Service
 * public class ArrowDataFrameCache extends FileBackedCache&lt;ArrowDataFrame&gt; {
 *     public ArrowDataFrameCache() { super(20); }
 *     &#64;Override protected ArrowDataFrame loadFromDisk(String uri) throws Exception {
 *         return ArrowDataFrame.load(uri);
 *     }
 * }
 * </pre>
 *
 * @param <T> the cached resource type. Must implement {@link Cacheable}.
 */
public abstract class FileBackedCache<T extends Cacheable> {

    /** Tracks the on-disk mtime of each currently cached entry, updated atomically with cache puts. */
    private final ConcurrentHashMap<String, Long> mtimeIndex = new ConcurrentHashMap<>();

    /** The Caffeine cache holding the loaded resources. */
    private final Cache<String, T> cache;

    /** Identifier for log lines and the /api/cache/stats endpoint. Defaults to subclass simple name. */
    private final String name;

    /**
     * @param maxSize maximum number of entries; on overflow Caffeine evicts via Window TinyLFU.
     */
    protected FileBackedCache(long maxSize, Duration expireAfterAccess) {
        this.name = getClass().getSimpleName();
        this.cache = Caffeine.<String, T>newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireAfterAccess)
                .recordStats()
                .<String, T>removalListener((String key, T value, RemovalCause cause) -> {
                    if (value != null) {
                        // Flip ownership back to caller so close() actually releases buffers
                        value.setCached(false);
                        try {
                            value.close();
                        } catch (Exception e) {
                            System.err.println("[" + name + "] Failed to close evicted entry for "
                                    + key + ": " + e.getMessage());
                        }
                    }
                    mtimeIndex.remove(key);
                })
                .build();
    }

    /**
     * Template method: subclasses define how to read a fresh copy from disk.
     * The base class handles all caching concerns around this call.
     */
    protected abstract T loadFromDisk(String pathOrUri) throws Exception;

    /**
     * Read-through load. Returns a cached instance if mtime is unchanged; otherwise loads
     * from disk via {@link #loadFromDisk(String)} and caches the result.
     */
    public T load(String pathOrUri) {
        String absPath = toAbsolutePath(pathOrUri);
        long currentMtime = new File(absPath).lastModified();

        // Fast path: cache hit with matching mtime
        T existing = cache.getIfPresent(absPath);
        if (existing != null) {
            Long storedMtime = mtimeIndex.get(absPath);
            if (storedMtime != null && storedMtime == currentMtime) {
                return existing;
            }
            // Stale (file changed on disk) → invalidate, fall through to reload
            cache.invalidate(absPath);
        }

        // Miss or stale: let Caffeine dedup concurrent loads of the same key
        return cache.get(absPath, k -> {
            try {
                T fresh = loadFromDisk(pathOrUri);
                fresh.setCached(true);
                mtimeIndex.put(k, currentMtime);
                return fresh;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load: " + pathOrUri, e);
            }
        });
    }

    /** Manually invalidate one entry. */
    public void invalidate(String pathOrUri) {
        cache.invalidate(toAbsolutePath(pathOrUri));
    }

    /** Wipe the entire cache. Triggers removal listener for each entry. */
    public void clear() {
        cache.invalidateAll();
    }

    public CacheStats stats() {
        return cache.stats();
    }

    public long size() {
        return cache.estimatedSize();
    }

    public String getName() {
        return name;
    }

    /** Spring lifecycle hook — ensures Arrow buffers are released on shutdown. */
    @PreDestroy
    public void shutdown() {
        cache.invalidateAll();
    }

    /** Normalize URI strings (file:///...) and plain paths into a single canonical key. */
    private static String toAbsolutePath(String pathOrUri) {
        try {
            if (pathOrUri != null && pathOrUri.startsWith("file:")) {
                return new File(URI.create(pathOrUri)).getAbsolutePath();
            }
            return new File(pathOrUri).getAbsolutePath();
        } catch (Exception e) {
            return pathOrUri;
        }
    }
}
