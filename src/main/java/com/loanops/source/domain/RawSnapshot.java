package com.loanops.source.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(schema = "loanops", name = "raw_snapshots")
public class RawSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_run_id", nullable = false, updatable = false)
    private SourceRun sourceRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false, updatable = false)
    private DataSource dataSource;

    @Column(name = "content_type", nullable = false, length = 200, updatable = false)
    private String contentType;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "text", updatable = false)
    private String rawPayload;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RawSnapshot() {
    }

    private RawSnapshot(
            SourceRun sourceRun,
            DataSource dataSource,
            String contentType,
            String rawPayload,
            String payloadHash,
            Instant fetchedAt,
            Instant createdAt
    ) {
        this.sourceRun = Objects.requireNonNull(sourceRun);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.contentType = Objects.requireNonNull(contentType);
        this.rawPayload = Objects.requireNonNull(rawPayload);
        this.payloadHash = Objects.requireNonNull(payloadHash);
        this.fetchedAt = Objects.requireNonNull(fetchedAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static RawSnapshot create(
            SourceRun sourceRun,
            String contentType,
            String rawPayload,
            String payloadHash,
            Instant fetchedAt,
            Instant createdAt
    ) {
        return new RawSnapshot(
                sourceRun,
                sourceRun.getDataSource(),
                contentType,
                rawPayload,
                payloadHash,
                fetchedAt,
                createdAt
        );
    }

    public Long getId() {
        return id;
    }

    public SourceRun getSourceRun() {
        return sourceRun;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getContentType() {
        return contentType;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
