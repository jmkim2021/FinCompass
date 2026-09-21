package com.loanops.personal.openbanking;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.*;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

/** Local operator entry only: intentionally no unauthenticated personal-data HTTP endpoint. */
@Configuration(proxyBeanMethods=false)
@Profile("manual-open-banking")
@ConditionalOnProperty(prefix="fincompass.open-banking",name="enabled",havingValue="true")
public class ManualOpenBankingConfiguration {
    @Bean ApplicationRunner manualOpenBanking(Environment env,OpenBankingProperties config,OpenBankingStore store,
            OpenBankingTokenService tokens,OpenBankingSyncService sync,ConfigurableApplicationContext context) {
        return args -> {
            if (!"none".equalsIgnoreCase(env.getProperty("spring.main.web-application-type"))) throw new OpenBankingException(CONFIGURATION);
            String owner=env.getProperty("OPEN_BANKING_OWNER_REF","local-operator");
            String action=env.getProperty("OPEN_BANKING_ACTION","demo");
            try {
                switch(action) {
                    case "demo" -> {
                        if(config.mode()!=OpenBankingMode.DEMO_REPLAY) throw new OpenBankingException(INVALID_REQUEST);
                        UUID id=store.createConnection(owner,config.mode(),"000000000000000000000001",Instant.now().plusSeconds(3600));
                        sync.syncAll(id,owner,LocalDate.of(2026,9,1),LocalDate.of(2026,9,2)).forEach(System.out::println);
                    }
                    case "create" -> {
                        UUID id=store.createConnection(owner,config.mode(),required(env,"OPEN_BANKING_TEST_FINTECH_USE_NUM"),Instant.parse(required(env,"OPEN_BANKING_CONSENT_UNTIL")));
                        System.out.println("connectionId="+id);
                    }
                    case "authorize" -> System.out.println(tokens.authorization(connection(env),owner));
                    case "exchange" -> {
                        tokens.exchange(connection(env),owner,required(env,"OPEN_BANKING_AUTHORIZATION_CODE"),required(env,"OPEN_BANKING_OAUTH_STATE"));
                        System.out.println("OAuth exchange completed");
                    }
                    case "sync" -> sync.syncAll(connection(env),owner,LocalDate.parse(required(env,"OPEN_BANKING_FROM_DATE")),LocalDate.parse(required(env,"OPEN_BANKING_TO_DATE"))).forEach(System.out::println);
                    case "incremental" -> System.out.println(sync.incremental(connection(env),owner,LocalDate.parse(required(env,"OPEN_BANKING_TO_DATE"))));
                    case "status" -> System.out.println(store.run(UUID.fromString(required(env,"OPEN_BANKING_RUN_ID")),owner));
                    default -> throw new OpenBankingException(INVALID_REQUEST);
                }
            } catch(OpenBankingException ex) { throw ex; }
            catch(IllegalArgumentException | DateTimeException ex) { throw new OpenBankingException(INVALID_REQUEST); }
            finally { context.close(); }
        };
    }
    private static UUID connection(Environment env) { return UUID.fromString(required(env,"OPEN_BANKING_CONNECTION_ID")); }
    private static String required(Environment env,String name) {
        String value=env.getProperty(name); if(value==null||value.isBlank()) throw new OpenBankingException(CONFIGURATION); return value;
    }
}
