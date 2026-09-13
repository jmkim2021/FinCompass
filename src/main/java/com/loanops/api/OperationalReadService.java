package com.loanops.api;

import com.loanops.api.dto.ApiDtos.DataChangeResponse;
import com.loanops.api.dto.ApiDtos.DataSourceResponse;
import com.loanops.api.dto.ApiDtos.NormalizedRecordCountsResponse;
import com.loanops.api.dto.ApiDtos.OverviewResponse;
import com.loanops.api.dto.ApiDtos.PageResponse;
import com.loanops.api.dto.ApiDtos.PublicationDetailResponse;
import com.loanops.api.dto.ApiDtos.PublicationSummaryResponse;
import com.loanops.api.dto.ApiDtos.QualityIssueResponse;
import com.loanops.api.dto.ApiDtos.QuarantineSummaryResponse;
import com.loanops.api.dto.ApiDtos.RawSnapshotMetadataResponse;
import com.loanops.api.dto.ApiDtos.ServingLoanProductDetailResponse;
import com.loanops.api.dto.ApiDtos.ServingLoanProductResponse;
import com.loanops.api.dto.ApiDtos.ServingLoanRateObservationResponse;
import com.loanops.api.dto.ApiDtos.ServingProductTermResponse;
import com.loanops.api.dto.ApiDtos.SourceOverviewResponse;
import com.loanops.api.dto.ApiDtos.SourceRunDetailResponse;
import com.loanops.api.dto.ApiDtos.SourceRunSummaryResponse;
import com.loanops.publication.domain.DataChangeType;
import com.loanops.publication.domain.PublicationStatus;
import com.loanops.publication.domain.QualityRecordType;
import com.loanops.publication.domain.QualityRuleCode;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.SourceRunStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class OperationalReadService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbc;

    public OperationalReadService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OverviewResponse overview() {
        List<SourceOverviewResponse> sources = jdbc.query(
                """
                        SELECT ds.code,
                               latest_run.id AS latest_source_run_id,
                               latest_run.status AS ingestion_status,
                               latest_publication.source_run_id AS latest_publication_source_run_id,
                               latest_publication.status AS publication_status,
                               latest_publication.quality_policy_version,
                               latest_publication.completed_at AS published_at
                        FROM loanops.data_sources ds
                        LEFT JOIN LATERAL (
                            SELECT sr.id, sr.status
                            FROM loanops.source_runs sr
                            WHERE sr.data_source_id = ds.id
                            ORDER BY sr.started_at DESC, sr.id DESC
                            LIMIT 1
                        ) latest_run ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT pr.source_run_id, pr.status, pr.quality_policy_version, pr.completed_at
                            FROM loanops.publication_runs pr
                            JOIN loanops.source_runs sr ON sr.id = pr.source_run_id
                            WHERE sr.data_source_id = ds.id
                            ORDER BY pr.completed_at DESC, pr.id DESC
                            LIMIT 1
                        ) latest_publication ON TRUE
                        WHERE ds.active = TRUE
                        ORDER BY ds.code
                        """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> new SourceOverviewResponse(
                        rs.getString("code"),
                        nullableLong(rs, "latest_source_run_id"),
                        rs.getString("ingestion_status"),
                        nullableLong(rs, "latest_publication_source_run_id"),
                        rs.getString("publication_status"),
                        rs.getString("quality_policy_version"),
                        nullableInstant(rs, "published_at")
                )
        );
        return new OverviewResponse(
                count("SELECT COUNT(*) FROM loanops.data_sources WHERE active = TRUE"),
                sources,
                count("SELECT COUNT(*) FROM loanops.serving_loan_products"),
                count("SELECT COUNT(*) FROM loanops.serving_product_terms"),
                count("SELECT COUNT(*) FROM loanops.serving_loan_rate_observations"),
                count("SELECT COUNT(*) FROM loanops.quarantine_records"),
                count("SELECT COUNT(*) FROM loanops.data_quality_issues"),
                count("SELECT COUNT(*) FROM loanops.data_changes WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'")
        );
    }

    public PageResponse<SourceRunSummaryResponse> sourceRuns(
            DataSourceCode source,
            SourceRunStatus status,
            int page,
            int size
    ) {
        validatePage(page, size);
        String where = " WHERE 1 = 1";
        MapSqlParameterSource parameters = pageParameters(page, size);
        if (source != null) {
            where += " AND ds.code = :source";
            parameters.addValue("source", source.name());
        }
        if (status != null) {
            where += " AND sr.status = :status";
            parameters.addValue("status", status.name());
        }
        long total = requiredLong(
                "SELECT COUNT(*) FROM loanops.source_runs sr JOIN loanops.data_sources ds ON ds.id = sr.data_source_id" + where,
                parameters
        );
        List<SourceRunSummaryResponse> content = jdbc.query(
                """
                        SELECT sr.id, ds.code AS source_code, sr.status, sr.http_status,
                               sr.error_type, sr.error_message, sr.started_at, sr.ended_at,
                               COUNT(rs.id) AS raw_snapshot_count
                        FROM loanops.source_runs sr
                        JOIN loanops.data_sources ds ON ds.id = sr.data_source_id
                        LEFT JOIN loanops.raw_snapshots rs ON rs.source_run_id = sr.id
                        """ + where + " " + """
                        GROUP BY sr.id, ds.code
                        ORDER BY sr.started_at DESC, sr.id DESC
                        LIMIT :size OFFSET :offset
                        """,
                parameters,
                sourceRunMapper()
        );
        return PageResponse.of(content, page, size, total);
    }

    public SourceRunDetailResponse sourceRun(long id) {
        List<SourceRunSummaryResponse> summaries = jdbc.query(
                """
                        SELECT sr.id, ds.code AS source_code, sr.status, sr.http_status,
                               sr.error_type, sr.error_message, sr.started_at, sr.ended_at,
                               COUNT(rs.id) AS raw_snapshot_count
                        FROM loanops.source_runs sr
                        JOIN loanops.data_sources ds ON ds.id = sr.data_source_id
                        LEFT JOIN loanops.raw_snapshots rs ON rs.source_run_id = sr.id
                        WHERE sr.id = :id
                        GROUP BY sr.id, ds.code
                        """,
                new MapSqlParameterSource("id", id),
                sourceRunMapper()
        );
        if (summaries.isEmpty()) {
            throw new ApiNotFoundException("SourceRun not found: " + id);
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id);
        DataSourceResponse dataSource = jdbc.queryForObject(
                """
                        SELECT ds.id, ds.code, ds.name, ds.response_format, ds.active
                        FROM loanops.data_sources ds
                        JOIN loanops.source_runs sr ON sr.data_source_id = ds.id
                        WHERE sr.id = :id
                        """,
                parameters,
                (rs, rowNum) -> new DataSourceResponse(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("response_format"),
                        rs.getBoolean("active")
                )
        );
        List<RawSnapshotMetadataResponse> snapshots = jdbc.query(
                """
                        SELECT id, content_type, payload_hash, fetched_at,
                               OCTET_LENGTH(raw_payload) AS payload_size
                        FROM loanops.raw_snapshots
                        WHERE source_run_id = :id
                        ORDER BY id
                        """,
                parameters,
                (rs, rowNum) -> new RawSnapshotMetadataResponse(
                        rs.getLong("id"),
                        rs.getString("content_type"),
                        rs.getString("payload_hash"),
                        instant(rs, "fetched_at"),
                        rs.getLong("payload_size")
                )
        );
        List<PublicationSummaryResponse> publications = publicationSummaries(
                "WHERE pr.source_run_id = :id",
                parameters,
                ""
        );
        NormalizedRecordCountsResponse normalizedCounts = jdbc.queryForObject(
                """
                        SELECT
                            (SELECT COUNT(*) FROM loanops.loan_products lp
                             JOIN loanops.raw_snapshots rs ON rs.id = lp.raw_snapshot_id
                             WHERE rs.source_run_id = :id) AS loan_products,
                            (SELECT COUNT(*) FROM loanops.product_terms pt
                             JOIN loanops.raw_snapshots rs ON rs.id = pt.raw_snapshot_id
                             WHERE rs.source_run_id = :id) AS product_terms,
                            (SELECT COUNT(*) FROM loanops.loan_rate_observations observation
                             JOIN loanops.raw_snapshots rs ON rs.id = observation.raw_snapshot_id
                             WHERE rs.source_run_id = :id) AS loan_rate_observations
                        """,
                parameters,
                (rs, rowNum) -> new NormalizedRecordCountsResponse(
                        rs.getLong("loan_products"),
                        rs.getLong("product_terms"),
                        rs.getLong("loan_rate_observations")
                )
        );
        return new SourceRunDetailResponse(
                summaries.getFirst(),
                dataSource,
                snapshots,
                publications.isEmpty() ? null : publications.getFirst(),
                countBySourceRun("loanops.data_quality_issues", id),
                countBySourceRun("loanops.quarantine_records", id),
                normalizedCounts
        );
    }

    public PageResponse<PublicationSummaryResponse> publications(int page, int size) {
        validatePage(page, size);
        MapSqlParameterSource parameters = pageParameters(page, size);
        long total = count("SELECT COUNT(*) FROM loanops.publication_runs");
        List<PublicationSummaryResponse> content = publicationSummaries(
                "",
                parameters,
                "ORDER BY pr.completed_at DESC, pr.id DESC LIMIT :size OFFSET :offset"
        );
        return PageResponse.of(content, page, size, total);
    }

    public PublicationDetailResponse publication(long id) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id);
        List<PublicationSummaryResponse> publications = publicationSummaries(
                "WHERE pr.id = :id",
                parameters,
                ""
        );
        if (publications.isEmpty()) {
            throw new ApiNotFoundException("Publication not found: " + id);
        }
        PublicationSummaryResponse publication = publications.getFirst();
        MapSqlParameterSource detailParameters = new MapSqlParameterSource()
                .addValue("sourceRunId", publication.sourceRunId())
                .addValue("publicationId", publication.id());
        return new PublicationDetailResponse(
                publication,
                jdbc.query(qualityIssueSelect() + " WHERE issue.source_run_id = :sourceRunId ORDER BY issue.id", detailParameters, qualityIssueMapper()),
                jdbc.query(
                        """
                                SELECT quarantine.id, quarantine.source_run_id, quarantine.raw_snapshot_id,
                                       quarantine.record_type, quarantine.record_id, quarantine.created_at
                                FROM loanops.quarantine_records quarantine
                                WHERE quarantine.source_run_id = :sourceRunId
                                ORDER BY quarantine.id
                                """,
                        detailParameters,
                        quarantineMapper()
                ),
                jdbc.query(dataChangeSelect() + " WHERE change.publication_run_id = :publicationId ORDER BY change.id", detailParameters, dataChangeMapper())
        );
    }

    public PageResponse<QualityIssueResponse> qualityIssues(
            Long sourceRunId,
            QualityRecordType recordType,
            QualityRuleCode ruleCode,
            int page,
            int size
    ) {
        validatePage(page, size);
        String where = " WHERE 1 = 1";
        MapSqlParameterSource parameters = pageParameters(page, size);
        if (sourceRunId != null) {
            where += " AND issue.source_run_id = :sourceRunId";
            parameters.addValue("sourceRunId", sourceRunId);
        }
        if (recordType != null) {
            where += " AND issue.record_type = :recordType";
            parameters.addValue("recordType", recordType.name());
        }
        if (ruleCode != null) {
            where += " AND issue.rule_code = :ruleCode";
            parameters.addValue("ruleCode", ruleCode.name());
        }
        long total = requiredLong("SELECT COUNT(*) FROM loanops.data_quality_issues issue" + where, parameters);
        List<QualityIssueResponse> content = jdbc.query(
                qualityIssueSelect() + where + " ORDER BY issue.created_at DESC, issue.id DESC LIMIT :size OFFSET :offset",
                parameters,
                qualityIssueMapper()
        );
        return PageResponse.of(content, page, size, total);
    }

    public PageResponse<DataChangeResponse> dataChanges(
            Long sourceRunId,
            QualityRecordType recordType,
            DataChangeType changeType,
            int page,
            int size
    ) {
        validatePage(page, size);
        String where = " WHERE 1 = 1";
        MapSqlParameterSource parameters = pageParameters(page, size);
        if (sourceRunId != null) {
            where += " AND change.source_run_id = :sourceRunId";
            parameters.addValue("sourceRunId", sourceRunId);
        }
        if (recordType != null) {
            where += " AND change.record_type = :recordType";
            parameters.addValue("recordType", recordType.name());
        }
        if (changeType != null) {
            where += " AND change.change_type = :changeType";
            parameters.addValue("changeType", changeType.name());
        }
        long total = requiredLong("SELECT COUNT(*) FROM loanops.data_changes change" + where, parameters);
        List<DataChangeResponse> content = jdbc.query(
                dataChangeSelect() + where + " ORDER BY change.created_at DESC, change.id DESC LIMIT :size OFFSET :offset",
                parameters,
                dataChangeMapper()
        );
        return PageResponse.of(content, page, size, total);
    }

    public PageResponse<ServingLoanProductResponse> servingLoanProducts(
            String institution,
            String productName,
            int page,
            int size
    ) {
        validatePage(page, size);
        String where = " WHERE 1 = 1";
        MapSqlParameterSource parameters = pageParameters(page, size);
        if (StringUtils.hasText(institution)) {
            where += " AND (alias.source_institution_name ILIKE :institution OR institution.canonical_name ILIKE :institution)";
            parameters.addValue("institution", "%" + institution.trim() + "%");
        }
        if (StringUtils.hasText(productName)) {
            where += " AND product.product_name ILIKE :productName";
            parameters.addValue("productName", "%" + productName.trim() + "%");
        }
        String from = """
                 FROM loanops.serving_loan_products serving
                 JOIN loanops.loan_products product ON product.id = serving.current_loan_product_id
                 JOIN loanops.institution_aliases alias ON alias.id = product.institution_alias_id
                 JOIN loanops.financial_institutions institution ON institution.id = product.financial_institution_id
                 JOIN loanops.raw_snapshots snapshot ON snapshot.id = product.raw_snapshot_id
                """;
        long total = requiredLong("SELECT COUNT(*)" + from + where, parameters);
        List<ServingLoanProductResponse> content = jdbc.query(
                servingLoanProductSelect() + from + where
                        + " ORDER BY institution.canonical_name, product.product_name, serving.id LIMIT :size OFFSET :offset",
                parameters,
                servingLoanProductMapper()
        );
        return PageResponse.of(content, page, size, total);
    }

    public ServingLoanProductDetailResponse servingLoanProduct(long id) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id);
        String from = """
                 FROM loanops.serving_loan_products serving
                 JOIN loanops.loan_products product ON product.id = serving.current_loan_product_id
                 JOIN loanops.institution_aliases alias ON alias.id = product.institution_alias_id
                 JOIN loanops.financial_institutions institution ON institution.id = product.financial_institution_id
                 JOIN loanops.raw_snapshots snapshot ON snapshot.id = product.raw_snapshot_id
                """;
        List<ServingLoanProductResponse> products = jdbc.query(
                servingLoanProductSelect() + from + " WHERE serving.id = :id",
                parameters,
                servingLoanProductMapper()
        );
        if (products.isEmpty()) {
            throw new ApiNotFoundException("Serving loan product not found: " + id);
        }
        ServingLoanProductResponse product = products.getFirst();
        MapSqlParameterSource termParameters = new MapSqlParameterSource()
                .addValue("institutionCode", product.sourceInstitutionCode())
                .addValue("productId", product.sourceProductId());
        List<ServingProductTermResponse> terms = jdbc.query(
                """
                        SELECT serving.id, term.id AS current_normalized_record_id,
                               term.disclosure_month, term.mortgage_type_code, term.mortgage_type_name,
                               term.repayment_type_code, term.repayment_type_name,
                               term.lending_rate_type_code, term.lending_rate_type_name,
                               term.minimum_rate, term.average_rate, term.maximum_rate,
                               serving.promoted_source_run_id AS source_run_id,
                               term.raw_snapshot_id, serving.updated_at
                        FROM loanops.serving_product_terms serving
                        JOIN loanops.product_terms term ON term.id = serving.current_product_term_id
                        WHERE serving.source_institution_code = :institutionCode
                          AND serving.source_product_id = :productId
                        ORDER BY term.mortgage_type_name, term.repayment_type_name,
                                 term.lending_rate_type_name, serving.id
                        """,
                termParameters,
                (rs, rowNum) -> new ServingProductTermResponse(
                        rs.getLong("id"),
                        rs.getLong("current_normalized_record_id"),
                        rs.getString("disclosure_month"),
                        rs.getString("mortgage_type_code"),
                        rs.getString("mortgage_type_name"),
                        rs.getString("repayment_type_code"),
                        rs.getString("repayment_type_name"),
                        rs.getString("lending_rate_type_code"),
                        rs.getString("lending_rate_type_name"),
                        rs.getBigDecimal("minimum_rate"),
                        rs.getBigDecimal("average_rate"),
                        rs.getBigDecimal("maximum_rate"),
                        rs.getLong("source_run_id"),
                        rs.getLong("raw_snapshot_id"),
                        instant(rs, "updated_at")
                )
        );
        return new ServingLoanProductDetailResponse(product, terms);
    }

    public PageResponse<ServingLoanRateObservationResponse> servingLoanRateObservations(int page, int size) {
        validatePage(page, size);
        MapSqlParameterSource parameters = pageParameters(page, size);
        long total = count("SELECT COUNT(*) FROM loanops.serving_loan_rate_observations");
        List<ServingLoanRateObservationResponse> content = jdbc.query(
                """
                        SELECT serving.id, alias.source_institution_code, alias.source_institution_name,
                               institution.canonical_name AS canonical_institution_name, alias.mapping_status,
                               observation.reference_start_date, observation.reference_end_date,
                               observation.loan_count, observation.minimum_loan_rate,
                               observation.average_loan_rate, observation.maximum_loan_rate,
                               observation.minimum_loan_ale_rate, observation.average_loan_ale_rate,
                               observation.maximum_loan_ale_rate,
                               observation.id AS current_normalized_record_id,
                               serving.promoted_source_run_id AS source_run_id,
                               observation.raw_snapshot_id, serving.updated_at
                        FROM loanops.serving_loan_rate_observations serving
                        JOIN loanops.institution_aliases alias ON alias.id = serving.institution_alias_id
                        LEFT JOIN loanops.financial_institutions institution
                               ON institution.id = alias.financial_institution_id
                        JOIN loanops.loan_rate_observations observation
                             ON observation.id = serving.current_loan_rate_observation_id
                        ORDER BY alias.source_institution_name, alias.source_institution_code, serving.id
                        LIMIT :size OFFSET :offset
                        """,
                parameters,
                (rs, rowNum) -> new ServingLoanRateObservationResponse(
                        rs.getLong("id"),
                        rs.getString("source_institution_code"),
                        rs.getString("source_institution_name"),
                        rs.getString("canonical_institution_name"),
                        rs.getString("mapping_status"),
                        rs.getObject("reference_start_date", LocalDate.class),
                        rs.getObject("reference_end_date", LocalDate.class),
                        rs.getLong("loan_count"),
                        rs.getBigDecimal("minimum_loan_rate"),
                        rs.getBigDecimal("average_loan_rate"),
                        rs.getBigDecimal("maximum_loan_rate"),
                        rs.getBigDecimal("minimum_loan_ale_rate"),
                        rs.getBigDecimal("average_loan_ale_rate"),
                        rs.getBigDecimal("maximum_loan_ale_rate"),
                        rs.getLong("current_normalized_record_id"),
                        rs.getLong("source_run_id"),
                        rs.getLong("raw_snapshot_id"),
                        instant(rs, "updated_at")
                )
        );
        return PageResponse.of(content, page, size, total);
    }

    private List<PublicationSummaryResponse> publicationSummaries(
            String where,
            MapSqlParameterSource parameters,
            String orderAndPage
    ) {
        return jdbc.query(
                """
                        SELECT pr.id, pr.source_run_id, ds.code AS source_code, pr.status,
                               pr.quality_policy_version, pr.created_at, pr.completed_at,
                               (SELECT COUNT(*) FROM loanops.data_quality_issues issue
                                WHERE issue.source_run_id = pr.source_run_id) AS issue_count,
                               (SELECT COUNT(*) FROM loanops.quarantine_records quarantine
                                WHERE quarantine.source_run_id = pr.source_run_id) AS quarantine_count,
                               (SELECT COUNT(*) FROM loanops.data_changes change
                                WHERE change.publication_run_id = pr.id) AS change_count
                        FROM loanops.publication_runs pr
                        JOIN loanops.source_runs sr ON sr.id = pr.source_run_id
                        JOIN loanops.data_sources ds ON ds.id = sr.data_source_id
                        """ + where + " " + orderAndPage,
                parameters,
                (rs, rowNum) -> new PublicationSummaryResponse(
                        rs.getLong("id"),
                        rs.getLong("source_run_id"),
                        rs.getString("source_code"),
                        rs.getString("status"),
                        rs.getString("quality_policy_version"),
                        instant(rs, "created_at"),
                        instant(rs, "completed_at"),
                        rs.getLong("issue_count"),
                        rs.getLong("quarantine_count"),
                        rs.getLong("change_count")
                )
        );
    }

    private String qualityIssueSelect() {
        return """
                SELECT issue.id, issue.source_run_id, issue.raw_snapshot_id, issue.record_type,
                       issue.record_id, issue.rule_code, issue.field_name, issue.observed_value,
                       issue.message, issue.created_at
                FROM loanops.data_quality_issues issue
                """;
    }

    private RowMapper<QualityIssueResponse> qualityIssueMapper() {
        return (rs, rowNum) -> new QualityIssueResponse(
                rs.getLong("id"),
                rs.getLong("source_run_id"),
                rs.getLong("raw_snapshot_id"),
                rs.getString("record_type"),
                rs.getLong("record_id"),
                rs.getString("rule_code"),
                rs.getString("field_name"),
                rs.getString("observed_value"),
                rs.getString("message"),
                instant(rs, "created_at")
        );
    }

    private RowMapper<QuarantineSummaryResponse> quarantineMapper() {
        return (rs, rowNum) -> new QuarantineSummaryResponse(
                rs.getLong("id"),
                rs.getLong("source_run_id"),
                rs.getLong("raw_snapshot_id"),
                rs.getString("record_type"),
                rs.getLong("record_id"),
                instant(rs, "created_at")
        );
    }

    private String dataChangeSelect() {
        return """
                SELECT change.id, change.source_run_id, change.record_type,
                       change.stable_record_identity, change.change_type,
                       change.previous_record_id, change.current_record_id,
                       change.changed_fields, change.created_at
                FROM loanops.data_changes change
                """;
    }

    private RowMapper<DataChangeResponse> dataChangeMapper() {
        return (rs, rowNum) -> new DataChangeResponse(
                rs.getLong("id"),
                rs.getLong("source_run_id"),
                rs.getString("record_type"),
                rs.getString("stable_record_identity"),
                rs.getString("change_type"),
                nullableLong(rs, "previous_record_id"),
                rs.getLong("current_record_id"),
                rs.getString("changed_fields"),
                instant(rs, "created_at")
        );
    }

    private String servingLoanProductSelect() {
        return """
                SELECT serving.id, product.source_institution_code,
                       alias.source_institution_name,
                       institution.canonical_name AS canonical_institution_name,
                       product.source_product_id, product.product_name, product.disclosure_month,
                       product.disclosure_start_date, product.disclosure_end_date,
                       product.source_submitted_at,
                       product.id AS current_normalized_record_id,
                       serving.promoted_source_run_id AS source_run_id,
                       product.raw_snapshot_id,
                       (SELECT COUNT(*) FROM loanops.serving_product_terms term_serving
                        WHERE term_serving.source_institution_code = serving.source_institution_code
                          AND term_serving.source_product_id = serving.source_product_id) AS term_count,
                       serving.updated_at
                """;
    }

    private RowMapper<ServingLoanProductResponse> servingLoanProductMapper() {
        return (rs, rowNum) -> new ServingLoanProductResponse(
                rs.getLong("id"),
                rs.getString("source_institution_code"),
                rs.getString("source_institution_name"),
                rs.getString("canonical_institution_name"),
                rs.getString("source_product_id"),
                rs.getString("product_name"),
                rs.getString("disclosure_month"),
                rs.getObject("disclosure_start_date", LocalDate.class),
                rs.getObject("disclosure_end_date", LocalDate.class),
                rs.getObject("source_submitted_at", LocalDateTime.class),
                rs.getLong("current_normalized_record_id"),
                rs.getLong("source_run_id"),
                rs.getLong("raw_snapshot_id"),
                rs.getLong("term_count"),
                instant(rs, "updated_at")
        );
    }

    private RowMapper<SourceRunSummaryResponse> sourceRunMapper() {
        return (rs, rowNum) -> new SourceRunSummaryResponse(
                rs.getLong("id"),
                rs.getString("source_code"),
                rs.getString("status"),
                nullableInteger(rs, "http_status"),
                rs.getString("error_type"),
                rs.getString("error_message"),
                instant(rs, "started_at"),
                nullableInstant(rs, "ended_at"),
                rs.getLong("raw_snapshot_count")
        );
    }

    private MapSqlParameterSource pageParameters(int page, int size) {
        return new MapSqlParameterSource()
                .addValue("size", size)
                .addValue("offset", (long) page * size);
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new InvalidApiRequestException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidApiRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private long count(String sql) {
        return requiredLong(sql, new MapSqlParameterSource());
    }

    private long countBySourceRun(String table, long sourceRunId) {
        return requiredLong(
                "SELECT COUNT(*) FROM " + table + " WHERE source_run_id = :sourceRunId",
                new MapSqlParameterSource("sourceRunId", sourceRunId)
        );
    }

    private long requiredLong(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        if (value == null) {
            throw new IllegalStateException("Expected count query to return a value");
        }
        return value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
