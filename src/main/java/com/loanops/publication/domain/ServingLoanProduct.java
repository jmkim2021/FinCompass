package com.loanops.publication.domain;

import com.loanops.normalization.domain.LoanProduct;
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
@Table(schema = "loanops", name = "serving_loan_products")
public class ServingLoanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_institution_code", nullable = false, length = 100, updatable = false)
    private String sourceInstitutionCode;

    @Column(name = "source_product_id", nullable = false, length = 200, updatable = false)
    private String sourceProductId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "current_loan_product_id", nullable = false, unique = true)
    private LoanProduct currentLoanProduct;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promoted_source_run_id", nullable = false)
    private SourceRun promotedSourceRun;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServingLoanProduct() {
    }

    public ServingLoanProduct(LoanProduct product, SourceRun sourceRun, Instant now) {
        this.sourceInstitutionCode = Objects.requireNonNull(product.getSourceInstitutionCode());
        this.sourceProductId = Objects.requireNonNull(product.getSourceProductId());
        pointTo(product, sourceRun, now);
    }

    public void pointTo(LoanProduct product, SourceRun sourceRun, Instant now) {
        this.currentLoanProduct = Objects.requireNonNull(product);
        this.promotedSourceRun = Objects.requireNonNull(sourceRun);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long getId() { return id; }
    public String getSourceInstitutionCode() { return sourceInstitutionCode; }
    public String getSourceProductId() { return sourceProductId; }
    public LoanProduct getCurrentLoanProduct() { return currentLoanProduct; }
    public SourceRun getPromotedSourceRun() { return promotedSourceRun; }
    public Instant getUpdatedAt() { return updatedAt; }
}
