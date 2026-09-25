package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PickerHasBucketsException extends RuntimeException {

    public PickerHasBucketsException(String message) {
        super(message);
    }
}
