package com.backtest.engine.config;


import java.time.Duration;

import org.springframework.stereotype.Service;

import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.IndicatorRuleLoader;

import jakarta.annotation.PostConstruct;

/**
 * Façade: caches {@link ArrowDataFrame} instances using the generic {@link FileBackedCache}
 * infrastructure. All caching policy is inherited; this class only:
 *  <ul>
 *    <li>declares the type parameter,</li>
 *    <li>provides the type-specific disk loader,</li>
 *    <li>registers itself with the {@link IndicatorRuleLoader} static service-locator so legacy
 *        static-call sites pick up caching transparently.</li>
 *  </ul>
 *
 * <p>Sized for ~32GB host: maxSize=20 ≈ ~6-7GB worst-case for typical Russell-3000 parquets.
 */
@Service
public class ArrowDataFrameCache extends FileBackedCache<ArrowDataFrame> {

    public ArrowDataFrameCache() {
    	super(25, Duration.ofMinutes(15));
    }

    @Override
    protected ArrowDataFrame loadFromDisk(String pathOrUri) throws Exception {
        return ArrowDataFrame.load(pathOrUri);
    }

    /**
     * Service Locator bootstrap: lets legacy static callers (e.g. {@code IndicatorRuleLoader})
     * route their parquet loads through the cache without requiring DI refactoring at every site.
     */
    @PostConstruct
    public void registerStaticLocator() {
        IndicatorRuleLoader.setCache(this);
        System.err.println("[ArrowDataFrameCache] Registered with IndicatorRuleLoader static locator.");
    }
}