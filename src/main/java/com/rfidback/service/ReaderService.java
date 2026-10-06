package com.rfidback.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.Role;
import com.rfidback.exception.ReaderAlreadyExistsException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.exception.ReaderStillActiveException;
import com.rfidback.generated.model.CreateReader;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.ReadersList;
import com.rfidback.generated.model.UpdateReader;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.ReaderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReaderService {

    private static final String DUPLICATE_UID_MESSAGE = "A reader with the same uid already exists";

    private final ReaderRepository readerRepository;
    private final RegistrationService registrationService;
    private final LineActivityService lineActivityService;
    private final ActivityRepository activityRepository;
    private final Clock clock;

    public Reader createReader(CreateReader createReader) throws Exception {
        String uid = createReader.getUid();
        if (!StringUtils.hasText(uid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uid must not be blank");
        }
        uid = uid.trim();
        if (readerRepository.existsByNameIgnoreCase(uid)) {
            throw new ReaderAlreadyExistsException(DUPLICATE_UID_MESSAGE);
        }

        ReaderEntity readerEntitySaved;
        try {
            readerEntitySaved = readerRepository.saveAndFlush(ReaderEntity.builder().name(uid).build());
        } catch (DataIntegrityViolationException exception) {
            // Another request created the same uid between the check above and this insert.
            throw new ReaderAlreadyExistsException(DUPLICATE_UID_MESSAGE);
        }
        // Only Administrateurs can create a reader, and they need its token to configure the device.
        return toModel(readerEntitySaved, true);
    }

    public ReadersList getReaders() {
        boolean includeApiTokens = currentUserIsAdministrator();
        ArrayList<Reader> readerArrayList = new ArrayList<>();
        for (ReaderEntity readerEntity : readerRepository.findAllByDeletedAtIsNull()) {
            readerArrayList.add(toModel(readerEntity, includeApiTokens));
        }
        ReadersList readersList = new ReadersList();
        readersList.setReaders(readerArrayList);
        return readersList;
    }

    /**
     * A disabled reader keeps its token and its records, but the token is refused on /tags/scan. A reader that ends
     * up disabled or in PRODUCTION mode loses its open registration session, which could never get reads again. A
     * reader switched to ENREGISTREMENT loses its current activity but keeps its associations (spec 012, FR-013); one
     * switched to PRODUCTION without current activity gets its only associated active activity, if any (FR-008b).
     */
    @Transactional
    public Reader updateReader(UUID readerId, UpdateReader request) {
        if (request.getActive() == null && request.getMode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide an active state and/or a mode");
        }
        // Locked like every change of the line's current activity (spec 012, research R9).
        ReaderEntity readerEntity = notDeleted(readerRepository.findWithLockById(readerId), readerId);
        if (request.getActive() != null) {
            readerEntity.setActive(request.getActive());
        }
        ReaderMode previousMode = readerEntity.getMode();
        if (request.getMode() != null) {
            readerEntity.setMode(ReaderMode.valueOf(request.getMode().name()));
        }
        if (!readerEntity.isActive() || readerEntity.getMode() != ReaderMode.ENREGISTREMENT) {
            registrationService.closeForReader(readerEntity);
        }
        if (readerEntity.getMode() == ReaderMode.ENREGISTREMENT && readerEntity.getCurrentActivity() != null) {
            lineActivityService.clear(readerEntity, lineActivityService.currentUser());
        }
        if (readerEntity.getMode() == ReaderMode.PRODUCTION && previousMode != ReaderMode.PRODUCTION) {
            lineActivityService.applyDefaultIfNone(readerEntity, lineActivityService::currentUser);
        }
        return toModel(readerRepository.save(readerEntity), true);
    }

    /** The old token is refused from the next scan on: the scan filter reads the token from the database. */
    @Transactional
    public Reader rotateToken(UUID readerId) {
        ReaderEntity readerEntity = loadReader(readerId);
        readerEntity.setApitoken(ReaderEntity.newApitoken());
        return toModel(readerRepository.save(readerEntity), true);
    }

    /**
     * Soft delete (spec 002, FR-008): the row stays for the records, conformity and activity changes that point to it,
     * and keeps its uid taken. Only a disabled reader can be deleted, so its token is already refused, and it can
     * never be re-enabled. The line leaves its activities, ends its activity history with "no activity" and loses an
     * open registration session (research R11).
     */
    @Transactional
    public void deleteReader(UUID readerId) {
        ReaderEntity readerEntity = notDeleted(readerRepository.findWithLockById(readerId), readerId);
        if (readerEntity.isActive()) {
            throw new ReaderStillActiveException("Deactivate the reader before deleting it");
        }
        for (ActivityEntity activity : activityRepository.findAllByLine(readerId)) {
            activity.getLines().removeIf(line -> line.getId().equals(readerId));
        }
        activityRepository.flush();
        // Brought to today first: a state from a previous day must not be read as the line's current activity.
        lineActivityService.startNewDay(readerEntity);
        if (readerEntity.getCurrentActivity() != null) {
            lineActivityService.clear(readerEntity, lineActivityService.currentUser());
        }
        // Normally already closed when the reader was disabled.
        registrationService.closeForReader(readerEntity);
        readerEntity.setDeletedAt(OffsetDateTime.now(clock));
        readerRepository.save(readerEntity);
    }

    private ReaderEntity loadReader(UUID readerId) {
        return notDeleted(readerRepository.findById(readerId), readerId);
    }

    /** A deleted reader is answered as unknown by every management route (research R12). */
    private static ReaderEntity notDeleted(Optional<ReaderEntity> reader, UUID readerId) {
        return reader.filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ReaderNotFoundException("Reader %s not found".formatted(readerId)));
    }

    private Reader toModel(ReaderEntity readerEntity, boolean includeApiToken) {
        Reader reader = new Reader();
        reader.setId(readerEntity.getId());
        reader.setUid(readerEntity.getName());
        reader.setActive(readerEntity.isActive());
        reader.setMode(com.rfidback.generated.model.ReaderMode.fromValue(readerEntity.getMode().name()));
        if (includeApiToken) {
            reader.setApitoken(readerEntity.getApitoken());
        }
        reader.setCreationDate(readerEntity.getCreationDate());
        reader.setUpdateDate(readerEntity.getUpdateDate());
        return reader;
    }

    private static boolean currentUserIsAdministrator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> Role.ADMINISTRATEUR.authority().equals(authority.getAuthority()));
    }

}
