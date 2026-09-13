package com.loanops.normalization.repository;

import com.loanops.normalization.domain.LoanProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

    Optional<LoanProduct> findByRawSnapshotIdAndSourceInstitutionCodeAndSourceProductId(
            Long rawSnapshotId,
            String sourceInstitutionCode,
            String sourceProductId
    );
}
