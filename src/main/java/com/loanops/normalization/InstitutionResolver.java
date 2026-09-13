package com.loanops.normalization;

import com.loanops.normalization.domain.FinancialInstitution;
import com.loanops.normalization.domain.InstitutionAlias;
import com.loanops.normalization.repository.FinancialInstitutionRepository;
import com.loanops.normalization.repository.InstitutionAliasRepository;
import com.loanops.source.domain.DataSource;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class InstitutionResolver {

    private final FinancialInstitutionRepository financialInstitutionRepository;
    private final InstitutionAliasRepository institutionAliasRepository;

    public InstitutionResolver(
            FinancialInstitutionRepository financialInstitutionRepository,
            InstitutionAliasRepository institutionAliasRepository
    ) {
        this.financialInstitutionRepository = financialInstitutionRepository;
        this.institutionAliasRepository = institutionAliasRepository;
    }

    public InstitutionAlias resolveFss(DataSource dataSource, String sourceCode, String sourceName) {
        return institutionAliasRepository
                .findByDataSourceCodeAndSourceInstitutionCodeAndSourceInstitutionName(
                        dataSource.getCode(),
                        sourceCode,
                        sourceName
                )
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    FinancialInstitution institution = financialInstitutionRepository
                            .findByCanonicalName(sourceName)
                            .orElseGet(() -> financialInstitutionRepository.save(
                                    FinancialInstitution.create(sourceName, now)
                            ));
                    return institutionAliasRepository.save(
                            InstitutionAlias.mapped(dataSource, sourceCode, sourceName, institution, now)
                    );
                });
    }

    public InstitutionAlias resolveSeoul(DataSource dataSource, String sourceCode, String sourceName) {
        return institutionAliasRepository
                .findByDataSourceCodeAndSourceInstitutionCodeAndSourceInstitutionName(
                        dataSource.getCode(),
                        sourceCode,
                        sourceName
                )
                .orElseGet(() -> institutionAliasRepository.save(
                        InstitutionAlias.unmapped(dataSource, sourceCode, sourceName, Instant.now())
                ));
    }
}
