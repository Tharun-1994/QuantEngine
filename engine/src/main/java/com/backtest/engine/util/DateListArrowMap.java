package com.backtest.engine.util;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

public class DateListArrowMap implements AutoCloseable {

    private final BufferAllocator allocator;
    private final DateDayVector dateVector;
    private final ListVector listVector;
    private final VarCharVector dataVector;
    private int rowCount = 0;
    private final Map<LocalDate, Integer> dateIndexMap = new HashMap<>();

    public DateListArrowMap() {
        this.allocator = new RootAllocator(Long.MAX_VALUE);

        this.dateVector = new DateDayVector("date", allocator);
        this.listVector = ListVector.empty("tickers", allocator);

        // allocate vectors
        dateVector.allocateNew();
        listVector.addOrGetVector(FieldType.nullable(new ArrowType.Utf8()));

        listVector.allocateNew();

        this.dataVector = (VarCharVector) listVector.getDataVector();
    }

    /** Put a new row */
    public void put(LocalDate date, List<String> tickers) {
        int row = rowCount;

        // store date
        dateVector.setSafe(row, (int) date.toEpochDay());

        // store list of tickers
        listVector.startNewValue(row);
        for (String t : tickers) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            dataVector.setSafe(dataVector.getValueCount(), bytes);
        }
        listVector.endValue(row, tickers.size());

        dateIndexMap.put(date, row);
        rowCount++;
        dateVector.setValueCount(rowCount);
        listVector.setValueCount(rowCount);
    }

    /** Get the tickers for a date */
    public List<String> get(LocalDate date) {
        Integer row = dateIndexMap.get(date);
        if (row == null) {
            return Collections.emptyList();
        }

        int start = listVector.getElementStartIndex(row);
        int end = listVector.getElementEndIndex(row);

        List<String> tickers = new ArrayList<>();
        for (int i = start; i < end; i++) {
            byte[] bytes = dataVector.get(i);
            if (bytes != null) {
                tickers.add(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return tickers;
    }


    /** Get all stored dates */
    public Set<LocalDate> getDates() {
        return dateIndexMap.keySet();
    }

    public int size() {
        return rowCount;
    }

    @Override
    public void close() {
        dateVector.close();
        listVector.close();
        allocator.close();
    }
}
