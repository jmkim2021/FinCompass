package com.loanops.source.repository;

import com.loanops.source.domain.RawSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RawSnapshotRepository extends JpaRepository<RawSnapshot, Long> {

    Optional<RawSnapshot> findBySourceRunId(Long sourceRunId);
}
