package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="fincompass.open-banking",name="enabled",havingValue="true")
public class OpenBankingConfiguration {
    @Bean OpenBankingJson openBankingJson(ObjectMapper mapper) { return new OpenBankingJson(mapper); }
    @Bean SecretBox openBankingSecretBox(OpenBankingProperties properties) { properties.validate(); return new SecretBox(properties.encryptionKey(),properties.encryptionKeyId()); }
    @Bean OpenBankingStore openBankingStore(JdbcTemplate jdbc,PlatformTransactionManager manager,SecretBox box,OpenBankingJson json) {
        return new OpenBankingStore(jdbc,manager,box,json,Clock.systemUTC());
    }
    @Bean OpenBankingClient openBankingClient(OpenBankingProperties config,OpenBankingJson json) { return new OpenBankingClient(config,Clock.systemUTC(),json); }
    @Bean OpenBankingTokenService openBankingTokenService(OpenBankingStore store,OpenBankingClient client,OpenBankingProperties config) {
        return new OpenBankingTokenService(store,client,config,Clock.systemUTC());
    }
    @Bean OpenBankingProvider openBankingProvider(OpenBankingClient client,OpenBankingTokenService tokens,OpenBankingStore store,OpenBankingProperties config,OpenBankingJson json) {
        return config.mode()==OpenBankingMode.LIVE_TEST?new LiveTestOpenBankingProvider(client,tokens,store,config,Clock.systemUTC()):new ReplayOpenBankingProvider(json,Clock.systemUTC());
    }
    @Bean OpenBankingMapper openBankingMapper(OpenBankingJson json) { return new OpenBankingMapper(json); }
    @Bean OpenBankingSyncService openBankingSyncService(OpenBankingProvider provider,OpenBankingStore store,OpenBankingMapper mapper,OpenBankingProperties config) {
        return new OpenBankingSyncService(provider,store,mapper,config,Clock.systemUTC());
    }
}
