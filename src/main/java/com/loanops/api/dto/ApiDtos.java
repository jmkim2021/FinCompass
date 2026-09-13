package com.loanops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
            int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
            return new PageResponse<>(List.copyOf(content), page, size, totalElements, totalPages);
        }
    }

    public record OverviewResponse(
            long sourceCount,
            List<SourceOverviewResponse> sources,
            long servingLoanProductCount,
            long servingProductTermCount,
            long servingSeoulObservationCount,
            long currentQuarantineCount,
            long totalQualityIssueCount,
            long recentDataChangeCount
    ) {
    }

    public record SourceOverviewResponse(
            String sourceCode,
            Long latestSourceRunId,
            String ingestionStatus,
            Long latestPublicationSourceRunId,
            String publicationStatus,
            String qualityPolicyVersion,
            Instant publishedAt
    ) {
    }

    public record SourceRunSummaryResponse(
            long id,
            String sourceCode,
            String status,
            Integer httpStatus,
            String errorType,
            String errorMessage,
            Instant startedAt,
            Instant endedAt,
            long rawSnapshotCount
    ) {
    }

    public record SourceRunDetailResponse(
            SourceRunSummaryResponse sourceRun,
            DataSourceResponse dataSource,
            List<RawSnapshotMetadataResponse> rawSnapshots,
            PublicationSummaryResponse publication,
            long qualityIssueCount,
            long quarantineCount,
            NormalizedRecordCountsResponse normalizedRecordCounts
    ) {
    }

    public record DataSourceResponse(
            long id,
            String code,
            String name,
            String responseFormat,
            boolean active
    ) {
    }

    public record RawSnapshotMetadataResponse(
            long id,
            String contentType,
            String payloadHash,
            Instant fetchedAt,
            long payloadSize
    ) {
    }

    public record NormalizedRecordCountsResponse(
            long loanProducts,
            long productTerms,
            long loanRateObservations
    ) {
    }

    public record PublicationSummaryResponse(
            long id,
            long sourceRunId,
            String sourceCode,
            String status,
            String qualityPolicyVersion,
            Instant createdAt,
            Instant completedAt,
            long issueCount,
            long quarantineCount,
            long changeCount
    ) {
    }

    public record PublicationDetailResponse(
            PublicationSummaryResponse publication,
            List<QualityIssueResponse> qualityIssues,
            List<QuarantineSummaryResponse> quarantineRecords,
            List<DataChangeResponse> dataChanges
    ) {
    }

    public record QualityIssueResponse(
            long id,
            long sourceRunId,
            long rawSnapshotId,
            String recordType,
            long recordId,
            String ruleCode,
            String fieldName,
            String observedValue,
            String message,
            Instant createdAt
    ) {
    }

    public record QuarantineSummaryResponse(
            long id,
            long sourceRunId,
            long rawSnapshotId,
            String recordType,
            long recordId,
            Instant createdAt
    ) {
    }

    public record DataChangeResponse(
            long id,
            long sourceRunId,
            String recordType,
            String stableRecordIdentity,
            String changeType,
            Long previousRecordId,
            long currentRecordId,
            String changedFields,
            Instant createdAt
    ) {
    }

    public record ServingLoanProductResponse(
            long id,
            String sourceInstitutionCode,
            String sourceInstitutionName,
            String canonicalInstitutionName,
            String sourceProductId,
            String productName,
            String disclosureMonth,
            LocalDate disclosureStartDate,
            LocalDate disclosureEndDate,
            LocalDateTime sourceSubmittedAt,
            long currentNormalizedRecordId,
            long sourceRunId,
            long rawSnapshotId,
            long termCount,
            Instant updatedAt
    ) {
    }

    public record ServingLoanProductDetailResponse(
            ServingLoanProductResponse product,
            List<ServingProductTermResponse> terms
    ) {
    }

    public record ServingProductTermResponse(
            long id,
            long currentNormalizedRecordId,
            String disclosureMonth,
            String mortgageTypeCode,
            String mortgageTypeName,
            String repaymentTypeCode,
            String repaymentTypeName,
            String lendingRateTypeCode,
            String lendingRateTypeName,
            BigDecimal minimumRate,
            BigDecimal averageRate,
            BigDecimal maximumRate,
            long sourceRunId,
            long rawSnapshotId,
            Instant updatedAt
    ) {
    }

    public record ServingLoanRateObservationResponse(
            long id,
            String sourceInstitutionCode,
            String sourceInstitutionName,
            String canonicalInstitutionName,
            String mappingStatus,
            LocalDate referenceStartDate,
            LocalDate referenceEndDate,
            long loanCount,
            BigDecimal minimumLoanRate,
            BigDecimal averageLoanRate,
            BigDecimal maximumLoanRate,
            BigDecimal minimumLoanAleRate,
            BigDecimal averageLoanAleRate,
            BigDecimal maximumLoanAleRate,
            long currentNormalizedRecordId,
            long sourceRunId,
            long rawSnapshotId,
            Instant updatedAt
    ) {
    }

    public record ApiErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path
    ) {
    }
}
