package com.loanops.publication;

import com.loanops.publication.domain.PublicationRun;
import com.loanops.publication.repository.DataChangeRepository;
import com.loanops.publication.repository.DataQualityIssueRepository;
import com.loanops.publication.repository.PublicationRunRepository;
import com.loanops.publication.repository.QuarantineRecordRepository;
import org.springframework.stereotype.Service;

@Service
public class PublicationService {

    private final PublicationRunRepository publicationRunRepository;
    private final DataQualityIssueRepository issueRepository;
    private final QuarantineRecordRepository quarantineRepository;
    private final DataChangeRepository dataChangeRepository;
    private final QualityValidationService qualityValidationService;
    private final PublicationTransactionService transactionService;

    public PublicationService(
            PublicationRunRepository publicationRunRepository,
            DataQualityIssueRepository issueRepository,
            QuarantineRecordRepository quarantineRepository,
            DataChangeRepository dataChangeRepository,
            QualityValidationService qualityValidationService,
            PublicationTransactionService transactionService
    ) {
        this.publicationRunRepository = publicationRunRepository;
        this.issueRepository = issueRepository;
        this.quarantineRepository = quarantineRepository;
        this.dataChangeRepository = dataChangeRepository;
        this.qualityValidationService = qualityValidationService;
        this.transactionService = transactionService;
    }

    public PublicationResult publish(long sourceRunId) {
        PublicationRun existing = publicationRunRepository.findBySourceRunId(sourceRunId).orElse(null);
        if (existing != null) {
            return result(existing, sourceRunId, true);
        }

        QualityValidationResult validation = qualityValidationService.validateAndPersist(sourceRunId);
        PublicationRun publicationRun = validation.valid()
                ? transactionService.promote(sourceRunId)
                : transactionService.rejectQuality(sourceRunId);
        return result(publicationRun, sourceRunId, false);
    }

    private PublicationResult result(PublicationRun publicationRun, long sourceRunId, boolean alreadyFinalized) {
        return new PublicationResult(
                sourceRunId,
                publicationRun.getId(),
                publicationRun.getStatus(),
                issueRepository.countBySourceRunId(sourceRunId),
                quarantineRepository.countBySourceRunId(sourceRunId),
                dataChangeRepository.countByPublicationRunId(publicationRun.getId()),
                alreadyFinalized
        );
    }
}
