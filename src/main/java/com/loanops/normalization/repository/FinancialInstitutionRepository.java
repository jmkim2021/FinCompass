package com.loanops.normalization.repository;

import com.loanops.normalization.domain.FinancialInstitution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinancialInstitutionRepository extends JpaRepository<FinancialInstitution, Long> {

    Optional<FinancialInstitution> findByCanonicalName(String canonicalName);
}
