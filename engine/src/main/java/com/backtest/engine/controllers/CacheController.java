package com.backtest.engine.controllers;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtest.engine.config.ArrowDataFrameCache;
import com.backtest.engine.config.ArrowStringDataFrameCache;
import com.backtest.engine.config.FileBackedCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Ops endpoints for all file-backed caches.
 *  - GET  /api/cache/stats  → per-cache hit/miss/eviction breakdown
 *  - POST /api/cache/clear  → clear all caches (e.g. after manual data refresh)
 */
@RestController
public class CacheController {

	private final ArrowDataFrameCache dfCache;
	private final ArrowStringDataFrameCache stringCache;

	public CacheController(ArrowDataFrameCache dfCache, ArrowStringDataFrameCache stringCache) {
		this.dfCache = dfCache;
		this.stringCache = stringCache;
	}

	@GetMapping("/api/cache/stats")
	public Map<String, Object> stats() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put(dfCache.getName(),     statsFor(dfCache));
		out.put(stringCache.getName(), statsFor(stringCache));
		return out;
	}

	@PostMapping("/api/cache/clear")
	public Map<String, Object> clear() {
		long dfBefore = dfCache.size();
		long stringBefore = stringCache.size();
		dfCache.clear();
		stringCache.clear();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("cleared", true);
		out.put(dfCache.getName() + ".entriesEvicted",     dfBefore);
		out.put(stringCache.getName() + ".entriesEvicted", stringBefore);
		return out;
	}

	private static Map<String, Object> statsFor(FileBackedCache<?> cache) {
		CacheStats s = cache.stats();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("size",             cache.size());
		out.put("hitCount",         s.hitCount());
		out.put("missCount",        s.missCount());
		out.put("hitRate",          String.format("%.2f%%", s.hitRate() * 100));
		out.put("loadSuccessCount", s.loadSuccessCount());
		out.put("loadFailureCount", s.loadFailureCount());
		out.put("totalLoadTimeMs",  s.totalLoadTime() / 1_000_000);
		out.put("evictionCount",    s.evictionCount());
		return out;
	}
}