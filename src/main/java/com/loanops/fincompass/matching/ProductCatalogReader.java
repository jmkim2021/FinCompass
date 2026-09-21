package com.loanops.fincompass.matching;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.loanops.fincompass.matching.MatchingModels.*;

/** Reads the existing LoanOps serving projection; it is deliberately not a second catalog. */
public final class ProductCatalogReader {
    private final JdbcTemplate jdbc;
    public ProductCatalogReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<ProductRecord> read() {
        return jdbc.query("""
            SELECT p.id,t.id,t.loan_product_id,pp.id,pt.id,sp.promoted_source_run_id,st.promoted_source_run_id,
                   pr.id,tr.id,pr.payload_hash,tr.payload_hash,p.source_institution_code,p.source_product_id,
                   fi.canonical_name,p.product_name,t.mortgage_type_code,t.mortgage_type_name,
                   t.repayment_type_code,t.repayment_type_name,t.lending_rate_type_code,t.lending_rate_type_name,
                   t.minimum_rate,t.maximum_rate,t.average_rate,pr.fetched_at,tr.fetched_at,
                   tp.source_submitted_at AT TIME ZONE 'Asia/Seoul',
                   CASE WHEN pr.raw_payload LIKE '%\"fixture_origin\"%' OR tr.raw_payload LIKE '%\"fixture_origin\"%'
                        THEN 'DEMO_REPLAY' ELSE 'LIVE_OR_TEST_SOURCE_PIPELINE' END
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
            """, (r,n) -> new ProductRecord(
                r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6),r.getLong(7),
                r.getLong(8),r.getLong(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),
                r.getString(14),r.getString(15),r.getString(16),r.getString(17),r.getString(18),r.getString(19),
                r.getString(20),r.getString(21),r.getBigDecimal(22),r.getBigDecimal(23),r.getBigDecimal(24),
                r.getTimestamp(25).toInstant(),r.getTimestamp(26).toInstant(),r.getTimestamp(27)==null?null:r.getTimestamp(27).toInstant(),
                "SERVING","REAL_PRODUCT_DATA",r.getString(28),
                null,null,null,null,null,null,null,null,null));
    }
}
