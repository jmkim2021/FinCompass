package com.loanops.publication.repository;

import com.loanops.publication.domain.ServingProductTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServingProductTermRepository extends JpaRepository<ServingProductTerm, Long> {

    Optional<ServingProductTerm> findBySourceInstitutionCodeAndSourceProductIdAndMortgageTypeCodeAndRepaymentTypeCodeAndLendingRateTypeCode(
            String sourceInstitutionCode,
            String sourceProductId,
            String mortgageTypeCode,
            String repaymentTypeCode,
            String lendingRateTypeCode
    );
}
