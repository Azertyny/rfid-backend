package com.rfidback.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
import com.rfidback.entity.RegistrationReadEntity;
import com.rfidback.entity.RegistrationSessionEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.ReaderBusyException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.exception.RegistrationSessionNotFoundException;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.RegistrationRead;
import com.rfidback.generated.model.RegistrationSession;
import com.rfidback.generated.model.SaveRegistrationSession;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RegistrationReadRepository;
import com.rfidback.repository.RegistrationSessionRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;

/**
 * Tag-registration sessions (spec 003): the reads of one ENREGISTREMENT reader since an Administrateur clicked Start.
 * <p>
 * Transactions (research R3a): {@link #start} and {@link #recordRead} are deliberately not transactional, so a
 * unique-constraint violation only rolls back the insert that caused it. Catching it inside a wider transaction
 * would not help: the repository has already marked that transaction rollback-only, and the commit would fail.
 */
@Service
public class RegistrationService {

    static final String READ_KEPT = "Registration read";
    static final String READ_IGNORED = "Registration read ignored: no session started";
    private static final Duration GET_TOUCH_THRESHOLD = Duration.ofSeconds(30);

    private final RegistrationSessionRepository sessionRepository;
    private final RegistrationReadRepository readRepository;
    private final ReaderRepository readerRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final TagService tagService;
    private final ReferenceTagList referenceTagList;
    private final Clock clock;
    private final Duration sessionTimeout;

    public RegistrationService(RegistrationSessionRepository sessionRepository,
            RegistrationReadRepository readRepository, ReaderRepository readerRepository,
            UserRepository userRepository, TagRepository tagRepository, TagService tagService,
            ReferenceTagList referenceTagList, Clock clock,
            @Value("${app.registration.session-timeout}") Duration sessionTimeout) {
        this.sessionRepository = sessionRepository;
        this.readRepository = readRepository;
        this.readerRepository = readerRepository;
        this.userRepository = userRepository;
        this.tagRepository = tagRepository;
        this.tagService = tagService;
        this.referenceTagList = referenceTagList;
        this.clock = clock;
        this.sessionTimeout = sessionTimeout;
    }

    public RegistrationSession start(UUID readerId) {
        ReaderEntity reader = readerRepository.findById(readerId)
                .orElseThrow(() -> new ReaderNotFoundException("Reader %s not found".formatted(readerId)));
        if (!reader.isActive() || reader.getMode() != ReaderMode.ENREGISTREMENT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The reader must be active and in ENREGISTREMENT mode");
        }
        // Looked up after the reader, so a bad request never depends on the caller's account.
        UserEntity user = userRepository.findByUsername(currentUsername())
                .orElseThrow(() -> new IllegalStateException("Logged-in user not found in app_user"));
        return insertSession(reader, user, true);
    }

    private RegistrationSession insertSession(ReaderEntity reader, UserEntity user, boolean retryOnRace) {
        Optional<RegistrationSessionEntity> existing = sessionRepository.findByReader(reader);
        if (existing.isPresent()) {
            if (!isExpired(existing.get())) {
                throw readerBusy(existing.get());
            }
            deleteSession(existing.get());
        }

        OffsetDateTime now = now();
        RegistrationSessionEntity session = RegistrationSessionEntity.builder()
                .reader(reader).startedBy(user).startedAt(now).lastActivityAt(now).build();
        try {
            return toModel(sessionRepository.saveAndFlush(session), List.of());
        } catch (DataIntegrityViolationException exception) {
            // Another Administrateur started a session on this reader between the check above and the insert.
            Optional<RegistrationSessionEntity> winner = sessionRepository.findByReader(reader);
            if (winner.isPresent()) {
                throw readerBusy(winner.get());
            }
            if (retryOnRace) {
                return insertSession(reader, user, false);
            }
            // Two races in a row: the reader is contended but its owner is unknown, so answer busy without one.
            throw new ReaderBusyException(null, null);
        }
    }

    /** A scan from an ENREGISTREMENT reader: kept once per tag in its open session, never a Record nor a Tag. */
    public ScanTagResponse recordRead(ReaderEntity reader, String rawUid) {
        OffsetDateTime now = now();
        String uid = rawUid == null ? "" : rawUid.trim();
        if (!StringUtils.hasText(uid)) {
            return scanResponse(uid, now, READ_IGNORED);
        }

        Optional<RegistrationSessionEntity> found = sessionRepository.findByReader(reader);
        if (found.isEmpty()) {
            return scanResponse(uid, now, READ_IGNORED);
        }
        RegistrationSessionEntity session = found.get();
        if (isExpired(session)) {
            deleteSession(session);
            return scanResponse(uid, now, READ_IGNORED);
        }

        if (!readRepository.existsBySessionAndUid(session, uid)) {
            try {
                readRepository.saveAndFlush(RegistrationReadEntity.builder()
                        .session(session).uid(uid).firstReadAt(now).build());
            } catch (DataIntegrityViolationException exception) {
                // A concurrent read of the same tag was stored first; that insert ran in its own transaction.
            }
        }
        sessionRepository.touch(session.getId(), now);
        return scanResponse(uid, now, READ_KEPT);
    }

