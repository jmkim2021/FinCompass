package com.loanops.publication.domain;

import com.loanops.normalization.domain.InstitutionAlias;
import com.loanops.normalization.domain.LoanRateObservation;
import com.loanops.source.domain.SourceRun;
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
@Table(schema = "loanops", name = "serving_loan_rate_observations")
public class ServingLoanRateObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_alias_id", nullable = false, unique = true, updatable = false)
    private InstitutionAlias institutionAlias;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "current_loan_rate_observation_id", nullable = false, unique = true)
    private LoanRateObservation currentLoanRateObservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promoted_source_run_id", nullable = false)
    private SourceRun promotedSourceRun;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServingLoanRateObservation() {
    }

    public ServingLoanRateObservation(LoanRateObservation observation, SourceRun sourceRun, Instant now) {
        this.institutionAlias = Objects.requireNonNull(observation.getInstitutionAlias());
        pointTo(observation, sourceRun, now);
    }

    public void pointTo(LoanRateObservation observation, SourceRun sourceRun, Instant now) {
        this.currentLoanRateObservation = Objects.requireNonNull(observation);
        this.promotedSourceRun = Objects.requireNonNull(sourceRun);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long getId() { return id; }
    public InstitutionAlias getInstitutionAlias() { return institutionAlias; }
    public LoanRateObservation getCurrentLoanRateObservation() { return currentLoanRateObservation; }
    public SourceRun getPromotedSourceRun() { return promotedSourceRun; }
    public Instant getUpdatedAt() { return updatedAt; }
}
