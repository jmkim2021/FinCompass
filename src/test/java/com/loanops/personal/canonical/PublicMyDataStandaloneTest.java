package com.loanops.personal.canonical;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.time.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
@SpringBootTest(properties={"fincompass.personal.enabled=true","fincompass.open-banking.enabled=false"})
class PublicMyDataStandaloneTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
  r.add("PERSONAL_FINANCE_ENCRYPTION_KEY",()->Base64.getEncoder().encodeToString(new byte[32]));
 }
 @Autowired PersonalFinanceService service;
 @Test void mockStartsAndCollectsWithoutOpenBankingBeansOrCredentials() {
  UUID user=service.createDemoUser("standalone-mock"),connection=service.connectPublicMyDataMock("standalone-mock",user,Instant.now().plusSeconds(3600));
  var grant=service.grant("standalone-mock",ProviderCode.PUBLIC_MYDATA,connection,Set.of(Capability.INCOME),Instant.now().plusSeconds(1800));
  var run=service.collectMock("standalone-mock",connection,grant.id(),Capability.INCOME,"main-home-buyer-v1");
  // Clock-independent wiring assertion: source mapping remains available even when the fixed fixture ages.
  assertThat(service.normalize("standalone-mock",grant.id(),run).sourceSyncRunId()).isEqualTo(run);
 }
}
