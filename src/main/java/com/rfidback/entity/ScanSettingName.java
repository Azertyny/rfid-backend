package com.rfidback.entity;

/** The scan delays an Administrateur can change, one row each in {@code scan_setting} (spec 015, FR-008). */
public enum ScanSettingName {
    /** A counted tag read again less than this after its last read is ignored (FR-003). */
    REREAD_WINDOW,
    /** Two tags of one bucket read by one reader less than this apart are a Cheat (FR-006). */
    CHEAT_WINDOW
}
