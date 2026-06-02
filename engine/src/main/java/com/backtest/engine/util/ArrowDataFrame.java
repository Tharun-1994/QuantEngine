package com.backtest.engine.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.dataset.file.FileFormat;
import org.apache.arrow.dataset.file.FileSystemDatasetFactory;
import org.apache.arrow.dataset.jni.NativeMemoryPool;
import org.apache.arrow.dataset.scanner.ScanOptions;
import org.apache.arrow.dataset.scanner.Scanner;
import org.apache.arrow.dataset.source.Dataset;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID;

import com.backtest.engine.config.Cacheable;

public class ArrowDataFrame implements Cacheable {

	private final BufferAllocator allocator;
	private final VectorSchemaRoot root;
	private final Map<String, Float4Vector> tickerVectors = new HashMap<>();
	private final Map<LocalDate, Integer> dateIndexMap = new HashMap<>();

	// ─────────────────────────────────────────────────────────────────────────
	// PRIMITIVE-ACCESS FAST PATH
	// Lazy-built parallel arrays for boxing-free, allocation-free iteration in
	// hot loops (leaf cache build, date loop). Built once on first call; reused
	// for the lifetime of the frame. Pairs perfectly with the cache: cached
	// frames also cache their primitive index.
	// ─────────────────────────────────────────────────────────────────────────
	private volatile String[] tickerArray;        // tickerArray[col] = ticker name
	private volatile Float4Vector[] vectorArray;  // vectorArray[col] = primitive float column

	private void buildPrimitiveIndex() {
		// Double-checked locking — the only writer is here, readers see a fully built array
		if (tickerArray != null) return;
		synchronized (this) {
			if (tickerArray != null) return;
			int n = tickerVectors.size();
			String[] tArr = new String[n];
			Float4Vector[] vArr = new Float4Vector[n];
			int i = 0;
			for (Map.Entry<String, Float4Vector> e : tickerVectors.entrySet()) {
				tArr[i] = e.getKey();
				vArr[i] = e.getValue();
				i++;
			}
			vectorArray = vArr;
			tickerArray = tArr; // assign last — visible-after barrier for the array contents
		}
	}

	/** Ticker names indexed by column. Built once, reused. Do NOT mutate. */
	public String[] getTickerArray() {
		buildPrimitiveIndex();
		return tickerArray;
	}

	/** Primitive Float4Vectors indexed by column, aligned with getTickerArray(). Do NOT mutate. */
	public Float4Vector[] getVectorArray() {
		buildPrimitiveIndex();
		return vectorArray;
	}

