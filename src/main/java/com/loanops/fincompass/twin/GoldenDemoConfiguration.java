package com.loanops.fincompass.twin;
import com.loanops.personal.openbanking.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import java.time.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.loanops.fincompass.twin.TwinModels.*;
import static com.loanops.fincompass.api.TwinController.DEMO_OWNER;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="fincompass.demo.enabled",havingValue="true")
public class GoldenDemoConfiguration {
 public GoldenDemoConfiguration(org.springframework.core.env.Environment environment){if(!Set.of("127.0.0.1","::1").contains(environment.getProperty("server.address","")))throw new IllegalArgumentException("Demo requires explicit loopback server.address");}
 public static final Instant DEMO_AS_OF=Instant.parse("2026-09-21T10:00:00Z");
 @Bean Clock goldenDemoClock(){return Clock.fixed(DEMO_AS_OF,ZoneOffset.UTC);}
 @Bean ApplicationRunner goldenTwinBootstrap(GoldenHomeBuyerSources sources,FinancialTwinService service,FinancialTwinStore store,OpenBankingProperties properties){return args->{
  if(properties.mode()!=OpenBankingMode.DEMO_REPLAY)throw new IllegalArgumentException("Golden demo requires DEMO_REPLAY");
  if(store.latestOptional(DEMO_OWNER).isPresent())return;
  var seed=sources.seed(DEMO_OWNER);Map<UUID,Category> categories=new HashMap<>();seed.classifications().forEach((id,value)->categories.put(id,Category.valueOf(value)));
  service.create(DEMO_OWNER,seed.obConsentId(),seed.incomeConsentId(),facts(),categories);
 };}
 public static List<Fact> facts(){return List.of(fact("EXISTING_LOAN_BALANCE","30000000"),fact("SCHEDULED_MONTHLY_DEBT_SERVICE","600000"),fact("MONTHLY_FIXED_OBLIGATION","200000"),fact("MONTHLY_ESSENTIAL_EXPENSE","2200000"),fact("OTHER_ASSET","0"),fact("ACCOUNT_CURRENCY_KRW","1"),fact("EXISTING_LOAN_ANNUAL_RATE","0.061"));}
 private static Fact fact(String kind,String amount){return new Fact(UUID.nameUUIDFromBytes(("golden-homebuyer-v1:"+kind).getBytes(StandardCharsets.UTF_8)),kind,new BigDecimal(amount),"USER_INPUT","DEMO_REPLAY",DEMO_AS_OF,"KRW","golden-homebuyer-v1:"+kind);}
}
