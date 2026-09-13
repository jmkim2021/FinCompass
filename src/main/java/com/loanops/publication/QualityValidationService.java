package com.loanops.publication;

import com.loanops.normalization.domain.LoanProduct;
import com.loanops.normalization.domain.LoanRateObservation;
import com.loanops.normalization.domain.ProductTerm;
import com.loanops.normalization.repository.LoanProductRepository;
import com.loanops.normalization.repository.LoanRateObservationRepository;
import com.loanops.normalization.repository.ProductTermRepository;
import com.loanops.publication.domain.DataQualityIssue;
import com.loanops.publication.domain.QualityRecordType;
import com.loanops.publication.domain.QualityRuleCode;
import com.loanops.publication.domain.QuarantineRecord;
import com.loanops.publication.repository.DataQualityIssueRepository;
import com.loanops.publication.repository.QuarantineRecordRepository;
import com.loanops.source.domain.RawSnapshot;
import com.loanops.source.domain.SourceRun;
import com.loanops.source.domain.SourceRunStatus;
import com.loanops.source.repository.SourceRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class QualityValidationService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final SourceRunRepository sourceRunRepository;
    private final LoanProductRepository loanProductRepository;
    private final ProductTermRepository productTermRepository;
    private final LoanRateObservationRepository observationRepository;
    private final DataQualityIssueRepository issueRepository;
    private final QuarantineRecordRepository quarantineRepository;

    public QualityValidationService(
            SourceRunRepository sourceRunRepository,
            LoanProductRepository loanProductRepository,
            ProductTermRepository productTermRepository,
            LoanRateObservationRepository observationRepository,
            DataQualityIssueRepository issueRepository,
            QuarantineRecordRepository quarantineRepository
    ) {
        this.sourceRunRepository = sourceRunRepository;
        this.loanProductRepository = loanProductRepository;
        this.productTermRepository = productTermRepository;
        this.observationRepository = observationRepository;
        this.issueRepository = issueRepository;
        this.quarantineRepository = quarantineRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QualityValidationResult validateAndPersist(long sourceRunId) {
        SourceRun sourceRun = requireSuccessfulRun(sourceRunId);
        List<LoanProduct> products = loanProductRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(sourceRunId);
        List<ProductTerm> terms = productTermRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(sourceRunId);
        List<LoanRateObservation> observations = observationRepository
                .findAllByRawSnapshotSourceRunIdOrderByIdAsc(sourceRunId);
        requireNormalizedRecords(sourceRun, products, terms, observations);

        products.forEach(product -> persistRecordIssues(
                sourceRun,
                product.getRawSnapshot(),
                QualityRecordType.LOAN_PRODUCT,
                product.getId(),
                validateProduct(product)
        ));
        terms.forEach(term -> persistRecordIssues(
                sourceRun,
                term.getRawSnapshot(),
                QualityRecordType.PRODUCT_TERM,
                term.getId(),
                validateTerm(term)
        ));
        observations.forEach(observation -> persistRecordIssues(
                sourceRun,
                observation.getRawSnapshot(),
                QualityRecordType.LOAN_RATE_OBSERVATION,
                observation.getId(),
                validateObservation(observation)
        ));

        long issueCount = issueRepository.countBySourceRunId(sourceRunId);
        long quarantineCount = quarantineRepository.countBySourceRunId(sourceRunId);
        return new QualityValidationResult(sourceRunId, issueCount == 0, issueCount, quarantineCount);
    }

    private SourceRun requireSuccessfulRun(long sourceRunId) {
        SourceRun sourceRun = sourceRunRepository.findById(sourceRunId)
                .orElseThrow(() -> new PublicationException("Source run not found: " + sourceRunId));
        if (sourceRun.getStatus() != SourceRunStatus.SUCCESS) {
            throw new PublicationException("Only a SUCCESS source run can be published");
        }
        return sourceRun;
    }

    private void requireNormalizedRecords(
            SourceRun sourceRun,
            List<LoanProduct> products,
            List<ProductTerm> terms,
            List<LoanRateObservation> observations
    ) {
        boolean normalized = switch (sourceRun.getDataSource().getCode()) {
            case FSS_FINLIFE -> !products.isEmpty() || !terms.isEmpty();
            case SEOUL_POLICY_LOAN_RATE -> !observations.isEmpty();
        };
        if (!normalized) {
            throw new PublicationException("Source run has no normalized records");
        }
    }

    private List<IssueDraft> validateProduct(LoanProduct product) {
        List<IssueDraft> issues = new ArrayList<>();
        required(issues, "source_institution_code", product.getSourceInstitutionCode());
        required(issues, "source_product_id", product.getSourceProductId());
        required(issues, "product_name", product.getProductName());
        if (product.getDisclosureStartDate() != null
                && product.getDisclosureEndDate() != null
                && product.getDisclosureStartDate().isAfter(product.getDisclosureEndDate())) {
            issues.add(new IssueDraft(
                    QualityRuleCode.INVALID_DATE_RANGE,
                    "disclosure_date_range",
                    product.getDisclosureStartDate() + ".." + product.getDisclosureEndDate(),
                    "Disclosure start date must not be after disclosure end date"
            ));
        }
        return issues;
    }

    private List<IssueDraft> validateTerm(ProductTerm term) {
        List<IssueDraft> issues = new ArrayList<>();
        if (term.getLoanProduct() == null) {
            required(issues, "loan_product", null);
        }
        required(issues, "mortgage_type_code", term.getMortgageTypeCode());
        required(issues, "repayment_type_code", term.getRepaymentTypeCode());
        required(issues, "lending_rate_type_code", term.getLendingRateTypeCode());
        nonNegative(issues, "minimum_rate", term.getMinimumRate());
        nonNegative(issues, "maximum_rate", term.getMaximumRate());
        nonNegative(issues, "average_rate", term.getAverageRate());
        orderedRates(
                issues,
                "rate",
                term.getMinimumRate(),
                term.getAverageRate(),
                term.getMaximumRate()
        );
        return issues;
    }

    private List<IssueDraft> validateObservation(LoanRateObservation observation) {
        List<IssueDraft> issues = new ArrayList<>();
        if (observation.getInstitutionAlias() == null) {
            required(issues, "institution_alias", null);
        }
        if (observation.getReferenceStartDate() == null) {
            required(issues, "reference_start_date", null);
        }
        if (observation.getReferenceEndDate() == null) {
            required(issues, "reference_end_date", null);
        }
        if (observation.getReferenceStartDate() != null
                && observation.getReferenceEndDate() != null
                && observation.getReferenceStartDate().isAfter(observation.getReferenceEndDate())) {
            issues.add(new IssueDraft(
                    QualityRuleCode.INVALID_DATE_RANGE,
                    "reference_date_range",
                    observation.getReferenceStartDate() + ".." + observation.getReferenceEndDate(),
                    "Reference start date must not be after reference end date"
            ));
        }
        if (observation.getLoanCount() < 0) {
            issues.add(new IssueDraft(
                    QualityRuleCode.NEGATIVE_COUNT,
                    "loan_count",
                    String.valueOf(observation.getLoanCount()),
                    "Loan count must not be negative"
            ));
        }
        nonNegative(issues, "minimum_loan_rate", observation.getMinimumLoanRate());
        nonNegative(issues, "average_loan_rate", observation.getAverageLoanRate());
        nonNegative(issues, "maximum_loan_rate", observation.getMaximumLoanRate());
        nonNegative(issues, "minimum_loan_ale_rate", observation.getMinimumLoanAleRate());
        nonNegative(issues, "average_loan_ale_rate", observation.getAverageLoanAleRate());
        nonNegative(issues, "maximum_loan_ale_rate", observation.getMaximumLoanAleRate());
        orderedRates(
                issues,
                "loan_rate",
                observation.getMinimumLoanRate(),
                observation.getAverageLoanRate(),
                observation.getMaximumLoanRate()
        );
        orderedRates(
                issues,
                "loan_ale_rate",
                observation.getMinimumLoanAleRate(),
                observation.getAverageLoanAleRate(),
                observation.getMaximumLoanAleRate()
        );
        return issues;
    }

    private void required(List<IssueDraft> issues, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            issues.add(new IssueDraft(
                    QualityRuleCode.REQUIRED_FIELD_MISSING,
                    fieldName,
                    value,
                    "Required field is missing"
            ));
        }
    }

    private void nonNegative(List<IssueDraft> issues, String fieldName, BigDecimal value) {
        if (value != null && value.compareTo(ZERO) < 0) {
            issues.add(new IssueDraft(
                    QualityRuleCode.NEGATIVE_RATE,
                    fieldName,
                    value.toPlainString(),
                    "Rate must not be negative"
            ));
        }
    }

    private void orderedRates(
            List<IssueDraft> issues,
            String fieldName,
            BigDecimal minimum,
            BigDecimal average,
            BigDecimal maximum
    ) {
        if (minimum == null || maximum == null) {
            return;
        }
        boolean invalid = minimum.compareTo(maximum) > 0
                || (average != null
                && (average.compareTo(minimum) < 0 || average.compareTo(maximum) > 0));
        if (invalid) {
            String observed = "min=" + minimum.toPlainString()
                    + ",avg=" + (average == null ? "null" : average.toPlainString())
                    + ",max=" + maximum.toPlainString();
            issues.add(new IssueDraft(
                    QualityRuleCode.INVALID_RATE_ORDER,
                    fieldName,
                    observed,
                    "Rates must satisfy minimum <= average <= maximum when average is present"
            ));
        }
    }

    private void persistRecordIssues(
            SourceRun sourceRun,
            RawSnapshot snapshot,
            QualityRecordType recordType,
            long recordId,
            List<IssueDraft> issues
    ) {
        Instant now = Instant.now();
        for (IssueDraft issue : issues) {
            boolean exists = issueRepository
                    .existsBySourceRunIdAndRecordTypeAndRecordIdAndRuleCodeAndFieldName(
                            sourceRun.getId(),
                            recordType,
                            recordId,
                            issue.ruleCode(),
                            issue.fieldName()
                    );
            if (!exists) {
                issueRepository.save(new DataQualityIssue(
                        sourceRun,
                        snapshot,
                        recordType,
                        recordId,
                        issue.ruleCode(),
                        issue.fieldName(),
                        issue.observedValue(),
                        issue.message(),
                        now
                ));
            }
        }
        if (!issues.isEmpty()
                && !quarantineRepository.existsBySourceRunIdAndRecordTypeAndRecordId(
                sourceRun.getId(), recordType, recordId)) {
            quarantineRepository.save(new QuarantineRecord(
                    sourceRun,
                    snapshot,
                    recordType,
                    recordId,
                    now
            ));
        }
    }

    private record IssueDraft(
            QualityRuleCode ruleCode,
            String fieldName,
            String observedValue,
            String message
    ) {
    }
}
