package com.backtest.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.util.ArrowDataFrame;

public class Main {

	public static void main(String[] args) throws Exception {
		System.out.println(0.1 + 0.2);
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