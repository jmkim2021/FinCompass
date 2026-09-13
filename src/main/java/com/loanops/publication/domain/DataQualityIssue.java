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
@Table(schema = "loanops", name = "data_quality_issues")
public class DataQualityIssue {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_code", nullable = false, length = 64, updatable = false)
    private QualityRuleCode ruleCode;

    @Column(name = "field_name", length = 100, updatable = false)
    private String fieldName;

    @Column(name = "observed_value", length = 1000, updatable = false)
    private String observedValue;

    @Column(nullable = false, length = 1000, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DataQualityIssue() {
    }

    public DataQualityIssue(
            SourceRun sourceRun,
            RawSnapshot rawSnapshot,
            QualityRecordType recordType,
            long recordId,
            QualityRuleCode ruleCode,
            String fieldName,
            String observedValue,
            String message,
            Instant createdAt
    ) {
        this.sourceRun = Objects.requireNonNull(sourceRun);
        this.rawSnapshot = Objects.requireNonNull(rawSnapshot);
        this.recordType = Objects.requireNonNull(recordType);
        this.recordId = recordId;
        this.ruleCode = Objects.requireNonNull(ruleCode);
        this.fieldName = fieldName;
        this.observedValue = observedValue;
        this.message = Objects.requireNonNull(message);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public SourceRun getSourceRun() { return sourceRun; }
    public RawSnapshot getRawSnapshot() { return rawSnapshot; }
    public QualityRecordType getRecordType() { return recordType; }
    public long getRecordId() { return recordId; }
    public QualityRuleCode getRuleCode() { return ruleCode; }
    public String getFieldName() { return fieldName; }
    public String getObservedValue() { return observedValue; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
