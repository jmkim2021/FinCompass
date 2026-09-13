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
import java.util.Objects;

@Entity
@Table(schema = "loanops", name = "product_terms")
public class ProductTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_product_id", nullable = false, updatable = false)
    private LoanProduct loanProduct;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_snapshot_id", nullable = false, updatable = false)
    private RawSnapshot rawSnapshot;

    @Column(name = "disclosure_month", nullable = false, length = 6, updatable = false)
    private String disclosureMonth;

    @Column(name = "mortgage_type_code", nullable = false, length = 20, updatable = false)
    private String mortgageTypeCode;

    @Column(name = "mortgage_type_name", nullable = false, length = 100, updatable = false)
    private String mortgageTypeName;

    @Column(name = "repayment_type_code", nullable = false, length = 20, updatable = false)
    private String repaymentTypeCode;

    @Column(name = "repayment_type_name", nullable = false, length = 100, updatable = false)
    private String repaymentTypeName;

    @Column(name = "lending_rate_type_code", nullable = false, length = 20, updatable = false)
    private String lendingRateTypeCode;

    @Column(name = "lending_rate_type_name", nullable = false, length = 100, updatable = false)
    private String lendingRateTypeName;

    @Column(name = "minimum_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal minimumRate;

    @Column(name = "maximum_rate", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal maximumRate;

    @Column(name = "average_rate", precision = 10, scale = 4, updatable = false)
    private BigDecimal averageRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductTerm() {
    }

    public ProductTerm(
            LoanProduct loanProduct,
            RawSnapshot rawSnapshot,
            String disclosureMonth,
            String mortgageTypeCode,
            String mortgageTypeName,
            String repaymentTypeCode,
            String repaymentTypeName,
            String lendingRateTypeCode,
            String lendingRateTypeName,
            BigDecimal minimumRate,
            BigDecimal maximumRate,
            BigDecimal averageRate,
            Instant createdAt
    ) {
        this.loanProduct = Objects.requireNonNull(loanProduct);
        this.rawSnapshot = Objects.requireNonNull(rawSnapshot);
        this.disclosureMonth = Objects.requireNonNull(disclosureMonth);
        this.mortgageTypeCode = Objects.requireNonNull(mortgageTypeCode);
        this.mortgageTypeName = Objects.requireNonNull(mortgageTypeName);
        this.repaymentTypeCode = Objects.requireNonNull(repaymentTypeCode);
        this.repaymentTypeName = Objects.requireNonNull(repaymentTypeName);
        this.lendingRateTypeCode = Objects.requireNonNull(lendingRateTypeCode);
        this.lendingRateTypeName = Objects.requireNonNull(lendingRateTypeName);
        this.minimumRate = Objects.requireNonNull(minimumRate);
        this.maximumRate = Objects.requireNonNull(maximumRate);
        this.averageRate = averageRate;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public LoanProduct getLoanProduct() { return loanProduct; }
    public RawSnapshot getRawSnapshot() { return rawSnapshot; }
    public String getDisclosureMonth() { return disclosureMonth; }
    public String getMortgageTypeCode() { return mortgageTypeCode; }
    public String getMortgageTypeName() { return mortgageTypeName; }
    public String getRepaymentTypeCode() { return repaymentTypeCode; }
    public String getRepaymentTypeName() { return repaymentTypeName; }
    public String getLendingRateTypeCode() { return lendingRateTypeCode; }
    public String getLendingRateTypeName() { return lendingRateTypeName; }
    public BigDecimal getMinimumRate() { return minimumRate; }
    public BigDecimal getMaximumRate() { return maximumRate; }
    public BigDecimal getAverageRate() { return averageRate; }
    public Instant getCreatedAt() { return createdAt; }
}
