package com.loanops.publication.manual;

import com.loanops.publication.PublicationResult;
import com.loanops.publication.PublicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("manual-publication")
public class ManualPublicationConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ManualPublicationConfiguration.class);

    @Bean
    ApplicationRunner manualPublicationRunner(
            PublicationService publicationService,
            @Value("${loanops.manual.run-id:0}") long sourceRunId
    ) {
        return arguments -> {
            if (sourceRunId < 1) {
                throw new IllegalArgumentException("loanops.manual.run-id must be positive");
            }
            PublicationResult result = publicationService.publish(sourceRunId);
            logger.info(
                    "Manual publication completed: runId={}, publicationId={}, status={}, issues={}, quarantined={}, changes={}, alreadyFinalized={}",
                    result.sourceRunId(),
                    result.publicationRunId(),
                    result.status(),
                    result.issueCount(),
                    result.quarantineCount(),
                    result.changeCount(),
                    result.alreadyFinalized()
            );
        };
    }
}
