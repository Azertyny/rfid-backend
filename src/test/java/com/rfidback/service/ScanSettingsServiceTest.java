package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.rfidback.entity.Role;
import com.rfidback.entity.ScanSettingEntity;
import com.rfidback.entity.ScanSettingName;
import com.rfidback.entity.UserEntity;
import com.rfidback.generated.model.ScanSettings;
import com.rfidback.generated.model.UpdateScanSettings;
import com.rfidback.repository.ScanSettingRepository;
import com.rfidback.repository.UserRepository;

/** The two scan delays an Administrateur can change (spec 015, FR-008, FR-013, FR-014; research R6, R7). */
class ScanSettingsServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-15T08:00:00Z");

    private ScanSettingRepository repository;
    private UserRepository userRepository;
    private ScanSettingsService service;
    private UserEntity admin;
    private final List<ScanSettingEntity> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ScanSettingRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        when(repository.findAll()).thenReturn(rows);
        when(repository.findAllWithAuthorBy()).thenReturn(rows);
        when(repository.save(any(ScanSettingEntity.class))).thenAnswer(invocation -> {
            ScanSettingEntity saved = invocation.getArgument(0);
            rows.removeIf(row -> row.getName() == saved.getName());
            rows.add(saved);
            return saved;
        });
        service = new ScanSettingsService(repository, userRepository,
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC), Duration.ofMinutes(30), Duration.ofSeconds(2));
        admin = UserEntity.builder().id(UUID.randomUUID()).username("admin1").role(Role.ADMINISTRATEUR).enabled(true)
                .build();
        when(userRepository.findByUsername("admin1")).thenReturn(Optional.of(admin));
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("admin1", null, "ROLE_ADMINISTRATEUR"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noRow_givesTheConfigurationDefaults_withoutAuthorNorDate() {
        ScanSettings settings = service.getSettings();

        assertEquals(30, settings.getRereadWindowMinutes().getValue());
        assertThat(settings.getRereadWindowMinutes().getUpdatedBy()).isNull();
        assertThat(settings.getRereadWindowMinutes().getUpdatedAt()).isNull();
        assertEquals(2, settings.getCheatWindowSeconds().getValue());
        assertEquals(new ScanSettingsService.Windows(Duration.ofMinutes(30), Duration.ofSeconds(2)),
                service.windows());
    }

    @Test
    void aChangedValue_isStoredInSecondsWithItsAuthorAndDate() {
        ScanSettings settings = service.updateSettings(new UpdateScanSettings(10, 2));

        ArgumentCaptor<ScanSettingEntity> saved = ArgumentCaptor.forClass(ScanSettingEntity.class);
        verify(repository).save(saved.capture());
        assertEquals(ScanSettingName.REREAD_WINDOW, saved.getValue().getName());
        assertEquals(600, saved.getValue().getSeconds());
        assertThat(saved.getValue().getUpdatedBy()).isSameAs(admin);
        assertEquals(NOW, saved.getValue().getUpdatedAt());
        assertEquals(10, settings.getRereadWindowMinutes().getValue());
        assertEquals("admin1", settings.getRereadWindowMinutes().getUpdatedBy());
        assertThat(settings.getCheatWindowSeconds().getUpdatedBy()).isNull();
    }

    @Test
    void theValueInForce_writesNothing_andKeepsAuthorAndDate() {
        UserEntity earlier = UserEntity.builder().id(UUID.randomUUID()).username("admin0").build();
        OffsetDateTime earlierDate = OffsetDateTime.parse("2026-11-01T07:00:00Z");
        rows.add(ScanSettingEntity.builder().name(ScanSettingName.CHEAT_WINDOW).seconds(5).updatedBy(earlier)
                .updatedAt(earlierDate).build());

        ScanSettings settings = service.updateSettings(new UpdateScanSettings(30, 5));

        verify(repository, never()).save(any());
        assertEquals("admin0", settings.getCheatWindowSeconds().getUpdatedBy());
        assertEquals(earlierDate, settings.getCheatWindowSeconds().getUpdatedAt());
    }

    @Test
    void aSavedValue_appliesToTheNextScan() {
        service.updateSettings(new UpdateScanSettings(0, 7));

        assertEquals(new ScanSettingsService.Windows(Duration.ZERO, Duration.ofSeconds(7)), service.windows());
    }
}
