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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(schema = "loanops", name = "loan_products")
public class LoanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_alias_id", nullable = false, updatable = false)
    private InstitutionAlias institutionAlias;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_institution_id", nullable = false, updatable = false)
    private FinancialInstitution financialInstitution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_snapshot_id", nullable = false, updatable = false)
    private RawSnapshot rawSnapshot;

    @Column(name = "source_institution_code", nullable = false, length = 100, updatable = false)
    private String sourceInstitutionCode;

    @Column(name = "source_product_id", nullable = false, length = 200, updatable = false)
    private String sourceProductId;

    @Column(name = "product_name", nullable = false, length = 300, updatable = false)
    private String productName;

    @Column(name = "disclosure_month", nullable = false, length = 6, updatable = false)
    private String disclosureMonth;

    @Column(name = "disclosure_start_date", updatable = false)
    private LocalDate disclosureStartDate;

    @Column(name = "disclosure_end_date", updatable = false)
    private LocalDate disclosureEndDate;

    @Column(name = "source_submitted_at", updatable = false)
    private LocalDateTime sourceSubmittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoanProduct() {
    }

    public LoanProduct(
            InstitutionAlias institutionAlias,
            RawSnapshot rawSnapshot,
            String sourceInstitutionCode,
            String sourceProductId,
            String productName,
            String disclosureMonth,
            LocalDate disclosureStartDate,
            LocalDate disclosureEndDate,
            LocalDateTime sourceSubmittedAt,
            Instant createdAt
    ) {
        this.institutionAlias = Objects.requireNonNull(institutionAlias);
        this.financialInstitution = Objects.requireNonNull(institutionAlias.getFinancialInstitution());
        this.rawSnapshot = Objects.requireNonNull(rawSnapshot);
        this.sourceInstitutionCode = Objects.requireNonNull(sourceInstitutionCode);
        this.sourceProductId = Objects.requireNonNull(sourceProductId);
        this.productName = Objects.requireNonNull(productName);
        this.disclosureMonth = Objects.requireNonNull(disclosureMonth);
        this.disclosureStartDate = disclosureStartDate;
        this.disclosureEndDate = disclosureEndDate;
        this.sourceSubmittedAt = sourceSubmittedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() { return id; }
    public InstitutionAlias getInstitutionAlias() { return institutionAlias; }
    public FinancialInstitution getFinancialInstitution() { return financialInstitution; }
    public RawSnapshot getRawSnapshot() { return rawSnapshot; }
    public String getSourceInstitutionCode() { return sourceInstitutionCode; }
    public String getSourceProductId() { return sourceProductId; }
    public String getProductName() { return productName; }
    public String getDisclosureMonth() { return disclosureMonth; }
    public LocalDate getDisclosureStartDate() { return disclosureStartDate; }
    public LocalDate getDisclosureEndDate() { return disclosureEndDate; }
    public LocalDateTime getSourceSubmittedAt() { return sourceSubmittedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
