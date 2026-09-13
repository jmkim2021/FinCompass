package com.loanops.publication;

import com.loanops.normalization.domain.LoanProduct;
import com.loanops.normalization.domain.LoanRateObservation;
import com.loanops.normalization.domain.ProductTerm;
import com.loanops.normalization.repository.LoanProductRepository;
import com.loanops.normalization.repository.LoanRateObservationRepository;
import com.loanops.normalization.repository.ProductTermRepository;
import com.loanops.publication.domain.DataChange;
import com.loanops.publication.domain.DataChangeType;
import com.loanops.publication.domain.PublicationRun;
import com.loanops.publication.domain.PublicationStatus;
import com.loanops.publication.domain.QualityRecordType;
import com.loanops.publication.domain.ServingLoanProduct;
import com.loanops.publication.domain.ServingLoanRateObservation;
import com.loanops.publication.domain.ServingProductTerm;
import com.loanops.publication.repository.DataChangeRepository;
import com.loanops.publication.repository.DataQualityIssueRepository;
import com.loanops.publication.repository.PublicationRunRepository;
import com.loanops.publication.repository.ServingLoanProductRepository;
import com.loanops.publication.repository.ServingLoanRateObservationRepository;
import com.loanops.publication.repository.ServingProductTermRepository;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.SourceRun;
import com.loanops.source.domain.SourceRunStatus;
import com.loanops.source.repository.SourceRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class PublicationTransactionService {

    private final SourceRunRepository sourceRunRepository;
    private final LoanProductRepository loanProductRepository;
    private final ProductTermRepository productTermRepository;
    private final LoanRateObservationRepository observationRepository;
    private final DataQualityIssueRepository issueRepository;
    private final PublicationRunRepository publicationRunRepository;
    private final ServingLoanProductRepository servingProductRepository;
    private final ServingProductTermRepository servingTermRepository;
    private final ServingLoanRateObservationRepository servingObservationRepository;
    private final DataChangeRepository dataChangeRepository;

    public PublicationTransactionService(
            SourceRunRepository sourceRunRepository,
            LoanProductRepository loanProductRepository,
            ProductTermRepository productTermRepository,
            LoanRateObservationRepository observationRepository,
            DataQualityIssueRepository issueRepository,
            PublicationRunRepository publicationRunRepository,
            ServingLoanProductRepository servingProductRepository,
            ServingProductTermRepository servingTermRepository,
            ServingLoanRateObservationRepository servingObservationRepository,
            DataChangeRepository dataChangeRepository
    ) {
        this.sourceRunRepository = sourceRunRepository;
        this.loanProductRepository = loanProductRepository;
        this.productTermRepository = productTermRepository;
        this.observationRepository = observationRepository;
        this.issueRepository = issueRepository;
        this.publicationRunRepository = publicationRunRepository;
        this.servingProductRepository = servingProductRepository;
        this.servingTermRepository = servingTermRepository;
        this.servingObservationRepository = servingObservationRepository;
        this.dataChangeRepository = dataChangeRepository;
    }

    @Transactional
    public PublicationRun rejectQuality(long sourceRunId) {
        PublicationRun existing = publicationRunRepository.findBySourceRunId(sourceRunId).orElse(null);
        if (existing != null) {
            return existing;
        }
        SourceRun sourceRun = requireSuccessfulRun(sourceRunId);
        if (issueRepository.countBySourceRunId(sourceRunId) == 0) {
            throw new PublicationException("Quality rejection requires at least one persisted issue");
        }
        return publicationRunRepository.save(new PublicationRun(
                sourceRun,
                PublicationStatus.REJECTED_QUALITY,
                Instant.now(),
                QualityPolicy.CURRENT_VERSION
        ));
    }

    @Transactional
    public PublicationRun promote(long sourceRunId) {
        PublicationRun existing = publicationRunRepository.findBySourceRunId(sourceRunId).orElse(null);
        if (existing != null) {
            return existing;
        }
        SourceRun sourceRun = requireSuccessfulRun(sourceRunId);
        if (issueRepository.countBySourceRunId(sourceRunId) > 0) {
            throw new PublicationException("A source run with quality issues cannot be promoted");
        }

        Instant now = Instant.now();
        List<ChangeDraft> changes = switch (sourceRun.getDataSource().getCode()) {
            case FSS_FINLIFE -> promoteFss(sourceRun, now);
            case SEOUL_POLICY_LOAN_RATE -> promoteSeoul(sourceRun, now);
        };
        PublicationStatus status = changes.isEmpty()
                ? PublicationStatus.NO_CHANGE
                : PublicationStatus.PROMOTED;
        PublicationRun publicationRun = publicationRunRepository.save(
                new PublicationRun(sourceRun, status, now, QualityPolicy.CURRENT_VERSION)
        );
        for (ChangeDraft change : changes) {
            dataChangeRepository.save(new DataChange(
                    publicationRun,
                    sourceRun,
                    change.recordType(),
                    change.stableIdentity(),
                    change.changeType(),
                    change.previousRecordId(),
                    change.currentRecordId(),
                    change.changedFields(),
                    now
            ));
        }
        return publicationRun;
    }

    private List<ChangeDraft> promoteFss(SourceRun sourceRun, Instant now) {
        List<LoanProduct> products = loanProductRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(sourceRun.getId());
        List<ProductTerm> terms = productTermRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(sourceRun.getId());
        if (products.isEmpty() && terms.isEmpty()) {
            throw new PublicationException("Source run has no normalized records");
        }

        List<ChangeDraft> changes = new ArrayList<>();
        for (LoanProduct product : products) {
            ServingLoanProduct serving = servingProductRepository
                    .findBySourceInstitutionCodeAndSourceProductId(
                            product.getSourceInstitutionCode(),
                            product.getSourceProductId()
                    )
                    .orElse(null);
            String identity = stableIdentity(product.getSourceInstitutionCode(), product.getSourceProductId());
            if (serving == null) {
                servingProductRepository.save(new ServingLoanProduct(product, sourceRun, now));
                changes.add(ChangeDraft.added(QualityRecordType.LOAN_PRODUCT, identity, product.getId()));
                continue;
            }
            LoanProduct previous = serving.getCurrentLoanProduct();
            List<String> changedFields = changedProductFields(previous, product);
            if (!changedFields.isEmpty()) {
                serving.pointTo(product, sourceRun, now);
                changes.add(ChangeDraft.updated(
                        QualityRecordType.LOAN_PRODUCT,
                        identity,
                        previous.getId(),
                        product.getId(),
                        changedFields
                ));
            }
        }

        for (ProductTerm term : terms) {
            LoanProduct product = term.getLoanProduct();
            ServingProductTerm serving = servingTermRepository
                    .findBySourceInstitutionCodeAndSourceProductIdAndMortgageTypeCodeAndRepaymentTypeCodeAndLendingRateTypeCode(
                            product.getSourceInstitutionCode(),
                            product.getSourceProductId(),
                            term.getMortgageTypeCode(),
                            term.getRepaymentTypeCode(),
                            term.getLendingRateTypeCode()
                    )
                    .orElse(null);
            String identity = stableIdentity(
                    product.getSourceInstitutionCode(),
                    product.getSourceProductId(),
                    term.getMortgageTypeCode(),
                    term.getRepaymentTypeCode(),
                    term.getLendingRateTypeCode()
            );
            if (serving == null) {
                servingTermRepository.save(new ServingProductTerm(term, sourceRun, now));
                changes.add(ChangeDraft.added(QualityRecordType.PRODUCT_TERM, identity, term.getId()));
                continue;
            }
            ProductTerm previous = serving.getCurrentProductTerm();
            List<String> changedFields = changedTermFields(previous, term);
            if (!changedFields.isEmpty()) {
                serving.pointTo(term, sourceRun, now);
                changes.add(ChangeDraft.updated(
                        QualityRecordType.PRODUCT_TERM,
                        identity,
                        previous.getId(),
                        term.getId(),
                        changedFields
                ));
            }
        }
        return changes;
    }

    private List<ChangeDraft> promoteSeoul(SourceRun sourceRun, Instant now) {
        List<LoanRateObservation> observations = observationRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(sourceRun.getId());
        if (observations.isEmpty()) {
            throw new PublicationException("Source run has no normalized records");
        }

        List<ChangeDraft> changes = new ArrayList<>();
        for (LoanRateObservation observation : observations) {
            Long aliasId = observation.getInstitutionAlias().getId();
            ServingLoanRateObservation serving = servingObservationRepository
                    .findByInstitutionAliasId(aliasId)
                    .orElse(null);
            String identity = stableIdentity(String.valueOf(aliasId));
            if (serving == null) {
                servingObservationRepository.save(
                        new ServingLoanRateObservation(observation, sourceRun, now)
                );
                changes.add(ChangeDraft.added(
                        QualityRecordType.LOAN_RATE_OBSERVATION,
                        identity,
                        observation.getId()
                ));
                continue;
            }
            LoanRateObservation previous = serving.getCurrentLoanRateObservation();
            List<String> changedFields = changedObservationFields(previous, observation);
            if (!changedFields.isEmpty()) {
                serving.pointTo(observation, sourceRun, now);
                changes.add(ChangeDraft.updated(
                        QualityRecordType.LOAN_RATE_OBSERVATION,
                        identity,
                        previous.getId(),
                        observation.getId(),
                        changedFields
                ));
            }
        }
        return changes;
    }

    private SourceRun requireSuccessfulRun(long sourceRunId) {
        SourceRun sourceRun = sourceRunRepository.findById(sourceRunId)
                .orElseThrow(() -> new PublicationException("Source run not found: " + sourceRunId));
        if (sourceRun.getStatus() != SourceRunStatus.SUCCESS) {
            throw new PublicationException("Only a SUCCESS source run can be published");
        }
        return sourceRun;
    }

    private List<String> changedProductFields(LoanProduct previous, LoanProduct current) {
        List<String> fields = new ArrayList<>();
        addIfChanged(fields, "product_name", previous.getProductName(), current.getProductName());
        addIfChanged(fields, "disclosure_month", previous.getDisclosureMonth(), current.getDisclosureMonth());
        addIfChanged(fields, "disclosure_start_date", previous.getDisclosureStartDate(), current.getDisclosureStartDate());
        addIfChanged(fields, "disclosure_end_date", previous.getDisclosureEndDate(), current.getDisclosureEndDate());
        addIfChanged(fields, "source_submitted_at", previous.getSourceSubmittedAt(), current.getSourceSubmittedAt());
        return fields;
    }

    private List<String> changedTermFields(ProductTerm previous, ProductTerm current) {
        List<String> fields = new ArrayList<>();
        addIfChanged(fields, "disclosure_month", previous.getDisclosureMonth(), current.getDisclosureMonth());
        addIfChanged(fields, "mortgage_type_name", previous.getMortgageTypeName(), current.getMortgageTypeName());
        addIfChanged(fields, "repayment_type_name", previous.getRepaymentTypeName(), current.getRepaymentTypeName());
        addIfChanged(fields, "lending_rate_type_name", previous.getLendingRateTypeName(), current.getLendingRateTypeName());
        addIfDecimalChanged(fields, "minimum_rate", previous.getMinimumRate(), current.getMinimumRate());
        addIfDecimalChanged(fields, "maximum_rate", previous.getMaximumRate(), current.getMaximumRate());
        addIfDecimalChanged(fields, "average_rate", previous.getAverageRate(), current.getAverageRate());
        return fields;
    }

    private List<String> changedObservationFields(
            LoanRateObservation previous,
            LoanRateObservation current
    ) {
        List<String> fields = new ArrayList<>();
        addIfChanged(fields, "reference_start_date", previous.getReferenceStartDate(), current.getReferenceStartDate());
        addIfChanged(fields, "reference_end_date", previous.getReferenceEndDate(), current.getReferenceEndDate());
        if (previous.getLoanCount() != current.getLoanCount()) {
            fields.add("loan_count");
        }
        addIfDecimalChanged(fields, "minimum_loan_rate", previous.getMinimumLoanRate(), current.getMinimumLoanRate());
        addIfDecimalChanged(fields, "maximum_loan_rate", previous.getMaximumLoanRate(), current.getMaximumLoanRate());
        addIfDecimalChanged(fields, "average_loan_rate", previous.getAverageLoanRate(), current.getAverageLoanRate());
        addIfDecimalChanged(fields, "minimum_loan_ale_rate", previous.getMinimumLoanAleRate(), current.getMinimumLoanAleRate());
        addIfDecimalChanged(fields, "maximum_loan_ale_rate", previous.getMaximumLoanAleRate(), current.getMaximumLoanAleRate());
        addIfDecimalChanged(fields, "average_loan_ale_rate", previous.getAverageLoanAleRate(), current.getAverageLoanAleRate());
        return fields;
    }

    private void addIfChanged(List<String> fields, String fieldName, Object previous, Object current) {
        if (!Objects.equals(previous, current)) {
            fields.add(fieldName);
        }
    }

    private void addIfDecimalChanged(
            List<String> fields,
            String fieldName,
            BigDecimal previous,
            BigDecimal current
    ) {
        boolean changed = previous == null
                ? current != null
                : current == null || previous.compareTo(current) != 0;
        if (changed) {
            fields.add(fieldName);
        }
    }

    private String stableIdentity(String... parts) {
        List<String> encoded = new ArrayList<>(parts.length);
        for (String part : parts) {
            encoded.add(part.length() + ":" + part);
        }
        return String.join("|", encoded);
    }

    private record ChangeDraft(
            QualityRecordType recordType,
            String stableIdentity,
            DataChangeType changeType,
            Long previousRecordId,
            long currentRecordId,
            String changedFields
    ) {

        static ChangeDraft added(QualityRecordType recordType, String identity, long currentRecordId) {
            return new ChangeDraft(
                    recordType,
                    identity,
                    DataChangeType.ADDED,
                    null,
                    currentRecordId,
                    null
            );
        }

        static ChangeDraft updated(
                QualityRecordType recordType,
                String identity,
                long previousRecordId,
                long currentRecordId,
                List<String> fields
        ) {
            return new ChangeDraft(
                    recordType,
                    identity,
                    DataChangeType.UPDATED,
                    previousRecordId,
                    currentRecordId,
                    String.join(",", fields)
            );
        }
    }
}
