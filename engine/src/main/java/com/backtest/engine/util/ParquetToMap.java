package com.backtest.engine.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParquetToMap {
	private static final DateTimeFormatter PARQUET_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");

	public static Map<LocalDate, Map<String, Float>> loadParquetToMap(String path) throws SQLException {
		
		
		Map<LocalDate, Map<String, Float>> out = new LinkedHashMap<>();

		// 1) DuckDB in-memory
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
			// inline the path – DuckDB needs a literal, not a bind param, to read_parquet
			String safePath = path.replace("'", "''");
			
			System.err.println(safePath);
			String sql = "SELECT * FROM read_parquet('" + safePath + "')";

			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

				ResultSetMetaData md = rs.getMetaData();
				int cols = md.getColumnCount();

				// 2) find the 'date' column index (case-insensitive)
				int dateIdx = -1;
				for (int i = 1; i <= cols; i++) {
					if ("date".equalsIgnoreCase(md.getColumnName(i))) {
						dateIdx = i;
						break;
					}
				}
				if (dateIdx < 0) {
					throw new SQLException("No 'date' column found in Parquet schema");
				}

				// 3) iterate rows
				while (rs.next()) {
					String dateTime = rs.getString(dateIdx);
					// 2) parse it into a LocalDate
					LocalDateTime ldt = LocalDateTime.parse(dateTime, PARQUET_TS_FMT);
					// then drop the time
					LocalDate key = ldt.toLocalDate();
					Map<String, Float> row = new LinkedHashMap<>();

					// 4) only read numeric columns
					for (int i = 1; i <= cols; i++) {
						if (i == dateIdx)
							continue;

						String colName = md.getColumnName(i);
						int jdbcType = md.getColumnType(i);

						switch (jdbcType) {
						// handle all numeric SQL types you expect
						case Types.DOUBLE:
						case Types.FLOAT:
						case Types.REAL:
						case Types.INTEGER:
						case Types.BIGINT:
						case Types.SMALLINT:
						case Types.TINYINT:
							float f = rs.getFloat(i);
//							double d = rs.getDouble(i);
							
							// wasNull → store null, otherwise store the value
							row.put(colName, rs.wasNull() || Float.isNaN(f) ? null : f);
							break;

						default:
							// skip any non-numeric column (e.g., VARCHAR)
							break;
						}
					}

					out.put(key, row);
				}
			}
		}

		return out;
	}

	public static List<LocalDate> loadParquetToDateList(String path) throws SQLException {
		List<LocalDate> out = new LinkedList<>();

		// 1) DuckDB in-memory
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
			// inline the path – DuckDB needs a literal, not a bind param, to read_parquet
			String safePath = path.replace("'", "''");
			String sql = "SELECT * FROM read_parquet('" + safePath + "')";

			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

				ResultSetMetaData md = rs.getMetaData();
				int cols = md.getColumnCount();

				// 2) find the 'date' column index (case-insensitive)
				int dateIdx = -1;
				for (int i = 1; i <= cols; i++) {
					if ("date".equalsIgnoreCase(md.getColumnName(i))) {
						dateIdx = i;
						break;
					}
				}
				if (dateIdx < 0) {
					throw new SQLException("No 'date' column found in Parquet schema");
				}

				// 3) iterate rows
				while (rs.next()) {
					String dateTime = rs.getString(dateIdx);
					// 2) parse it into a LocalDate
					LocalDateTime ldt = LocalDateTime.parse(dateTime, PARQUET_TS_FMT);
					// then drop the time
					LocalDate key = ldt.toLocalDate();

					out.add(key);
				}
			}
		}

		return out;
	}

	public static Map<LocalDate, Set<String>> loadParquetToMapListTickers(String path) throws SQLException {
		Map<LocalDate, Set<String>> out = new LinkedHashMap<>();

		// 1) DuckDB in-memory
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
			// inline the path – DuckDB needs a literal, not a bind param, to read_parquet
			String safePath = path.replace("'", "''");
			String sql = "SELECT * FROM read_parquet('" + safePath + "')";

			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

				ResultSetMetaData md = rs.getMetaData();
				int cols = md.getColumnCount();

				// 2) find the 'date' column index (case-insensitive)
				int dateIdx = -1;
				for (int i = 1; i <= cols; i++) {
					if ("date".equalsIgnoreCase(md.getColumnName(i))) {
						dateIdx = i;
						break;
					}
				}
				if (dateIdx < 0) {
					throw new SQLException("No 'date' column found in Parquet schema");
				}

				// 3) iterate rows
				while (rs.next()) {
					String dateTime = rs.getString(dateIdx);
					// 2) parse it into a LocalDate
					LocalDateTime ldt = LocalDateTime.parse(dateTime, PARQUET_TS_FMT);
					// then drop the time
					LocalDate key = ldt.toLocalDate();


					// 4) only read numeric columns
					for (int i = 1; i <= cols; i++) {
						if (i == dateIdx)
							continue;

						String colName = md.getColumnName(i);
						int jdbcType = md.getColumnType(i);

						switch (jdbcType) {
						// handle all numeric SQL types you expect
						case Types.DOUBLE:
						case Types.FLOAT:
						case Types.REAL:
						case Types.INTEGER:
						case Types.BIGINT:
						case Types.SMALLINT:
						case Types.TINYINT:
						case Types.VARCHAR:
							
							String tickerStr = rs.getString(i);
							List<String> tickers = Arrays.asList(tickerStr.split(","));

							out.put(key, new HashSet<String>(tickers));
							break;

						default:
							// skip any non-numeric column (e.g., VARCHAR)
							break;
						}
					}

					
				}
			}
		}

		return out;
	}
	
	
	public static DataCache loadParquetToCache(String path) throws SQLException {
	    try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
	        String safePath = path.replace("'", "''");
	        String sql = "SELECT * FROM read_parquet('" + safePath + "')";
	        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

	            ResultSetMetaData md = rs.getMetaData();
	            int cols = md.getColumnCount();

	            // 1) find the 'date' column index
	            int dateIdx = -1;
	            List<String> tickers = new ArrayList<>();
	            for (int i = 1; i <= cols; i++) {
	                String colName = md.getColumnName(i);
	                if ("date".equalsIgnoreCase(colName)) {
	                    dateIdx = i;
	                } else {
	                    tickers.add(colName);
	                }
	            }
	            if (dateIdx < 0) throw new SQLException("No 'date' column found in Parquet schema");

	            // 2) collect rows
	            List<LocalDate> dates = new ArrayList<>();
	            List<float[]> rowValues = new ArrayList<>();

	            while (rs.next()) {
	                // ✅ no manual DateTimeFormatter parsing
					String dateTime = rs.getString(dateIdx);
					// 2) parse it into a LocalDate
					LocalDateTime ldt = LocalDateTime.parse(dateTime, PARQUET_TS_FMT);
					// then drop the time
					LocalDate key = ldt.toLocalDate();
	                dates.add(key);

	                float[] row = new float[tickers.size()];
	                for (int c = 0; c < tickers.size(); c++) {
	                    float v = rs.getFloat(c + 2); // +2 if col1=date
	                    row[c] = rs.wasNull() ? Float.NaN : v;
	                }
	                rowValues.add(row);
	            }

	            // 3) convert to arrays
	            LocalDate[] dateArr = dates.toArray(new LocalDate[0]);
	            String[] tickerArr = tickers.toArray(new String[0]);
	            float[][] values = rowValues.toArray(new float[0][]);

	            // 4) build lookup maps
	            Map<LocalDate, Integer> dateIndex = new HashMap<>();
	            for (int i = 0; i < dateArr.length; i++) dateIndex.put(dateArr[i], i);

	            Map<String, Integer> tickerIndex = new HashMap<>();
	            for (int i = 0; i < tickerArr.length; i++) tickerIndex.put(tickerArr[i], i);

	            return new DataCache(values, dateArr, tickerArr, dateIndex, tickerIndex);
	        }
	    }
	}
	
	
	
