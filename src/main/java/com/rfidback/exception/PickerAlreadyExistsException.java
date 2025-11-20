package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PickerAlreadyExistsException extends RuntimeException {

    public PickerAlreadyExistsException(String message) {
        super(message);
    }
}
