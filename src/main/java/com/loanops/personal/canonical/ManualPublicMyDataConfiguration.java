package com.loanops.personal.canonical;
import com.loanops.personal.openbanking.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.*;
import java.util.*;
import static com.loanops.personal.canonical.PersonalTypes.*;
@Configuration(proxyBeanMethods=false)
@Profile("manual-public-mydata")
@ConditionalOnProperty(prefix="fincompass.personal",name="enabled",havingValue="true")
public class ManualPublicMyDataConfiguration {
 @Bean ApplicationRunner publicMyDataDemo(PersonalFinanceService personal,OpenBankingStore ob,OpenBankingSyncService sync,OpenBankingProperties config,Environment env,ConfigurableApplicationContext context) {
  return args->{try {
   if(config.mode()!=OpenBankingMode.DEMO_REPLAY||!"none".equalsIgnoreCase(env.getProperty("spring.main.web-application-type"))) throw new PersonalFinanceException(PersonalFinanceException.Code.INVALID_REQUEST);
   String owner="explicit-main-demo";UUID user=personal.createDemoUser(owner);
   UUID pmd=personal.connectPublicMyDataMock(owner,user,Instant.now().plusSeconds(3600));
   var consent=personal.grant(owner,ProviderCode.PUBLIC_MYDATA,pmd,Set.of(Capability.INCOME,Capability.EMPLOYMENT),Instant.now().plusSeconds(1800));
   for(var cap:List.of(Capability.INCOME,Capability.EMPLOYMENT)) System.out.println(personal.normalize(owner,consent.id(),personal.collectMock(owner,pmd,consent.id(),cap,"main-home-buyer-v1")));
   UUID account=ob.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",Instant.now().plusSeconds(3600));
   var accountConsent=personal.grant(owner,ProviderCode.OPEN_BANKING,account,Set.of(Capability.BALANCE,Capability.TRANSACTIONS),Instant.now().plusSeconds(1800));
   if(!user.equals(accountConsent.userId())) throw new IllegalStateException("Explicit demo ownership mismatch");
   for(var cap:List.of(OpenBankingCapability.BALANCE,OpenBankingCapability.TRANSACTIONS)) System.out.println(personal.normalize(owner,accountConsent.id(),sync.sync(account,owner,cap,LocalDate.of(2026,9,1),LocalDate.of(2026,9,2)).id()));
   System.out.println("Public MyData MOCK income="+personal.currentIncome(owner,consent.id(),Capability.INCOME).evidence().size()+", employment="+personal.currentIncome(owner,consent.id(),Capability.EMPLOYMENT).evidence().size()+", Open Banking replay transactions="+personal.current(owner,accountConsent.id(),Capability.TRANSACTIONS,0,100).transactions().size());
  } finally {context.close();}};
 }
}
