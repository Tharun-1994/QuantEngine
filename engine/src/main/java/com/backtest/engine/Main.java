package com.backtest.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.util.ArrowDataFrame;

public class Main {

	public static void main(String[] args) throws Exception {
		String parquetFile = "file:///C:/Tharun/Projects/backtest_data/inputs/relative_momentum_63.parquet";

		try (ArrowDataFrame df = ArrowDataFrame.load(parquetFile)) {
			List<LocalDate> sortedDates = df.getDates().stream().sorted().toList();
			List<String> tickers = df.getTickers().stream().toList();

			System.out.printf("Testing %d dates × %d tickers = ~%,d lookups%n", sortedDates.size(), tickers.size(),
					(long) sortedDates.size() * tickers.size());

			// Benchmark start
			long start = System.nanoTime();

			long count = 0;
			double sum = 0.0;

			for (LocalDate date : sortedDates) {
				Map<String, Float> row = df.getRow(date); // fetch all tickers for a date
				for (String ticker : tickers) {
					Float v = row.get(ticker);
					if (v != null) {
						sum += v; // dummy use to avoid dead-code elimination
						count++;
					}
				}
			}

			long end = System.nanoTime();
			double elapsedMs = (end - start) / 1_000_000.0;

			System.out.printf("Processed %,d values in %.2f ms (%.2f M lookups/sec)%n", count, elapsedMs,
					(count / (elapsedMs / 1000.0)) / 1_000_000.0);

			System.out.println("Checksum (ignore): " + sum);
		}
	}
//	public static void main(String[] args) throws Exception {
//        String inputDir = "C:/Tharun/Projects/backtest_data/inputs"; // your folder
//
//        File dir = new File(inputDir);
//        if (!dir.isDirectory()) {
//            throw new IllegalArgumentException("Not a directory: " + inputDir);
//        }
//
//        // Collect all parquet files in directory
//        List<File> parquetFiles = Files.list(Path.of(inputDir))
//                .filter(p -> p.toString().endsWith(".parquet"))
//                .map(Path::toFile)
//                .toList();
//
//        System.out.println("Found " + parquetFiles.size() + " parquet files.");
//
//        // Hold references so they stay in memory
//        List<ArrowDataFrame> frames = new ArrayList<>();
//
//        for (File file : parquetFiles) {
//            String parquetPath = "file:///" + file.getAbsolutePath().replace("\\", "/");
//
//            ArrowDataFrame df = ArrowDataFrame.load(parquetPath);
//            frames.add(df);
//
//            long usedHeap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
//            System.out.printf("Loaded %s, heap used: %d MB%n", file.getName(), usedHeap);
//        }
//
//        System.out.println("All frames loaded. Press Enter to release memory...");
////        System.in.read();
//
//        // Close all frames to free Arrow native memory
//        for (ArrowDataFrame df : frames) {
//            df.close();
//        }
//
//        System.out.println("All frames closed.");
//    }
}