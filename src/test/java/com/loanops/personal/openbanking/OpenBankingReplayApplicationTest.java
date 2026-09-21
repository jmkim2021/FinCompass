package com.loanops.personal.openbanking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"fincompass.open-banking.enabled=true"})
class OpenBankingReplayApplicationTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("fincompass.open-banking.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired OpenBankingProperties properties;
    @Autowired OpenBankingProvider provider;
    @Autowired OpenBankingStore store;
    @Autowired OpenBankingSyncService sync;
    @Test void defaultReplayApplicationStartsWithoutLiveCredentialsAndCompletesBothRequiredCapabilities() {
        assertThat(properties.mode()).isEqualTo(OpenBankingMode.DEMO_REPLAY);
        assertThat(properties.clientSecret()).isEmpty();assertThat(provider).isInstanceOf(ReplayOpenBankingProvider.class);
        var id=store.createConnection("synthetic-wiring",properties.mode(),"000000000000000000000001",Instant.now().plusSeconds(3600));
        var runs=sync.syncAll(id,"synthetic-wiring",LocalDate.of(2026,9,1),LocalDate.of(2026,9,2));
        assertThat(runs.subList(0,2)).allSatisfy(run->assertThat(run.complete()).isTrue());
        assertThat(runs.subList(2,5)).allSatisfy(run->assertThat(run.status()).isEqualTo("UNSUPPORTED"));
        assertThat(store.snapshots(runs.get(1).id(),"synthetic-wiring")).hasSize(2);
        assertThatThrownBy(()->store.snapshots(runs.get(1).id(),"other-owner")).isInstanceOf(OpenBankingException.class);
    }
}
