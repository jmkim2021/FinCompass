package com.loanops.source.domain;

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
@Table(schema = "loanops", name = "source_runs")
public class SourceRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false)
    private DataSource dataSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SourceRunStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 32)
    private SourceRunErrorType errorType;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_of_run_id")
    private Long retryOfRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SourceRun() {
    }

    private SourceRun(DataSource dataSource, Instant startedAt) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.status = SourceRunStatus.RUNNING;
        this.attemptCount = 1;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.createdAt = startedAt;
    }

    public static SourceRun start(DataSource dataSource, Instant startedAt) {
        return new SourceRun(dataSource, startedAt);
    }

    public void markSuccess(int httpStatus, Instant endedAt) {
        requireRunning();
        this.status = SourceRunStatus.SUCCESS;
        this.httpStatus = httpStatus;
        this.endedAt = Objects.requireNonNull(endedAt);
    }

    public void markFailed(SourceRunErrorType errorType, String errorMessage, Integer httpStatus, Instant endedAt) {
        requireRunning();
        this.status = SourceRunStatus.FAILED;
        this.errorType = Objects.requireNonNull(errorType);
        this.errorMessage = Objects.requireNonNull(errorMessage);
        this.httpStatus = httpStatus;
        this.endedAt = Objects.requireNonNull(endedAt);
    }

    private void requireRunning() {
        if (status != SourceRunStatus.RUNNING) {
            throw new IllegalStateException("Only a RUNNING source run can be completed");
        }
    }

    public Long getId() {
        return id;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public SourceRunStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public SourceRunErrorType getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Long getRetryOfRunId() {
        return retryOfRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
