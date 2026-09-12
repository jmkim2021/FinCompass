package com.loanops.source.repository;

import com.loanops.source.domain.DataSource;
import com.loanops.source.domain.DataSourceCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    Optional<DataSource> findByCodeAndActiveTrue(DataSourceCode code);
}
