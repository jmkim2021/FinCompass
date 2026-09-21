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
    @Bean PersonalFinanceCipher personalFinanceCipher(org.springframework.beans.factory.ObjectProvider<SecretBox> boxes,ObjectMapper json,org.springframework.core.env.Environment env) {
        SecretBox box=boxes.getIfAvailable(()->new SecretBox(env.getProperty("PERSONAL_FINANCE_ENCRYPTION_KEY",""),"personal-v1"));
        return new PersonalFinanceCipher(box,json);
    }
    @Bean com.loanops.personal.publicmydata.MockPublicMyDataProvider mockPublicMyDataProvider(JdbcTemplate jdbc,PersonalFinanceCipher cipher,ObjectMapper json) { return new com.loanops.personal.publicmydata.MockPublicMyDataProvider(jdbc,cipher,json); }
    @Bean PersonalFinanceStore personalFinanceStore(JdbcTemplate jdbc,PlatformTransactionManager manager,PersonalFinanceCipher cipher) { return new PersonalFinanceStore(jdbc,manager,cipher); }
    @Bean PersonalFinanceService personalFinanceService(List<PersonalFinanceProvider> providers,PersonalFinanceStore store,PersonalFinanceCipher cipher,PersonalQualityProperties config,com.loanops.personal.quality.IncomeQualityProperties incomeConfig) {
        return new PersonalFinanceService(providers,store,cipher,config,Clock.systemUTC(),incomeConfig);
    }
}
