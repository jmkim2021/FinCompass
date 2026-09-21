package com.loanops.fincompass.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.loanops.fincompass.matching.MatchingModels.ProductRecord;

/** Reads the existing LoanOps serving projection; it is deliberately not a second catalog. */
public final class ProductCatalogReader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProductCatalogReader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ProductRecord> read() {
        return jdbc.query("""
            SELECT p.id,t.id,t.loan_product_id,pp.id,pt.id,sp.promoted_source_run_id,st.promoted_source_run_id,
                   pr.id,tr.id,pr.payload_hash,tr.payload_hash,p.source_institution_code,p.source_product_id,
                   fi.canonical_name,p.product_name,t.mortgage_type_code,t.mortgage_type_name,
                   t.repayment_type_code,t.repayment_type_name,t.lending_rate_type_code,t.lending_rate_type_name,
                   t.minimum_rate,t.maximum_rate,t.average_rate,pr.fetched_at,tr.fetched_at,
                   tp.source_submitted_at AT TIME ZONE 'Asia/Seoul',pr.raw_payload
            FROM loanops.serving_loan_products sp
            JOIN loanops.loan_products p ON p.id=sp.current_loan_product_id
            JOIN loanops.serving_product_terms st ON st.source_institution_code=sp.source_institution_code
                 AND st.source_product_id=sp.source_product_id
            JOIN loanops.product_terms t ON t.id=st.current_product_term_id
            JOIN loanops.loan_products tp ON tp.id=t.loan_product_id
            JOIN loanops.raw_snapshots pr ON pr.id=p.raw_snapshot_id
            JOIN loanops.raw_snapshots tr ON tr.id=t.raw_snapshot_id
            JOIN loanops.publication_runs pp ON pp.source_run_id=sp.promoted_source_run_id
            JOIN loanops.publication_runs pt ON pt.source_run_id=st.promoted_source_run_id
            JOIN loanops.financial_institutions fi ON fi.id=p.financial_institution_id
            WHERE pp.status='PROMOTED' AND pt.status='PROMOTED'
              AND NOT EXISTS(SELECT 1 FROM loanops.quarantine_records q WHERE q.record_type='LOAN_PRODUCT' AND q.record_id=p.id)
              AND NOT EXISTS(SELECT 1 FROM loanops.quarantine_records q WHERE q.record_type='PRODUCT_TERM' AND q.record_id=t.id)
            ORDER BY p.source_institution_code,p.source_product_id,t.id
            """, (r,n) -> {
                String productId = r.getString(13);
                DemoMetadata demo = metadata(r.getString(28), productId);
                boolean synthetic = demo.fixtureOrigin() != null;
                return new ProductRecord(
                    r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6),r.getLong(7),
                    r.getLong(8),r.getLong(9),r.getString(10),r.getString(11),r.getString(12),productId,
                    r.getString(14),r.getString(15),r.getString(16),r.getString(17),r.getString(18),r.getString(19),
                    r.getString(20),r.getString(21),r.getBigDecimal(22),r.getBigDecimal(23),r.getBigDecimal(24),
                    instant(r.getTimestamp(25)),instant(r.getTimestamp(26)),r.getTimestamp(27)==null?null:instant(r.getTimestamp(27)),
                    "SERVING",synthetic?"DEMO_PRODUCT_FIXTURE":"REAL_PRODUCT_DATA",
                    synthetic?"DEMO_REPLAY":"LIVE_OR_TEST_SOURCE_PIPELINE",
                    demo.productType(),demo.purpose(),demo.amountMin(),demo.amountMax(),demo.termMin(),demo.termMax(),
                    demo.repaymentMethods(),demo.eligibilityText(),demo.constraints());
            });
    }

    private DemoMetadata metadata(String raw, String productId) {
        try {
            JsonNode root = json.readTree(raw);
            String origin = text(root, "fixture_origin");
            if (origin == null) return DemoMetadata.none();
            for (JsonNode product : root.path("result").path("baseList")) {
                if (!productId.equals(product.path("fin_prdt_cd").asText())) continue;
                JsonNode demo = product.path("fincompass_demo");
                if (!demo.isObject()) return new DemoMetadata(origin,null,null,null,null,null,null,null,null,null);
                List<String> repayments = new ArrayList<>();
                demo.path("repaymentMethods").forEach(value -> repayments.add(value.asText()));
                return new DemoMetadata(origin,text(demo,"productType"),text(demo,"purpose"),decimal(demo,"amountMin"),
                    decimal(demo,"amountMax"),integer(demo,"termMin"),integer(demo,"termMax"),
                    repayments.isEmpty()?null:List.copyOf(repayments),text(demo,"eligibilityText"),text(demo,"constraints"));
            }
            return new DemoMetadata(origin,null,null,null,null,null,null,null,null,null);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read frozen product provenance metadata", error);
        }
    }

    private static String text(JsonNode node,String field){JsonNode value=node.get(field);return value==null||value.isNull()||value.asText().isBlank()?null:value.asText();}
    private static BigDecimal decimal(JsonNode node,String field){String value=text(node,field);return value==null?null:new BigDecimal(value);}
    private static Integer integer(JsonNode node,String field){JsonNode value=node.get(field);return value==null||!value.canConvertToInt()?null:value.asInt();}
    private static Instant instant(java.sql.Timestamp value){return value.toInstant();}

    private record DemoMetadata(String fixtureOrigin,String productType,String purpose,BigDecimal amountMin,BigDecimal amountMax,
                                Integer termMin,Integer termMax,List<String> repaymentMethods,String eligibilityText,String constraints){
        static DemoMetadata none(){return new DemoMetadata(null,null,null,null,null,null,null,null,null,null);}
    }
}
