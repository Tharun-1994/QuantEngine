package com.backtest.engine.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class BacktestExecutionException extends RuntimeException {
	
	private static final long serialVersionUID = 1L;
	
    public BacktestExecutionException(String message) {
        super(message);
    	System.err.println(message);
    	
    }

    public BacktestExecutionException(String message, Throwable cause) {
    	
        super(message, cause);
        System.err.println(cause);
    }
}
