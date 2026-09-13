package com.loanops.normalization;

import com.loanops.normalization.domain.InstitutionAlias;
import com.loanops.normalization.domain.LoanProduct;
import com.loanops.normalization.domain.LoanRateObservation;
import com.loanops.normalization.domain.ProductTerm;
import com.loanops.normalization.parser.FssFinlifeParser;
import com.loanops.normalization.parser.SeoulLoanRateParser;
import com.loanops.normalization.repository.LoanProductRepository;
import com.loanops.normalization.repository.LoanRateObservationRepository;
import com.loanops.normalization.repository.ProductTermRepository;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.RawSnapshot;
import com.loanops.source.domain.SourceRun;
import com.loanops.source.domain.SourceRunStatus;
import com.loanops.source.repository.RawSnapshotRepository;
import com.loanops.source.repository.SourceRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SourceNormalizationService {

    private final SourceRunRepository sourceRunRepository;
    private final RawSnapshotRepository rawSnapshotRepository;
    private final FssFinlifeParser fssParser;
    private final SeoulLoanRateParser seoulParser;
    private final InstitutionResolver institutionResolver;
    private final LoanProductRepository loanProductRepository;
    private final ProductTermRepository productTermRepository;
    private final LoanRateObservationRepository loanRateObservationRepository;

    public SourceNormalizationService(
            SourceRunRepository sourceRunRepository,
            RawSnapshotRepository rawSnapshotRepository,
            FssFinlifeParser fssParser,
            SeoulLoanRateParser seoulParser,
            InstitutionResolver institutionResolver,
            LoanProductRepository loanProductRepository,
            ProductTermRepository productTermRepository,
            LoanRateObservationRepository loanRateObservationRepository
    ) {
        this.sourceRunRepository = sourceRunRepository;
        this.rawSnapshotRepository = rawSnapshotRepository;
        this.fssParser = fssParser;
        this.seoulParser = seoulParser;
        this.institutionResolver = institutionResolver;
        this.loanProductRepository = loanProductRepository;
        this.productTermRepository = productTermRepository;
        this.loanRateObservationRepository = loanRateObservationRepository;
    }

    @Transactional
    public NormalizationResult normalize(long sourceRunId) {
        SourceRun sourceRun = sourceRunRepository.findById(sourceRunId)
                .orElseThrow(() -> new NormalizationException("Source run not found: " + sourceRunId));
        if (sourceRun.getStatus() != SourceRunStatus.SUCCESS) {
            throw new NormalizationException("Only a SUCCESS source run can be normalized");
        }

        List<RawSnapshot> snapshots = rawSnapshotRepository.findAllBySourceRunIdOrderByIdAsc(sourceRunId);
        if (snapshots.isEmpty()) {
            throw new NormalizationException("Successful source run has no raw snapshots");
        }

        return switch (sourceRun.getDataSource().getCode()) {
            case FSS_FINLIFE -> normalizeFss(sourceRun, snapshots);
            case SEOUL_POLICY_LOAN_RATE -> normalizeSeoul(sourceRun, snapshots);
        };
    }

    private NormalizationResult normalizeFss(SourceRun sourceRun, List<RawSnapshot> snapshots) {
        if (snapshots.size() != 1) {
            throw new NormalizationException("FSS normalization requires exactly one raw snapshot");
        }

        RawSnapshot snapshot = snapshots.getFirst();
        FssFinlifeParser.ParsedResponse parsed = fssParser.parse(snapshot.getRawPayload());
        Map<ProductKey, FssFinlifeParser.BaseProduct> baseProducts = new HashMap<>();
        for (FssFinlifeParser.BaseProduct product : parsed.products()) {
            ProductKey key = new ProductKey(product.institutionCode(), product.productId());
            if (baseProducts.put(key, product) != null) {
                throw new NormalizationException("FSS snapshot contains a duplicate product identity");
            }
        }
        for (FssFinlifeParser.ProductOption option : parsed.options()) {
            if (!baseProducts.containsKey(new ProductKey(option.institutionCode(), option.productId()))) {
                throw new NormalizationException("FSS option does not match a base product");
            }
        }

        Map<ProductKey, LoanProduct> products = new HashMap<>();
        int createdProducts = 0;
        for (FssFinlifeParser.BaseProduct sourceProduct : parsed.products()) {
            InstitutionAlias alias = institutionResolver.resolveFss(
                    sourceRun.getDataSource(),
                    sourceProduct.institutionCode(),
                    sourceProduct.institutionName()
            );
            ProductKey key = new ProductKey(sourceProduct.institutionCode(), sourceProduct.productId());
            LoanProduct product = loanProductRepository
                    .findByRawSnapshotIdAndSourceInstitutionCodeAndSourceProductId(
                            snapshot.getId(),
                            sourceProduct.institutionCode(),
                            sourceProduct.productId()
                    )
                    .orElse(null);
            if (product == null) {
                product = loanProductRepository.save(new LoanProduct(
                        alias,
                        snapshot,
                        sourceProduct.institutionCode(),
                        sourceProduct.productId(),
                        sourceProduct.productName(),
                        sourceProduct.disclosureMonth(),
                        sourceProduct.disclosureStartDate(),
                        sourceProduct.disclosureEndDate(),
                        sourceProduct.sourceSubmittedAt(),
                        Instant.now()
                ));
                createdProducts++;
            }
            products.put(key, product);
        }

        int createdTerms = 0;
        for (FssFinlifeParser.ProductOption sourceOption : parsed.options()) {
            LoanProduct product = products.get(new ProductKey(
                    sourceOption.institutionCode(),
                    sourceOption.productId()
            ));
            boolean exists = productTermRepository
                    .existsByRawSnapshotIdAndLoanProductIdAndMortgageTypeCodeAndRepaymentTypeCodeAndLendingRateTypeCode(
                            snapshot.getId(),
                            product.getId(),
                            sourceOption.mortgageTypeCode(),
                            sourceOption.repaymentTypeCode(),
                            sourceOption.lendingRateTypeCode()
                    );
            if (!exists) {
                productTermRepository.save(new ProductTerm(
                        product,
                        snapshot,
                        sourceOption.disclosureMonth(),
                        sourceOption.mortgageTypeCode(),
                        sourceOption.mortgageTypeName(),
                        sourceOption.repaymentTypeCode(),
                        sourceOption.repaymentTypeName(),
                        sourceOption.lendingRateTypeCode(),
                        sourceOption.lendingRateTypeName(),
                        sourceOption.minimumRate(),
                        sourceOption.maximumRate(),
                        sourceOption.averageRate(),
                        Instant.now()
                ));
                createdTerms++;
            }
        }

        return new NormalizationResult(
                sourceRun.getId(),
                DataSourceCode.FSS_FINLIFE,
                1,
                createdProducts,
                createdTerms,
                0
        );
    }

    private NormalizationResult normalizeSeoul(SourceRun sourceRun, List<RawSnapshot> snapshots) {
        List<ParsedSeoulSnapshot> parsedSnapshots = snapshots.stream()
                .map(snapshot -> new ParsedSeoulSnapshot(
                        snapshot,
                        seoulParser.parse(snapshot.getRawPayload())
                ))
                .toList();
        int expectedTotal = parsedSnapshots.getFirst().page().totalCount();
        boolean inconsistentTotal = parsedSnapshots.stream()
                .anyMatch(parsed -> parsed.page().totalCount() != expectedTotal);
        int actualTotal = parsedSnapshots.stream().mapToInt(parsed -> parsed.page().rows().size()).sum();
        if (inconsistentTotal || actualTotal != expectedTotal) {
            throw new NormalizationException("Seoul source run does not contain a complete dataset");
        }

        int createdObservations = 0;
        for (ParsedSeoulSnapshot parsed : parsedSnapshots) {
            for (SeoulLoanRateParser.RateRow row : parsed.page().rows()) {
                InstitutionAlias alias = institutionResolver.resolveSeoul(
                        sourceRun.getDataSource(),
                        row.institutionCode(),
                        row.institutionName()
                );
                boolean exists = loanRateObservationRepository
                        .existsByRawSnapshotIdAndSourceInstitutionCodeAndReferenceStartDateAndReferenceEndDate(
                                parsed.snapshot().getId(),
                                row.institutionCode(),
                                row.referenceStartDate(),
                                row.referenceEndDate()
                        );
                if (!exists) {
                    loanRateObservationRepository.save(new LoanRateObservation(
                            alias,
                            parsed.snapshot(),
                            row.institutionCode(),
                            row.referenceStartDate(),
                            row.referenceEndDate(),
                            row.loanCount(),
                            row.minimumLoanRate(),
                            row.maximumLoanRate(),
                            row.averageLoanRate(),
                            row.minimumLoanAleRate(),
                            row.maximumLoanAleRate(),
                            row.averageLoanAleRate(),
                            Instant.now()
                    ));
                    createdObservations++;
                }
            }
        }

        return new NormalizationResult(
                sourceRun.getId(),
                DataSourceCode.SEOUL_POLICY_LOAN_RATE,
                snapshots.size(),
                0,
                0,
                createdObservations
        );
    }

    private record ProductKey(String institutionCode, String productId) {
    }

    private record ParsedSeoulSnapshot(RawSnapshot snapshot, SeoulLoanRateParser.ParsedPage page) {
    }
}
