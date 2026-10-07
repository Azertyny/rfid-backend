package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ReaderStillActiveException extends RuntimeException {

    public ReaderStillActiveException(String message) {
        super(message);
    }
}