	/** Row index for a date, or null if date not in this frame. */
	public Integer getDateIndex(LocalDate date) {
		return dateIndexMap.get(date);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// CACHE OWNERSHIP FLAG
	// If true, this frame is owned by ArrowDataFrameCache; close() is a no-op.
	// Only the cache's RemovalListener flips this back to false and then calls close().
	// ─────────────────────────────────────────────────────────────────────────
	private volatile boolean cached = false;

	public void setCached(boolean cached) {
		this.cached = cached;
	}

	public boolean isCached() {
		return cached;
	}

	private ArrowDataFrame(BufferAllocator allocator, VectorSchemaRoot root) {
		this.allocator = allocator;
		this.root = root;
	}

	public static ArrowDataFrame load(String parquetPath) throws Exception {
		BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
		FileSystemDatasetFactory factory = new FileSystemDatasetFactory(allocator, NativeMemoryPool.getDefault(),
				FileFormat.PARQUET, parquetPath);
		Dataset dataset = factory.finish();
		Scanner scanner = dataset.newScan(new ScanOptions(32768));
		ArrowReader reader = scanner.scanBatches();

		if (!reader.loadNextBatch()) {
			throw new RuntimeException("No data found in " + parquetPath);
		}

		VectorSchemaRoot root = reader.getVectorSchemaRoot();
		ArrowDataFrame df = new ArrowDataFrame(allocator, root);

		// Build ticker vectors
		root.getFieldVectors().forEach(vec -> {
			String name = vec.getName();
			if (!"date".equalsIgnoreCase(name) && vec instanceof Float4Vector) {
				df.tickerVectors.put(name, (Float4Vector) vec);
			}
		});

		// Build date index
		FieldVector dateVec = root.getFieldVectors().stream()
				.filter(v -> v.getField().getType().getTypeID() == ArrowTypeID.Timestamp).findFirst()
				.orElseThrow(() -> new RuntimeException("No timestamp column found"));

		if (dateVec instanceof TimeStampNanoVector) {
			TimeStampNanoVector v = (TimeStampNanoVector) dateVec;
			for (int row = 0; row < root.getRowCount(); row++) {
				long nanosSinceEpoch = v.get(row);
				long seconds = nanosSinceEpoch / 1_000_000_000;
				long nanos = nanosSinceEpoch % 1_000_000_000;
				LocalDate date = Instant.ofEpochSecond(seconds, nanos).atZone(ZoneOffset.UTC).toLocalDate();
				df.dateIndexMap.put(date, row);
			}
		} else {
			throw new IllegalStateException("Unsupported date column type: " + dateVec.getClass());
		}

		return df;
	}

	public Map<String, Float> getRow(LocalDate date) {
		Integer row = dateIndexMap.get(date);
		if (row == null) {
			throw new IllegalArgumentException("Date not found: " + date);
		}

		Map<String, Float> rowData = new HashMap<>();
		for (Map.Entry<String, Float4Vector> entry : tickerVectors.entrySet()) {
			String ticker = entry.getKey();
			Float4Vector vec = entry.getValue();
			Float value = null;
			if (row < vec.getValueCount() && !vec.isNull(row)) {
				float v = vec.get(row);
				value = Float.isNaN(v) ? null : v;
			}
			rowData.put(ticker, value);
		}
		return rowData;
	}
	
	
    /** Get value for a specific (date, ticker) */
    public Float getValue(LocalDate date, String ticker) {
        Integer row = dateIndexMap.get(date);
        if (row == null) {
            throw new IllegalArgumentException("Date not found: " + date);
        }

        Float4Vector vec = tickerVectors.get(ticker);
        if (vec == null) {
            throw new IllegalArgumentException("Ticker not found: " + ticker);
        }

        if (row >= vec.getValueCount() || vec.isNull(row)) {
            return null;
        }

        float v = vec.get(row);
        return Float.isNaN(v) ? null : v;
    }

    /** Check if we have a value for (date, ticker) */
    public boolean hasValue(LocalDate date, String ticker) {
        Integer row = dateIndexMap.get(date);
        if (row == null) return false;

        Float4Vector vec = tickerVectors.get(ticker);
        if (vec == null) return false;

        return row < vec.getValueCount() && !vec.isNull(row);
    }

    /** Convenience: get all values for one ticker across all dates */
    public Map<LocalDate, Float> getTickerSeries(String ticker) {
        Float4Vector vec = tickerVectors.get(ticker);
        if (vec == null) {
            throw new IllegalArgumentException("Ticker not found: " + ticker);
        }

        Map<LocalDate, Float> series = new HashMap<>();
        for (Map.Entry<LocalDate, Integer> e : dateIndexMap.entrySet()) {
            int row = e.getValue();
            Float value = null;
            if (row < vec.getValueCount() && !vec.isNull(row)) {
                float v = vec.get(row);
                value = Float.isNaN(v) ? null : v;
            }
            series.put(e.getKey(), value);
        }
        return series;
    }


	public Set<LocalDate> getDates() {
		return dateIndexMap.keySet();
	}

	public Set<String> getTickers() {
		return tickerVectors.keySet();
	}
	// same Series, getRow, etc. methods as before...

	@Override
	public void close() {
		// If this frame is cache-owned, skip release — cache eviction will close us later.
		if (cached) {
			return;
		}
		try {
			root.close();
		} finally {
			allocator.close();
		}
	}
}