package com.loanops.normalization.domain;

import com.loanops.source.domain.DataSource;
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
@Table(schema = "loanops", name = "institution_aliases")
public class InstitutionAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false, updatable = false)
    private DataSource dataSource;

    @Column(name = "source_institution_code", nullable = false, length = 100, updatable = false)
    private String sourceInstitutionCode;

    @Column(name = "source_institution_name", nullable = false, length = 200, updatable = false)
    private String sourceInstitutionName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "financial_institution_id", updatable = false)
    private FinancialInstitution financialInstitution;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_status", nullable = false, length = 16, updatable = false)
    private InstitutionMappingStatus mappingStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InstitutionAlias() {
    }

    private InstitutionAlias(
            DataSource dataSource,
            String sourceInstitutionCode,
            String sourceInstitutionName,
            FinancialInstitution financialInstitution,
            InstitutionMappingStatus mappingStatus,
            Instant now
    ) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.sourceInstitutionCode = Objects.requireNonNull(sourceInstitutionCode);
        this.sourceInstitutionName = Objects.requireNonNull(sourceInstitutionName);
        this.financialInstitution = financialInstitution;
        this.mappingStatus = Objects.requireNonNull(mappingStatus);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static InstitutionAlias mapped(
            DataSource dataSource,
            String sourceInstitutionCode,
            String sourceInstitutionName,
            FinancialInstitution financialInstitution,
            Instant now
    ) {
        return new InstitutionAlias(
                dataSource,
                sourceInstitutionCode,
                sourceInstitutionName,
                Objects.requireNonNull(financialInstitution),
                InstitutionMappingStatus.MAPPED,
                now
        );
    }

    public static InstitutionAlias unmapped(
            DataSource dataSource,
            String sourceInstitutionCode,
            String sourceInstitutionName,
            Instant now
    ) {
        return new InstitutionAlias(
                dataSource,
                sourceInstitutionCode,
                sourceInstitutionName,
                null,
                InstitutionMappingStatus.UNMAPPED,
                now
        );
    }

    public Long getId() {
        return id;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getSourceInstitutionCode() {
        return sourceInstitutionCode;
    }

    public String getSourceInstitutionName() {
        return sourceInstitutionName;
    }

    public FinancialInstitution getFinancialInstitution() {
        return financialInstitution;
    }

    public InstitutionMappingStatus getMappingStatus() {
        return mappingStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