    @Transactional(noRollbackFor = RegistrationSessionNotFoundException.class)
    public RegistrationSession get(UUID sessionId) {
        RegistrationSessionEntity session = loadOwnedSession(sessionId);
        OffsetDateTime now = now();
        // The page polls every half second; one write every 30 s is enough to keep the session alive.
        if (session.getLastActivityAt().plus(GET_TOUCH_THRESHOLD).isBefore(now)) {
            session.setLastActivityAt(now);
        }
        return toModel(session, readRepository.findAllBySessionOrderByFirstReadAtAsc(session));
    }

    @Transactional(noRollbackFor = RegistrationSessionNotFoundException.class)
    public void cancel(UUID sessionId) {
        deleteSession(loadOwnedSession(sessionId));
    }

    /** Registers the session's reads on the bucket, then closes it. On 409 the session stays open. */
    @Transactional(noRollbackFor = RegistrationSessionNotFoundException.class)
    public RegisterTagsResponse save(UUID sessionId, SaveRegistrationSession request) {
        RegistrationSessionEntity session = loadOwnedSession(sessionId);
        List<String> uids = readRepository.findAllBySessionOrderByFirstReadAtAsc(session).stream()
                .map(RegistrationReadEntity::getUid)
                .toList();
        if (uids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tag has been read in this session");
        }
        // Checked before TagService so that nothing is written and the session stays open.
        if (uids.size() > TagService.MAX_TAGS_PER_REGISTRATION) {
            throw TagService.tooManyTags();
        }
        RegisterTagsResponse response = tagService.registerTagsForBucket(request.getBucketNumber(), uids,
                Boolean.TRUE.equals(request.getMoveConfirmed()), Boolean.TRUE.equals(request.getOffListConfirmed()));
        deleteSession(session);
        return response;
    }

    /** Called when the reader leaves ENREGISTREMENT mode or is disabled: its session could never get reads again. */
    @Transactional
    public void closeForReader(ReaderEntity reader) {
        sessionRepository.findByReader(reader).ifPresent(this::deleteSession);
    }

    private RegistrationSessionEntity loadOwnedSession(UUID sessionId) {
        RegistrationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> sessionNotFound(sessionId));
        if (isExpired(session)) {
            deleteSession(session);
            throw sessionNotFound(sessionId);
        }
        if (!session.getStartedBy().getUsername().equals(currentUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This registration session was started by another user");
        }
        return session;
    }

    // Reads first: there is no cascade from the session (see RegistrationSessionEntity). The flush makes the delete
    // reach the database before a new session for the same reader is inserted in the same transaction.
    private void deleteSession(RegistrationSessionEntity session) {
        readRepository.deleteBySession(session);
        sessionRepository.delete(session);
        sessionRepository.flush();
    }

    private boolean isExpired(RegistrationSessionEntity session) {
        return session.getLastActivityAt().plus(sessionTimeout).isBefore(now());
    }

    private RegistrationSession toModel(RegistrationSessionEntity session, List<RegistrationReadEntity> reads) {
        Map<String, Integer> bucketByUid = new HashMap<>();
        if (!reads.isEmpty()) {
            List<String> uids = reads.stream().map(RegistrationReadEntity::getUid).toList();
            for (TagEntity tag : tagRepository.findAllByUidIn(uids)) {
                if (tag.getBucket() != null) {
                    bucketByUid.put(tag.getUid(), tag.getBucket().getNumber());
                }
            }
        }

        RegistrationSession model = new RegistrationSession();
        model.setId(session.getId());
        model.setReaderId(session.getReader().getId());
        model.setReaderUid(session.getReader().getName());
        model.setStartedBy(session.getStartedBy().getUsername());
        model.setStartedAt(session.getStartedAt());
        model.setReads(reads.stream().map(read -> {
            RegistrationRead readModel = new RegistrationRead(read.getUid(), read.getFirstReadAt(),
                    referenceTagList.isOffList(read.getUid()));
            readModel.setBucketNumber(bucketByUid.get(read.getUid()));
            return readModel;
        }).toList());
        return model;
    }

    private static ReaderBusyException readerBusy(RegistrationSessionEntity session) {
        return new ReaderBusyException(session.getStartedBy().getUsername(), session.getStartedAt());
    }

    private static RegistrationSessionNotFoundException sessionNotFound(UUID sessionId) {
        return new RegistrationSessionNotFoundException("Registration session %s not found".formatted(sessionId));
    }

    private static ScanTagResponse scanResponse(String uid, OffsetDateTime now, String message) {
        ScanTagResponse response = new ScanTagResponse();
        response.setUid(uid);
        // Always true: no compliance check happens in registration mode, and the reader must not raise an alert.
        response.setIsCompliant(true);
        response.setProcessedAt(now);
        response.setMessage(message);
        return response;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }
}
