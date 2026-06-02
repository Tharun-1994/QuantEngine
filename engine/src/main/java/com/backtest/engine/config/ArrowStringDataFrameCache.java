package com.backtest.engine.config;


import java.time.Duration;

import org.springframework.stereotype.Service;

import com.backtest.engine.util.ArrowStringDataFrame;

/**
 * Façade: caches {@link ArrowStringDataFrame} instances (used for the universe parquet)
 * via the generic {@link FileBackedCache} infrastructure.
 *
 * <p>Universe files are smaller than OHLC frames (~44MB on disk) so a tighter cap is fine.
 * Caffeine's TinyLFU keeps the hottest entries regardless of size.
 */
@Service
public class ArrowStringDataFrameCache extends FileBackedCache<ArrowStringDataFrame> {

    public ArrowStringDataFrameCache() {
    	super(10, Duration.ofMinutes(15));
    }

    @Override
    protected ArrowStringDataFrame loadFromDisk(String pathOrUri) throws Exception {
        return ArrowStringDataFrame.load(pathOrUri);
    }
}
