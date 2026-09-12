package com.loanops.source.repository;

import com.loanops.source.domain.SourceRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRunRepository extends JpaRepository<SourceRun, Long> {
}
