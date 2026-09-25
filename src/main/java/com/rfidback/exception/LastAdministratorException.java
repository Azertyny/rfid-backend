package com.rfidback.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "At least one enabled administrator must remain")
public class LastAdministratorException extends RuntimeException {

    public LastAdministratorException() {
        super("At least one enabled administrator must remain");
    }
}
