package com.loanops.publication.domain;

import com.loanops.source.domain.RawSnapshot;
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
@Table(schema = "loanops", name = "quarantine_records")
public class QuarantineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_run_id", nullable = false, updatable = false)
    private SourceRun sourceRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_snapshot_id", nullable = false, updatable = false)
    private RawSnapshot rawSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 32, updatable = false)
    private QualityRecordType recordType;

    @Column(name = "record_id", nullable = false, updatable = false)
    private long recordId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuarantineRecord() {
    }

    public QuarantineRecord(
            SourceRun sourceRun,
            RawSnapshot rawSnapshot,
            QualityRecordType recordType,
            long recordId,
            Instant createdAt
    ) {
        this.sourceRun = Objects.requireNonNull(sourceRun);
        this.rawSnapshot = Objects.requireNonNull(rawSnapshot);
        this.recordType = Objects.requireNonNull(recordType);
        this.recordId = recordId;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public SourceRun getSourceRun() { return sourceRun; }
    public RawSnapshot getRawSnapshot() { return rawSnapshot; }
    public QualityRecordType getRecordType() { return recordType; }
    public long getRecordId() { return recordId; }
    public Instant getCreatedAt() { return createdAt; }
}
