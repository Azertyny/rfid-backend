package com.rfidback.entity;

/**
 * Who changed a line's current activity (spec 012, FR-012): a logged-in user, the line kiosk through its reader's
 * token, or the system for the midnight reset.
 */
public enum ActivityChangeAuthorType {
    USER,
    READER,
    SYSTEM
}
