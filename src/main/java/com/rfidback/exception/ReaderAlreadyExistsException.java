package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ReaderAlreadyExistsException extends RuntimeException {

    public ReaderAlreadyExistsException(String message) {
        super(message);
    }
}
