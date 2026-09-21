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
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;

@Configuration(proxyBeanMethods=false)
@Profile("manual-personal-finance")
@ConditionalOnProperty(prefix="fincompass.personal",name="enabled",havingValue="true")
public class ManualPersonalFinanceConfiguration {
    @Bean ApplicationRunner manualPersonalFinance(Environment env,OpenBankingProperties config,OpenBankingStore source,
            OpenBankingSyncService sync,PersonalFinanceService personal,ConfigurableApplicationContext context) {
        return args->{
            try {
                if(!"none".equalsIgnoreCase(env.getProperty("spring.main.web-application-type"))||config.mode()!=OpenBankingMode.DEMO_REPLAY)
                    throw new PersonalFinanceException(INVALID_REQUEST);
                String owner="canonical-demo-operator";
                UUID connection=source.createConnection(owner,OpenBankingMode.DEMO_REPLAY,"000000000000000000000001",Instant.now().plusSeconds(3600));
                var grant=personal.grant(owner,ProviderCode.OPEN_BANKING,connection,Set.of(Capability.BALANCE,Capability.TRANSACTIONS),source.connection(connection,owner).consentUntil());
                var runs=sync.syncAll(connection,owner,LocalDate.of(2026,9,1),LocalDate.of(2026,9,2));
                for(var run:runs) if(run.capability()==OpenBankingCapability.BALANCE||run.capability()==OpenBankingCapability.TRANSACTIONS)
                    System.out.println(personal.normalize(owner,grant.id(),run.id()));
                var current=personal.current(owner,grant.id(),Capability.TRANSACTIONS,0,100);
                System.out.println("Canonical DEMO_REPLAY transactions="+current.transactions().size()+", completeForLatestAttempt="+current.state().completeForLatestAttempt());
            } finally { context.close(); }
        };
    }
}
