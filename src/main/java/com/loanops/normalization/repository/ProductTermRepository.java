package com.loanops.normalization.repository;

import com.loanops.normalization.domain.ProductTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductTermRepository extends JpaRepository<ProductTerm, Long> {

    boolean existsByRawSnapshotIdAndLoanProductIdAndMortgageTypeCodeAndRepaymentTypeCodeAndLendingRateTypeCode(
            Long rawSnapshotId,
            Long loanProductId,
            String mortgageTypeCode,
            String repaymentTypeCode,
            String lendingRateTypeCode
    );

    List<ProductTerm> findAllByRawSnapshotSourceRunIdOrderByIdAsc(Long sourceRunId);
}
