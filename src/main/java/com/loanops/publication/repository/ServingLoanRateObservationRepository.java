package com.loanops.publication.repository;

import com.loanops.publication.domain.ServingLoanRateObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServingLoanRateObservationRepository extends JpaRepository<ServingLoanRateObservation, Long> {

    Optional<ServingLoanRateObservation> findByInstitutionAliasId(Long institutionAliasId);
}
