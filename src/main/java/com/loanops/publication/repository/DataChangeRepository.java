package com.loanops.publication.repository;

import com.loanops.publication.domain.DataChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataChangeRepository extends JpaRepository<DataChange, Long> {

    long countByPublicationRunId(Long publicationRunId);

    List<DataChange> findAllByPublicationRunIdOrderByIdAsc(Long publicationRunId);
}
