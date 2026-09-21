package com.loanops.personal.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanops.personal.gateway.PersonalFinanceProvider;
import com.loanops.personal.openbanking.SecretBox;
import com.loanops.personal.quality.PersonalQualityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="fincompass.personal",name="enabled",havingValue="true")
public class PersonalFinanceConfiguration {
    @Bean PersonalFinanceCipher personalFinanceCipher(SecretBox box,ObjectMapper json) { return new PersonalFinanceCipher(box,json); }
    @Bean PersonalFinanceStore personalFinanceStore(JdbcTemplate jdbc,PlatformTransactionManager manager,PersonalFinanceCipher cipher) { return new PersonalFinanceStore(jdbc,manager,cipher); }
    @Bean PersonalFinanceService personalFinanceService(List<PersonalFinanceProvider> providers,PersonalFinanceStore store,PersonalFinanceCipher cipher,PersonalQualityProperties config) {
        return new PersonalFinanceService(providers,store,cipher,config,Clock.systemUTC());
    }
}
