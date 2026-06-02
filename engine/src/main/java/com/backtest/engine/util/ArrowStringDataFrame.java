package com.backtest.engine.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID;

import com.backtest.engine.config.Cacheable;

public class ArrowStringDataFrame implements Cacheable {

    private final BufferAllocator allocator;
    private final VectorSchemaRoot root;
    private final Map<String, VarCharVector> stringVectors = new LinkedHashMap<>();
    private final Map<LocalDate, Integer> dateIndexMap = new HashMap<>();

    // If true, this frame is cache-owned; close() is a no-op until the cache evicts.
    private volatile boolean cached = false;

    @Override
    public void setCached(boolean cached) {
        this.cached = cached;
    }

    @Override
    public boolean isCached() {
        return cached;
    }

    private ArrowStringDataFrame(BufferAllocator allocator, VectorSchemaRoot root) {
        this.allocator = allocator;
        this.root = root;
    }

    public static ArrowStringDataFrame load(String parquetUri) throws Exception {
        BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
        FileSystemDatasetFactory factory = new FileSystemDatasetFactory(
                allocator, NativeMemoryPool.getDefault(), FileFormat.PARQUET, parquetUri);
        Dataset dataset = factory.finish();
        Scanner scanner = dataset.newScan(new ScanOptions(32768));
        ArrowReader reader = scanner.scanBatches();

        if (!reader.loadNextBatch()) {
            throw new RuntimeException("No data found in " + parquetUri);
        }

        VectorSchemaRoot root = reader.getVectorSchemaRoot();
        ArrowStringDataFrame df = new ArrowStringDataFrame(allocator, root);

        // Build date index
        FieldVector dateVec = root.getFieldVectors().stream()
                .filter(v -> v.getField().getType().getTypeID() == ArrowTypeID.Timestamp)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No timestamp column found"));

        TimeStampNanoVector timeVec = (TimeStampNanoVector) dateVec;
        for (int row = 0; row < root.getRowCount(); row++) {
            long nanos = timeVec.get(row);
            LocalDate date = Instant.ofEpochSecond(
                    nanos / 1_000_000_000, nanos % 1_000_000_000
            ).atZone(ZoneOffset.UTC).toLocalDate();
            df.dateIndexMap.put(date, row);
        }

        // Capture string columns (no copying)
        for (FieldVector vec : root.getFieldVectors()) {
            if (vec instanceof VarCharVector && !"date".equalsIgnoreCase(vec.getName())) {
                df.stringVectors.put(vec.getName(), (VarCharVector) vec);
            }
        }

        return df;
    }

    public Set<LocalDate> getDates() {
        return dateIndexMap.keySet();
    }

    public List<String> getColumns() {
        return new ArrayList<>(stringVectors.keySet());
    }

    /** Get values lazily decoded from Arrow for one row */
    public Set<String> getRow(LocalDate date) {
        Integer idx = dateIndexMap.get(date);
        if (idx == null) {
            throw new IllegalArgumentException("Date not found: " + date);
        }

        List<String> values = null;
        for (VarCharVector vec : stringVectors.values()) {
            String raw = null;
            if (idx < vec.getValueCount() && !vec.isNull(idx)) {
            	raw = new String(vec.get(idx), StandardCharsets.UTF_8);
            }
            values = Arrays.asList(raw.split(","));
//            values.add(value);
        }
        return new HashSet<>(values);
    }

    @Override
    public void close() {
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