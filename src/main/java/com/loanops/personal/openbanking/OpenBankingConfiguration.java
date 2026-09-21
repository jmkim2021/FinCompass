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
    private final Clock clock;
    public OpenBankingConfiguration(org.springframework.beans.factory.ObjectProvider<Clock> clocks){this.clock=clocks.getIfAvailable(Clock::systemUTC);}
    @Bean OpenBankingJson openBankingJson(ObjectMapper mapper) { return new OpenBankingJson(mapper); }
    @Bean SecretBox openBankingSecretBox(OpenBankingProperties properties) { properties.validate(); return new SecretBox(properties.encryptionKey(),properties.encryptionKeyId()); }
    @Bean OpenBankingStore openBankingStore(JdbcTemplate jdbc,PlatformTransactionManager manager,SecretBox box,OpenBankingJson json) {
        return new OpenBankingStore(jdbc,manager,box,json,clock);
    }
    @Bean OpenBankingClient openBankingClient(OpenBankingProperties config,OpenBankingJson json) { return new OpenBankingClient(config,clock,json); }
    @Bean OpenBankingTokenService openBankingTokenService(OpenBankingStore store,OpenBankingClient client,OpenBankingProperties config) {
        return new OpenBankingTokenService(store,client,config,clock);
    }
    @Bean OpenBankingProvider openBankingProvider(OpenBankingClient client,OpenBankingTokenService tokens,OpenBankingStore store,OpenBankingProperties config,OpenBankingJson json) {
        return config.mode()==OpenBankingMode.LIVE_TEST?new LiveTestOpenBankingProvider(client,tokens,store,config,clock):new ReplayOpenBankingProvider(json,clock);
    }
    @Bean OpenBankingMapper openBankingMapper(OpenBankingJson json) { return new OpenBankingMapper(json); }
    @Bean OpenBankingSyncService openBankingSyncService(OpenBankingProvider provider,OpenBankingStore store,OpenBankingMapper mapper,OpenBankingProperties config) {
        return new OpenBankingSyncService(provider,store,mapper,config,clock);
    }
    @org.springframework.context.annotation.Primary
    @Bean OpenBankingPersonalFinanceProvider openBankingPersonalFinanceProvider(OpenBankingStore store,OpenBankingMapper mapper,
            OpenBankingJson json,SecretBox box,JdbcTemplate jdbc) {
        return new OpenBankingPersonalFinanceProvider(store,mapper,json,box,jdbc);
    }

}
