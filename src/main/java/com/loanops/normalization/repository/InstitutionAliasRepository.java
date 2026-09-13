package com.loanops.normalization.repository;

import com.loanops.normalization.domain.InstitutionAlias;
import com.loanops.source.domain.DataSourceCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstitutionAliasRepository extends JpaRepository<InstitutionAlias, Long> {

    Optional<InstitutionAlias> findByDataSourceCodeAndSourceInstitutionCodeAndSourceInstitutionName(
            DataSourceCode sourceCode,
            String sourceInstitutionCode,
            String sourceInstitutionName
    );
}
