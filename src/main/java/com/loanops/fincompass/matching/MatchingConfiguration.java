package com.loanops.fincompass.matching;
import com.loanops.fincompass.simulation.*;import com.loanops.fincompass.twin.*;import com.loanops.personal.canonical.*;
import org.springframework.context.annotation.*;import org.springframework.boot.autoconfigure.condition.*;import org.springframework.beans.factory.ObjectProvider;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.PlatformTransactionManager;import java.time.Clock;
@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="fincompass.personal.enabled",havingValue="true")
public class MatchingConfiguration {
 @Bean ProductCatalogReader productCatalogReader(JdbcTemplate jdbc){return new ProductCatalogReader(jdbc);}@Bean MatchingEngine matchingEngine(){return new MatchingEngine();}
 @Bean MatchStore matchStore(JdbcTemplate jdbc,PersonalFinanceCipher cipher,FinancialTwinStore twins,ScenarioStore scenarios,PlatformTransactionManager manager){return new MatchStore(jdbc,cipher,twins,scenarios,manager);}
 @Bean MatchService matchService(MatchStore store,ProductCatalogReader reader,FinancialTwinStore twins,ScenarioService scenarios,SimulationEngine simulations,MatchingEngine engine,ObjectProvider<Clock> clocks){return new MatchService(store,reader,twins,scenarios,simulations,engine,clocks.getIfAvailable(Clock::systemUTC));}
}