public static Map<String, Float> loadParquetToMapDirect(String path,LocalDate date) throws SQLException {
		
		
	Map<String, Float> row = new LinkedHashMap<>();

		// 1) DuckDB in-memory
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
			// inline the path – DuckDB needs a literal, not a bind param, to read_parquet
			String safePath = path.replace("'", "''");
			
			System.err.println(safePath);
			String sql = "SELECT * FROM read_parquet('" + safePath + "') WHERE date = '" + date.toString() + "'";

			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

				ResultSetMetaData md = rs.getMetaData();
				int cols = md.getColumnCount();

				// 2) find the 'date' column index (case-insensitive)
				int dateIdx = -1;
				for (int i = 1; i <= cols; i++) {
					if ("date".equalsIgnoreCase(md.getColumnName(i))) {
						dateIdx = i;
						break;
					}
				}
				if (dateIdx < 0) {
					throw new SQLException("No 'date' column found in Parquet schema");
				}

				// 3) iterate rows
				while (rs.next()) {
					String dateTime = rs.getString(dateIdx);
					// 2) parse it into a LocalDate
					LocalDateTime ldt = LocalDateTime.parse(dateTime, PARQUET_TS_FMT);
					// then drop the time
					LocalDate key = ldt.toLocalDate();
					

					// 4) only read numeric columns
					for (int i = 1; i <= cols; i++) {
						if (i == dateIdx)
							continue;

						String colName = md.getColumnName(i);
						int jdbcType = md.getColumnType(i);

						switch (jdbcType) {
						// handle all numeric SQL types you expect
						case Types.DOUBLE:
						case Types.FLOAT:
						case Types.REAL:
						case Types.INTEGER:
						case Types.BIGINT:
						case Types.SMALLINT:
						case Types.TINYINT:
							float f = rs.getFloat(i);
//							double d = rs.getDouble(i);
							
							// wasNull → store null, otherwise store the value
							row.put(colName, rs.wasNull() || Float.isNaN(f) ? null : f);
							break;

						default:
							// skip any non-numeric column (e.g., VARCHAR)
							break;
						}
					}

					
				}
			}
		}

		return row;
	}


