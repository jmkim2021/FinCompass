package com.loanops.personal.canonical;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class ProviderGeneralizationMigrationTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
 @Test void populatedV9BackfillsGenericRelationsWithoutChangingLegacyEvidenceOrCurrent() {
  var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var jdbc=new JdbcTemplate(ds);
  Flyway.configure().dataSource(ds).schemas("loanops").defaultSchema("loanops").target("9").load().migrate();
  UUID user=UUID.randomUUID(),conn=UUID.randomUUID(),run=UUID.randomUUID(),raw=UUID.randomUUID(),consent=UUID.randomUUID(),account=UUID.randomUUID(),validation=UUID.randomUUID(),evidence=UUID.randomUUID();
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
   jdbc.update("INSERT INTO loanops.financial_users VALUES(?,'synthetic-upgrade',now())",user);
   jdbc.update("INSERT INTO loanops.open_banking_connections(id,owner_ref,mode,account_ciphertext,consent_until,created_at) VALUES (?,'synthetic-upgrade','DEMO_REPLAY','synthetic-cipher',now()+interval '1 day',now())",conn);
   jdbc.update("INSERT INTO loanops.source_sync_runs(id,connection_id,source,mode,capability,status,started_at,completed_at,page_count,record_count,complete) VALUES (?,?,'OPEN_BANKING','DEMO_REPLAY','BALANCE','SUCCESS',now(),now(),1,1,true)",run,conn);
   jdbc.update("INSERT INTO loanops.raw_openbanking_snapshots(id,sync_run_id,source,endpoint,requested_at,received_at,http_status,response_hash,payload_ciphertext,api_version,redacted,page_number) VALUES (?,?,'OPEN_BANKING','/synthetic',now(),now(),200,?,'synthetic-raw','test',true,1)",raw,run,"a".repeat(64));
   jdbc.update("INSERT INTO loanops.consent_grants(id,financial_user_id,source_provider,source_connection_id,source_mode,consent_kind,scopes,granted_at,expires_at) VALUES (?,?,'OPEN_BANKING',?,'DEMO_REPLAY','DEMO',ARRAY['BALANCE'],now(),now()+interval '1 day')",consent,user,conn);
   jdbc.update("INSERT INTO loanops.financial_accounts VALUES(?,?,'OPEN_BANKING',?,?,?,'synthetic-account')",account,user,conn,conn.toString(),evidence);
   jdbc.update("INSERT INTO loanops.personal_validation_runs(id,consent_grant_id,source_sync_run_id,financial_account_id,capability,state,promoted,source_started_at,as_of,evaluated_at,policy_version,balance_stale_after_seconds) VALUES (?,?,?,?,'BALANCE','VALIDATED_COMPLETE',true,now(),now(),now(),'personal-dq-v1',86400)",validation,consent,run,account);
   jdbc.update("INSERT INTO loanops.personal_source_evidence VALUES(?,?,?,?,'OPEN_BANKING_TEST','DEMO_REPLAY','ACCOUNT',now(),now(),now(),NULL)",evidence,validation,run,raw);
   jdbc.update("INSERT INTO loanops.personal_current_states VALUES(?,'BALANCE',?)",account,validation);return null;
  });
  var rawBefore=jdbc.queryForList("SELECT * FROM loanops.raw_openbanking_snapshots");var accountBefore=jdbc.queryForList("SELECT * FROM loanops.financial_accounts");
  var checksums=jdbc.queryForList("SELECT version,checksum FROM loanops.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
  var flyway=Flyway.configure().dataSource(ds).schemas("loanops").defaultSchema("loanops").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);flyway.validate();
  assertThat(jdbc.queryForList("SELECT * FROM loanops.raw_openbanking_snapshots")).isEqualTo(rawBefore);assertThat(jdbc.queryForList("SELECT * FROM loanops.financial_accounts")).isEqualTo(accountBefore);
  assertThat(jdbc.queryForList("SELECT version,checksum FROM loanops.flyway_schema_history WHERE version::int<=9 ORDER BY installed_rank")).isEqualTo(checksums);
  assertThat(jdbc.queryForObject("SELECT provider_connection_id FROM loanops.consent_grants WHERE id=?",UUID.class,consent)).isEqualTo(conn);
  assertThat(jdbc.queryForObject("SELECT provider_sync_run_id FROM loanops.personal_validation_runs WHERE id=?",UUID.class,validation)).isEqualTo(run);
  assertThat(jdbc.queryForObject("SELECT provider_evidence_id FROM loanops.personal_source_evidence WHERE id=?",UUID.class,evidence)).isEqualTo(raw);
  assertThat(jdbc.queryForObject("SELECT raw_snapshot_id FROM loanops.personal_source_evidence WHERE id=?",UUID.class,evidence)).isEqualTo(raw);
  assertThat(jdbc.queryForObject("SELECT validation_run_id FROM loanops.personal_current_states",UUID.class)).isEqualTo(validation);
  assertThat(flyway.migrate().migrationsExecuted).isZero();
 }
}
