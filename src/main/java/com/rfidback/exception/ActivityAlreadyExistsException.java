package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ActivityAlreadyExistsException extends RuntimeException {

    public ActivityAlreadyExistsException(String message) {
        super(message);
    }
}
