package com.loanops.personal.canonical;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PersonalFinanceMigrationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
    @Test void populatedV8UpgradesAdditivelyAndRetainsExistingEvidence() {
        var dataSource=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        var jdbc=new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("loanops").defaultSchema("loanops").target("8").load().migrate();
        UUID connection=UUID.randomUUID(),run=UUID.randomUUID(),raw=UUID.randomUUID();
        jdbc.update("INSERT INTO loanops.open_banking_connections(id,owner_ref,mode,account_ciphertext,consent_until,created_at) VALUES (?,'synthetic-upgrade-owner','DEMO_REPLAY','synthetic-encrypted-account',now()+interval '1 day',now())",connection);
        jdbc.update("INSERT INTO loanops.source_sync_runs(id,connection_id,source,mode,capability,status,started_at,completed_at,page_count,record_count,complete) VALUES (?,?,'OPEN_BANKING','DEMO_REPLAY','BALANCE','SUCCESS',now(),now(),1,1,true)",run,connection);
        jdbc.update("INSERT INTO loanops.raw_openbanking_snapshots(id,sync_run_id,source,endpoint,requested_at,received_at,http_status,response_hash,payload_ciphertext,api_version,redacted,page_number) VALUES (?,?,'OPEN_BANKING','/synthetic',now(),now(),200,?,'synthetic-encrypted-raw','test',true,1)",raw,run,"a".repeat(64));
        jdbc.update("INSERT INTO loanops.open_banking_sync_checkpoints(connection_id,capability,successful_run_id,synced_at,mapped_ciphertext) VALUES (?,'BALANCE',?,now(),'synthetic-encrypted-balance')",connection,run);
        var before=jdbc.queryForList("SELECT * FROM loanops.raw_openbanking_snapshots");
        var checksums=jdbc.queryForList("SELECT version,checksum FROM loanops.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var result=Flyway.configure().dataSource(dataSource).schemas("loanops").defaultSchema("loanops").target("9").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM loanops.raw_openbanking_snapshots")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT version,checksum FROM loanops.flyway_schema_history WHERE version::int<=8 ORDER BY installed_rank")).isEqualTo(checksums);
        assertThat(jdbc.queryForObject("SELECT account_ciphertext FROM loanops.open_banking_connections WHERE id=?",String.class,connection)).isEqualTo("synthetic-encrypted-account");
        assertThat(jdbc.queryForObject("SELECT successful_run_id FROM loanops.open_banking_sync_checkpoints WHERE connection_id=?",UUID.class,connection)).isEqualTo(run);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loanops.financial_accounts",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loanops.personal_validation_runs",Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(dataSource).schemas("loanops").defaultSchema("loanops").target("9").load().migrate().migrationsExecuted).isZero();
    }
}
