package com.loanops.publication.repository;

import com.loanops.publication.domain.ServingLoanProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServingLoanProductRepository extends JpaRepository<ServingLoanProduct, Long> {

    Optional<ServingLoanProduct> findBySourceInstitutionCodeAndSourceProductId(
            String sourceInstitutionCode,
            String sourceProductId
    );
}
