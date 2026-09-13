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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(schema = "loanops", name = "publication_runs")
public class PublicationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_run_id", nullable = false, unique = true, updatable = false)
    private SourceRun sourceRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private PublicationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    protected PublicationRun() {
    }

    public PublicationRun(SourceRun sourceRun, PublicationStatus status, Instant now) {
        this.sourceRun = Objects.requireNonNull(sourceRun);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(now);
        this.completedAt = now;
    }

    public Long getId() { return id; }
    public SourceRun getSourceRun() { return sourceRun; }
    public PublicationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
