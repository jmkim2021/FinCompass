package com.loanops.normalization.manual;

import com.loanops.normalization.NormalizationResult;
import com.loanops.normalization.SourceNormalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("manual-normalization")
public class ManualNormalizationConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ManualNormalizationConfiguration.class);

    @Bean
    ApplicationRunner manualNormalizationRunner(
            SourceNormalizationService normalizationService,
            @Value("${loanops.manual.run-id:0}") long sourceRunId
    ) {
        return arguments -> {
            if (sourceRunId < 1) {
                throw new IllegalArgumentException("loanops.manual.run-id must be positive");
            }
            NormalizationResult result = normalizationService.normalize(sourceRunId);
            logger.info(
                    "Manual normalization completed: source={}, runId={}, snapshots={}, products={}, terms={}, observations={}",
                    result.sourceCode(),
                    result.sourceRunId(),
                    result.snapshotCount(),
                    result.productCount(),
                    result.termCount(),
                    result.observationCount()
            );
        };
    }
}
