package com.loanops.fincompass.twin;
import com.loanops.personal.canonical.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="fincompass.personal.enabled",havingValue="true")
public class FinancialTwinConfiguration {
 @Bean FinancialTwinStore financialTwinStore(JdbcTemplate jdbc,PersonalFinanceCipher cipher,PlatformTransactionManager manager){return new FinancialTwinStore(jdbc,cipher,manager);}
 @Bean FinancialTwinService financialTwinService(PersonalFinanceService personal,FinancialTwinStore store,PersonalFinanceCipher cipher,ObjectProvider<Clock> clocks){return new FinancialTwinService(personal,store,cipher,clocks.getIfAvailable(Clock::systemUTC));}
}
