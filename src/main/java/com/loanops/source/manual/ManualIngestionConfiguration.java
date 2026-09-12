package com.loanops.source.manual;

import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.service.IngestionResult;
import com.loanops.source.service.SourceIngestionException;
import com.loanops.source.service.SourceIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile("manual-ingestion")
public class ManualIngestionConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ManualIngestionConfiguration.class);

    @Bean
    ApplicationRunner manualIngestionRunner(
            SourceIngestionService ingestionService,
            @Value("${loanops.manual.source:}") String configuredSource
    ) {
        return arguments -> {
            if (!StringUtils.hasText(configuredSource)) {
                throw new IllegalArgumentException("loanops.manual.source is required");
            }

            DataSourceCode sourceCode = DataSourceCode.valueOf(configuredSource);
            try {
                IngestionResult result = ingestionService.ingest(sourceCode);
                logger.info(
                        "Manual ingestion succeeded: source={}, runId={}, httpStatus={}, payloadHash={}",
                        result.sourceCode(),
                        result.sourceRunId(),
                        result.httpStatus(),
                        result.payloadHash()
                );
            } catch (SourceIngestionException exception) {
                logger.error(
                        "Manual ingestion failed: source={}, runId={}, errorType={}",
                        exception.getSourceCode(),
                        exception.getSourceRunId(),
                        exception.getErrorType()
                );
                throw exception;
            }
        };
    }
}
