package com.loanops.personal.publicmydata;

import com.fasterxml.jackson.databind.*;
import com.loanops.personal.canonical.*;
import com.loanops.personal.gateway.PersonalFinanceProvider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;

/** No HTTP client. Sources are allowlisted, versioned synthetic classpath fixtures. */
public class MockPublicMyDataProvider implements PublicMyDataProvider {
    private final JdbcTemplate jdbc; private final PersonalFinanceCipher cipher; private final ObjectMapper json;
    public MockPublicMyDataProvider(JdbcTemplate jdbc,PersonalFinanceCipher cipher,ObjectMapper json) { this.jdbc=jdbc;this.cipher=cipher;this.json=json; }
    @Override public SourceConnection connection(UUID id,String owner) { return new ProviderLedger(jdbc).connection(id,owner,code()); }
    @Override public String request(String fixtureName,Capability capability) {
        if(!Set.of("main-home-buyer-v1","self-employed-v1","missing-income-v1").contains(fixtureName)) throw new PersonalFinanceException(INVALID_REQUEST);
        try(var input=getClass().getResourceAsStream("/fixtures/public-mydata/"+fixtureName+".json")) {
            JsonNode document=json.readTree(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8));
            var items=json.createArrayNode();
            if(consentScopes().contains(capability)) for(JsonNode item:document.get("evidence")) {
                if((capability==Capability.INCOME&&"INCOME_CERTIFICATE".equals(item.path("evidenceType").asText()))
                    ||(capability==Capability.EMPLOYMENT&&"EMPLOYMENT_ELIGIBILITY".equals(item.path("evidenceType").asText()))) items.add(item);
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode)document).set("evidence",items);return json.writeValueAsString(document);
        } catch(Exception ex) { throw new PersonalFinanceException(INVALID_SOURCE_DATA); }
    }
    /** Reject unknown fields rather than persist reflected personal documents or identifiers. */
    public static String minimize(String body,ObjectMapper json) {
        try {
            var n=json.readTree(body);
            if(!n.isObject()||!allowed(n,Set.of("contractVersion","fixtureId","evidence"))||!n.path("evidence").isArray()||n.get("evidence").size()>10) return "{\"rejected\":true}";
            if(!n.path("fixtureId").asText().matches("PMD-DEMO-[A-Z0-9-]{1,40}")||!"pmd-minimal-v1".equals(n.path("contractVersion").asText())) return "{\"rejected\":true}";
            for(var e:n.get("evidence")) {
                if(!e.isObject()||!allowed(e,Set.of("evidenceType","verifiedAnnualIncome","incomeYear","employmentStatus","currency","asOf"))) return "{\"rejected\":true}";
                if(!Set.of("INCOME_CERTIFICATE","EMPLOYMENT_ELIGIBILITY").contains(e.path("evidenceType").asText())) return "{\"rejected\":true}";
                if(e.hasNonNull("verifiedAnnualIncome")&&!e.get("verifiedAnnualIncome").asText().matches("[0-9]{1,18}(\\.[0-9]{1,2})?")) return "{\"rejected\":true}";
                if(e.hasNonNull("incomeYear")&&(!e.get("incomeYear").isIntegralNumber()||!e.get("incomeYear").canConvertToInt()||e.get("incomeYear").asInt()<1900||e.get("incomeYear").asInt()>2200)) return "{\"rejected\":true}";
                if(e.hasNonNull("employmentStatus")&&!Set.of("EMPLOYED","SELF_EMPLOYED","UNEMPLOYED","UNKNOWN").contains(e.get("employmentStatus").asText())) return "{\"rejected\":true}";
                if(e.hasNonNull("currency")&&!"KRW".equals(e.get("currency").asText())) return "{\"rejected\":true}";
                if(e.hasNonNull("asOf")) Instant.parse(e.get("asOf").asText());
            }
            return json.writeValueAsString(n);
        } catch(Exception ex) { return "{\"rejected\":true}"; }
    }
    private static boolean allowed(JsonNode node,Set<String> fields) { var keys=node.fieldNames();while(keys.hasNext()) if(!fields.contains(keys.next())) return false;return true; }
    @Override public SourceBatch read(UUID run,String owner) {
        var rows=jdbc.queryForList("SELECT s.*,c.mode FROM loanops.provider_sync_runs s JOIN loanops.provider_connections c ON c.id=s.connection_id JOIN loanops.financial_users u ON u.id=c.financial_user_id WHERE s.id=? AND c.provider='PUBLIC_MYDATA' AND u.owner_ref=?",run,owner);
        if(rows.isEmpty()) throw new PersonalFinanceException(NOT_FOUND);
        var row=rows.getFirst();UUID connection=(UUID)row.get("connection_id");Capability cap=Capability.valueOf((String)row.get("capability"));
        Instant start=((java.sql.Timestamp)row.get("started_at")).toInstant(),end=((java.sql.Timestamp)row.get("completed_at")).toInstant();
        List<IncomeObservation> items=new ArrayList<>();boolean valid=true;
        var evidence=jdbc.queryForList("SELECT * FROM loanops.provider_evidence WHERE provider_sync_run_id=?",run);
        try {
            if(evidence.size()!=1) throw new IllegalArgumentException();
            var raw=evidence.getFirst();UUID id=(UUID)raw.get("id");String body=cipher.open("provider:"+id,(String)raw.get("payload_ciphertext"),String.class);
            JsonNode n=json.readTree(body);if(!n.has("evidence")) throw new IllegalArgumentException();int index=0;
            for(var e:n.get("evidence")) {
                var type=EvidenceType.valueOf(e.get("evidenceType").asText());
                if(cap==Capability.INCOME&&type!=EvidenceType.INCOME_CERTIFICATE||cap==Capability.EMPLOYMENT&&type!=EvidenceType.EMPLOYMENT_ELIGIBILITY) throw new IllegalArgumentException();
                Instant asOf=Instant.parse(e.get("asOf").asText());
                var amount=e.hasNonNull("verifiedAnnualIncome")?new BigDecimal(e.get("verifiedAnnualIncome").asText()):null;
                Integer year=e.hasNonNull("incomeYear")?e.get("incomeYear").asInt():null;
                EmploymentStatus employment=e.hasNonNull("employmentStatus")?EmploymentStatus.valueOf(e.get("employmentStatus").asText()):null;
                if(type==EvidenceType.EMPLOYMENT_ELIGIBILITY&&(amount!=null||year!=null)) throw new IllegalArgumentException();
                items.add(new IncomeObservation(amount,year,employment,type,e.hasNonNull("currency")?e.get("currency").asText():null,
                    new Evidence(id,asOf,((java.sql.Timestamp)raw.get("collected_at")).toInstant(),(String)raw.get("endpoint"),(String)raw.get("response_hash"),index++)));
            }
        } catch(Exception ex) {valid=false;items.clear();}
        return new SourceBatch(run,connection,SourceType.PUBLIC_MYDATA_MOCK,SourceMode.DEMO_REPLAY,cap,(String)row.get("status"),(Boolean)row.get("complete"),null,null,null,start,end,0,consentScopes(),null,null,List.of(),valid,items);
    }
}
