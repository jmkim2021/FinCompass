package com.loanops.normalization.repository;

import com.loanops.normalization.domain.LoanRateObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface LoanRateObservationRepository extends JpaRepository<LoanRateObservation, Long> {

    boolean existsByRawSnapshotIdAndSourceInstitutionCodeAndReferenceStartDateAndReferenceEndDate(
            Long rawSnapshotId,
            String sourceInstitutionCode,
            LocalDate referenceStartDate,
            LocalDate referenceEndDate
    );
}
