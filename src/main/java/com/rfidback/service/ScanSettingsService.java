package com.rfidback.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.ScanSettingEntity;
import com.rfidback.entity.ScanSettingName;
import com.rfidback.entity.UserEntity;
import com.rfidback.generated.model.ScanSetting;
import com.rfidback.generated.model.ScanSettings;
import com.rfidback.generated.model.UpdateScanSettings;
import com.rfidback.repository.ScanSettingRepository;
import com.rfidback.repository.UserRepository;

/**
 * The two scan delays an Administrateur can change (spec 015, FR-008, FR-012 to FR-014). Read from the database at
 * every scan, with no cache, so a change applies to the next scan (research R6); a delay never changed is its
 * configuration default ({@code app.scan.reread-window}, {@code app.scan.crate-window}).
 */
@Service
public class ScanSettingsService {

    private static final long SECONDS_PER_MINUTE = 60;

    /** The delays in force for one scan. */
    public record Windows(Duration reread, Duration cheat) {
    }

    private final ScanSettingRepository scanSettingRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final Duration defaultRereadWindow;
    private final Duration defaultCheatWindow;

    public ScanSettingsService(ScanSettingRepository scanSettingRepository, UserRepository userRepository, Clock clock,
            @Value("${app.scan.reread-window}") Duration defaultRereadWindow,
            @Value("${app.scan.crate-window}") Duration defaultCheatWindow) {
        this.scanSettingRepository = scanSettingRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.defaultRereadWindow = defaultRereadWindow;
        this.defaultCheatWindow = defaultCheatWindow;
    }

    @Transactional(readOnly = true)
    public Windows windows() {
        Map<ScanSettingName, Duration> stored = new EnumMap<>(ScanSettingName.class);
        for (ScanSettingEntity setting : scanSettingRepository.findAll()) {
            stored.put(setting.getName(), Duration.ofSeconds(setting.getSeconds()));
        }
        return new Windows(stored.getOrDefault(ScanSettingName.REREAD_WINDOW, defaultRereadWindow),
                stored.getOrDefault(ScanSettingName.CHEAT_WINDOW, defaultCheatWindow));
    }

    /** The delays in force, in the units the Administrateur types, with the last change of each (FR-014). */
    @Transactional(readOnly = true)
    public ScanSettings getSettings() {
        Map<ScanSettingName, ScanSettingEntity> stored = storedWithAuthor();
        ScanSettings settings = new ScanSettings();
        settings.setRereadWindowMinutes(toModel(stored.get(ScanSettingName.REREAD_WINDOW),
                defaultRereadWindow.toSeconds(), SECONDS_PER_MINUTE));
        settings.setCheatWindowSeconds(toModel(stored.get(ScanSettingName.CHEAT_WINDOW),
                defaultCheatWindow.toSeconds(), 1));
        return settings;
    }

    /**
     * Stores each value that differs from the one in force, with the session's user and the current time; a value
     * equal to the one in force keeps its author and date (research R7). The bounds are checked by the API (FR-013).
     */
    @Transactional
    public ScanSettings updateSettings(UpdateScanSettings request) {
        Windows inForce = windows();
        UserEntity author = null;
        long rereadSeconds = request.getRereadWindowMinutes() * SECONDS_PER_MINUTE;
        if (rereadSeconds != inForce.reread().toSeconds()) {
            author = currentUser();
            store(ScanSettingName.REREAD_WINDOW, rereadSeconds, author);
        }
        long cheatSeconds = request.getCheatWindowSeconds();
        if (cheatSeconds != inForce.cheat().toSeconds()) {
            store(ScanSettingName.CHEAT_WINDOW, cheatSeconds, author == null ? currentUser() : author);
        }
        return getSettings();
    }

    private void store(ScanSettingName name, long seconds, UserEntity author) {
        scanSettingRepository.save(ScanSettingEntity.builder()
                .name(name)
                .seconds(Math.toIntExact(seconds))
                .updatedAt(OffsetDateTime.now(clock))
                .updatedBy(author)
                .build());
    }

    private Map<ScanSettingName, ScanSettingEntity> storedWithAuthor() {
        Map<ScanSettingName, ScanSettingEntity> stored = new EnumMap<>(ScanSettingName.class);
        scanSettingRepository.findAllWithAuthorBy().forEach(setting -> stored.put(setting.getName(), setting));
        return stored;
    }

    private static ScanSetting toModel(ScanSettingEntity stored, long defaultSeconds, long secondsPerUnit) {
        ScanSetting setting = new ScanSetting();
        if (stored == null) {
            setting.setValue(Math.toIntExact(defaultSeconds / secondsPerUnit));
            return setting;
        }
        setting.setValue(Math.toIntExact(stored.getSeconds() / secondsPerUnit));
        setting.setUpdatedAt(stored.getUpdatedAt());
        setting.setUpdatedBy(stored.getUpdatedBy().getUsername());
        return setting;
    }

    private UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Logged-in user %s not found".formatted(username)));
    }
}
