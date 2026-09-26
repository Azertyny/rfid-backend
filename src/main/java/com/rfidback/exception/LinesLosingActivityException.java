package com.rfidback.exception;

import java.util.List;

/** The change would leave these lines without their current activity, and it was not confirmed (409, spec 012). */
public class LinesLosingActivityException extends RuntimeException {

    private final List<String> readerUids;

    public LinesLosingActivityException(List<String> readerUids) {
        super("These lines would lose their current activity; confirm with confirmed=true");
        this.readerUids = List.copyOf(readerUids);
    }

    public List<String> getReaderUids() {
        return readerUids;
    }
}
