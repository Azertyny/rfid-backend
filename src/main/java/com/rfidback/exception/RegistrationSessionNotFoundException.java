package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Unknown, closed or expired registration session. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RegistrationSessionNotFoundException extends RuntimeException {

    public RegistrationSessionNotFoundException(String message) {
        super(message);
    }
}
