package com.rfidback.exception;

import java.time.OffsetDateTime;

/**
 * The reader already has an open registration session, started by someone else or earlier (409). The owner is null
 * in the rare case where the reader stayed contended but no session could be read back.
 */
public class ReaderBusyException extends RuntimeException {

    private final String startedBy;
    private final OffsetDateTime startedAt;

    public ReaderBusyException(String startedBy, OffsetDateTime startedAt) {
        super("This reader is already used by another registration session");
        this.startedBy = startedBy;
        this.startedAt = startedAt;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }
}
