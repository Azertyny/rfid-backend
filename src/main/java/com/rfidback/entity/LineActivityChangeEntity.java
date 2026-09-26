package com.rfidback.entity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// One row per change of a line's current activity, never updated or deleted (spec 012, FR-012). changedAt is set by
// the code: the midnight reset is dated at the midnight it was due, even when it runs later (research R3).
@Table(name = "line_activity_change", indexes = @Index(name = "idx_line_activity_change_reader_date",
        columnList = "reader_id, changed_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LineActivityChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reader_id", nullable = false)
    private ReaderEntity reader;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_activity_id")
    private ActivityEntity previousActivity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_activity_id")
    private ActivityEntity newActivity;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 10)
    private ActivityChangeAuthorType authorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id")
    private UserEntity authorUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_reader_id")
    private ReaderEntity authorReader;

    @PrePersist
    void checkConsistency() {
        boolean authorMatches = switch (authorType) {
            case USER -> authorUser != null && authorReader == null;
            case READER -> authorReader != null && authorUser == null;
            case SYSTEM -> authorUser == null && authorReader == null;
        };
        if (!authorMatches) {
            throw new IllegalStateException("The author of an activity change must match its type " + authorType);
        }
        if (Objects.equals(idOf(previousActivity), idOf(newActivity))) {
            throw new IllegalStateException("An activity change must change the activity");
        }
    }

    private static UUID idOf(ActivityEntity activity) {
        return activity == null ? null : activity.getId();
    }
}
