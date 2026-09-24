package com.rfidback.service;

import java.util.ArrayList;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.Role;
import com.rfidback.exception.ReaderAlreadyExistsException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.CreateReader;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.ReadersList;
import com.rfidback.generated.model.UpdateReader;
import com.rfidback.repository.ReaderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReaderService {

    private static final String DUPLICATE_UID_MESSAGE = "A reader with the same uid already exists";

    private final ReaderRepository readerRepository;
    private final RegistrationService registrationService;

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
        for (ReaderEntity readerEntity : readerRepository.findAll()) {
            readerArrayList.add(toModel(readerEntity, includeApiTokens));
        }
        ReadersList readersList = new ReadersList();
        readersList.setReaders(readerArrayList);
        return readersList;
    }

    /**
     * A disabled reader keeps its token and its records, but the token is refused on /tags/scan. A reader that ends
     * up disabled or in PRODUCTION mode loses its open registration session, which could never get reads again.
     */
    @Transactional
    public Reader updateReader(UUID readerId, UpdateReader request) {
        if (request.getActive() == null && request.getMode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide an active state and/or a mode");
        }
        ReaderEntity readerEntity = loadReader(readerId);
        if (request.getActive() != null) {
            readerEntity.setActive(request.getActive());
        }
        if (request.getMode() != null) {
            readerEntity.setMode(ReaderMode.valueOf(request.getMode().name()));
        }
        if (!readerEntity.isActive() || readerEntity.getMode() != ReaderMode.ENREGISTREMENT) {
            registrationService.closeForReader(readerEntity);
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

    private ReaderEntity loadReader(UUID readerId) {
        return readerRepository.findById(readerId)
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
