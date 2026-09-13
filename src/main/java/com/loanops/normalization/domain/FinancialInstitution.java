package com.loanops.normalization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(schema = "loanops", name = "financial_institutions")
public class FinancialInstitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canonical_name", nullable = false, unique = true, length = 200)
    private String canonicalName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancialInstitution() {
    }

    private FinancialInstitution(String canonicalName, Instant now) {
        this.canonicalName = Objects.requireNonNull(canonicalName);
        this.active = true;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static FinancialInstitution create(String canonicalName, Instant now) {
        return new FinancialInstitution(canonicalName, now);
    }

    public Long getId() {
        return id;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
