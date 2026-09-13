package com.loanops.normalization.domain;

import com.loanops.source.domain.RawSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(schema = "loanops", name = "loan_rate_observations")
public class LoanRateObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_alias_id", nullable = false, updatable = false)
    private InstitutionAlias institutionAlias;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "financial_institution_id", updatable = false)
    private FinancialInstitution financialInstitution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_snapshot_id", nullable = false, updatable = false)
    private RawSnapshot rawSnapshot;

    @Column(name = "source_institution_code", nullable = false, length = 100, updatable = false)
    private String sourceInstitutionCode;

    @Column(name = "reference_start_date", nullable = false, updatable = false)
    private LocalDate referenceStartDate;

    @Column(name = "reference_end_date", nullable = false, updatable = false)
    private LocalDate referenceEndDate;

    @Column(name = "loan_count", nullable = false, updatable = false)
    private long loanCount;

    @Column(name = "minimum_loan_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal minimumLoanRate;

    @Column(name = "maximum_loan_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal maximumLoanRate;

    @Column(name = "average_loan_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal averageLoanRate;

    @Column(name = "minimum_loan_ale_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal minimumLoanAleRate;

    @Column(name = "maximum_loan_ale_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal maximumLoanAleRate;

    @Column(name = "average_loan_ale_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal averageLoanAleRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoanRateObservation() {
    }

    public LoanRateObservation(
            InstitutionAlias institutionAlias,
            RawSnapshot rawSnapshot,
            String sourceInstitutionCode,
            LocalDate referenceStartDate,
            LocalDate referenceEndDate,
            long loanCount,
            BigDecimal minimumLoanRate,
            BigDecimal maximumLoanRate,
            BigDecimal averageLoanRate,
            BigDecimal minimumLoanAleRate,
            BigDecimal maximumLoanAleRate,
            BigDecimal averageLoanAleRate,
            Instant createdAt
    ) {
        this.institutionAlias = Objects.requireNonNull(institutionAlias);
        this.financialInstitution = institutionAlias.getFinancialInstitution();
        this.rawSnapshot = Objects.requireNonNull(rawSnapshot);
        this.sourceInstitutionCode = Objects.requireNonNull(sourceInstitutionCode);
        this.referenceStartDate = Objects.requireNonNull(referenceStartDate);
        this.referenceEndDate = Objects.requireNonNull(referenceEndDate);
        this.loanCount = loanCount;
        this.minimumLoanRate = Objects.requireNonNull(minimumLoanRate);
        this.maximumLoanRate = Objects.requireNonNull(maximumLoanRate);
        this.averageLoanRate = Objects.requireNonNull(averageLoanRate);
        this.minimumLoanAleRate = Objects.requireNonNull(minimumLoanAleRate);
        this.maximumLoanAleRate = Objects.requireNonNull(maximumLoanAleRate);
        this.averageLoanAleRate = Objects.requireNonNull(averageLoanAleRate);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public InstitutionAlias getInstitutionAlias() { return institutionAlias; }
    public FinancialInstitution getFinancialInstitution() { return financialInstitution; }
    public RawSnapshot getRawSnapshot() { return rawSnapshot; }
    public String getSourceInstitutionCode() { return sourceInstitutionCode; }
    public LocalDate getReferenceStartDate() { return referenceStartDate; }
    public LocalDate getReferenceEndDate() { return referenceEndDate; }
    public long getLoanCount() { return loanCount; }
    public BigDecimal getMinimumLoanRate() { return minimumLoanRate; }
    public BigDecimal getMaximumLoanRate() { return maximumLoanRate; }
    public BigDecimal getAverageLoanRate() { return averageLoanRate; }
    public BigDecimal getMinimumLoanAleRate() { return minimumLoanAleRate; }
    public BigDecimal getMaximumLoanAleRate() { return maximumLoanAleRate; }
    public BigDecimal getAverageLoanAleRate() { return averageLoanAleRate; }
    public Instant getCreatedAt() { return createdAt; }
}
