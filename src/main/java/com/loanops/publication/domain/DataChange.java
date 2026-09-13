package com.loanops.publication.domain;

import com.loanops.source.domain.SourceRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(schema = "loanops", name = "data_changes")
public class DataChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publication_run_id", nullable = false, updatable = false)
    private PublicationRun publicationRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_run_id", nullable = false, updatable = false)
    private SourceRun sourceRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 32, updatable = false)
    private QualityRecordType recordType;

    @Column(name = "stable_record_identity", nullable = false, length = 1000, updatable = false)
    private String stableRecordIdentity;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16, updatable = false)
    private DataChangeType changeType;

    @Column(name = "previous_record_id", updatable = false)
    private Long previousRecordId;

    @Column(name = "current_record_id", nullable = false, updatable = false)
    private long currentRecordId;

    @Column(name = "changed_fields", length = 1000, updatable = false)
    private String changedFields;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DataChange() {
    }

    public DataChange(
            PublicationRun publicationRun,
            SourceRun sourceRun,
            QualityRecordType recordType,
            String stableRecordIdentity,
            DataChangeType changeType,
            Long previousRecordId,
            long currentRecordId,
            String changedFields,
            Instant createdAt
    ) {
        this.publicationRun = Objects.requireNonNull(publicationRun);
        this.sourceRun = Objects.requireNonNull(sourceRun);
        this.recordType = Objects.requireNonNull(recordType);
        this.stableRecordIdentity = Objects.requireNonNull(stableRecordIdentity);
        this.changeType = Objects.requireNonNull(changeType);
        this.previousRecordId = previousRecordId;
        this.currentRecordId = currentRecordId;
        this.changedFields = changedFields;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public PublicationRun getPublicationRun() { return publicationRun; }
    public SourceRun getSourceRun() { return sourceRun; }
    public QualityRecordType getRecordType() { return recordType; }
    public String getStableRecordIdentity() { return stableRecordIdentity; }
    public DataChangeType getChangeType() { return changeType; }
    public Long getPreviousRecordId() { return previousRecordId; }
    public long getCurrentRecordId() { return currentRecordId; }
    public String getChangedFields() { return changedFields; }
    public Instant getCreatedAt() { return createdAt; }
}
