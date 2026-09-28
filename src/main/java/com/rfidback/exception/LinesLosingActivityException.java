package com.rfidback.exception;

import java.util.List;

import com.rfidback.generated.model.LineLosingActivity;

/**
 * The change would take their current activity from these lines, and it was not confirmed (409, spec 012). Each line
 * comes with the activity it would get instead, its only remaining one, or none (research R18).
 */
public class LinesLosingActivityException extends RuntimeException {

    private final List<LineLosingActivity> lines;

    public LinesLosingActivityException(List<LineLosingActivity> lines) {
        super("These lines would lose their current activity; confirm with confirmed=true");
        this.lines = List.copyOf(lines);
    }

    public List<LineLosingActivity> getLines() {
        return lines;
    }

    public List<String> getReaderUids() {
        return lines.stream().map(LineLosingActivity::getReaderUid).toList();
    }
}