public static Set<String> loadParquetToMapListTickersDirect(String path,LocalDate date) throws SQLException {
	Set<String> out = new LinkedHashSet<>();

	// 1) DuckDB in-memory
	try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
		// inline the path – DuckDB needs a literal, not a bind param, to read_parquet
		String safePath = path.replace("'", "''");
		String sql = "SELECT * FROM read_parquet('" + safePath + "') WHERE date = '" + date.toString() + "'";

		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

			ResultSetMetaData md = rs.getMetaData();
			int cols = md.getColumnCount();

			// 2) find the 'date' column index (case-insensitive)
			int dateIdx = -1;
			for (int i = 1; i <= cols; i++) {
				if ("date".equalsIgnoreCase(md.getColumnName(i))) {
					dateIdx = i;
					break;
				}
			}
			if (dateIdx < 0) {
				throw new SQLException("No 'date' column found in Parquet schema");
			}

			// 3) iterate rows
			while (rs.next()) {
				String dateTime = rs.getString(dateIdx);
				// 2) parse it into a LocalDate
				LocalDateTime ldt = LocalDateTime.parse(dateTime, PARQUET_TS_FMT);
				// then drop the time
				LocalDate key = ldt.toLocalDate();


				// 4) only read numeric columns
				for (int i = 1; i <= cols; i++) {
					if (i == dateIdx)
						continue;

					String colName = md.getColumnName(i);
					int jdbcType = md.getColumnType(i);

					switch (jdbcType) {
					// handle all numeric SQL types you expect
					case Types.DOUBLE:
					case Types.FLOAT:
					case Types.REAL:
					case Types.INTEGER:
					case Types.BIGINT:
					case Types.SMALLINT:
					case Types.TINYINT:
					case Types.VARCHAR:
						
						String tickerStr = rs.getString(i);
						List<String> tickers = Arrays.asList(tickerStr.split(","));

						out.addAll(tickers);
						break;

					default:
						// skip any non-numeric column (e.g., VARCHAR)
						break;
					}
				}

				
			}
		}
	}

	return out;
}

	
}
