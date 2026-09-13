package com.loanops.publication.domain;

import com.loanops.normalization.domain.LoanProduct;
import com.loanops.normalization.domain.ProductTerm;
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
@Table(schema = "loanops", name = "serving_product_terms")
public class ServingProductTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_institution_code", nullable = false, length = 100, updatable = false)
    private String sourceInstitutionCode;

    @Column(name = "source_product_id", nullable = false, length = 200, updatable = false)
    private String sourceProductId;

    @Column(name = "mortgage_type_code", nullable = false, length = 20, updatable = false)
    private String mortgageTypeCode;

    @Column(name = "repayment_type_code", nullable = false, length = 20, updatable = false)
    private String repaymentTypeCode;

    @Column(name = "lending_rate_type_code", nullable = false, length = 20, updatable = false)
    private String lendingRateTypeCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "current_product_term_id", nullable = false, unique = true)
    private ProductTerm currentProductTerm;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promoted_source_run_id", nullable = false)
    private SourceRun promotedSourceRun;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServingProductTerm() {
    }

    public ServingProductTerm(ProductTerm term, SourceRun sourceRun, Instant now) {
        LoanProduct product = term.getLoanProduct();
        this.sourceInstitutionCode = Objects.requireNonNull(product.getSourceInstitutionCode());
        this.sourceProductId = Objects.requireNonNull(product.getSourceProductId());
        this.mortgageTypeCode = Objects.requireNonNull(term.getMortgageTypeCode());
        this.repaymentTypeCode = Objects.requireNonNull(term.getRepaymentTypeCode());
        this.lendingRateTypeCode = Objects.requireNonNull(term.getLendingRateTypeCode());
        pointTo(term, sourceRun, now);
    }

    public void pointTo(ProductTerm term, SourceRun sourceRun, Instant now) {
        this.currentProductTerm = Objects.requireNonNull(term);
        this.promotedSourceRun = Objects.requireNonNull(sourceRun);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public Long getId() { return id; }
    public String getSourceInstitutionCode() { return sourceInstitutionCode; }
    public String getSourceProductId() { return sourceProductId; }
    public String getMortgageTypeCode() { return mortgageTypeCode; }
    public String getRepaymentTypeCode() { return repaymentTypeCode; }
    public String getLendingRateTypeCode() { return lendingRateTypeCode; }
    public ProductTerm getCurrentProductTerm() { return currentProductTerm; }
    public SourceRun getPromotedSourceRun() { return promotedSourceRun; }
    public Instant getUpdatedAt() { return updatedAt; }
}
