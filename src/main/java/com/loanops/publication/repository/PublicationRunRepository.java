package com.loanops.publication.repository;

import com.loanops.publication.domain.PublicationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PublicationRunRepository extends JpaRepository<PublicationRun, Long> {

    Optional<PublicationRun> findBySourceRunId(Long sourceRunId);
}
