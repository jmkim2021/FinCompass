package com.loanops.fincompass.simulation;

import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="fincompass.personal.enabled",havingValue="true")
public class SimulationConfiguration {
    @Bean SimulationEngine simulationEngine() { return new SimulationEngine(); }
    @Bean ScenarioStore scenarioStore(JdbcTemplate jdbc, PersonalFinanceCipher cipher, FinancialTwinStore twins,
                                      PlatformTransactionManager manager) { return new ScenarioStore(jdbc,cipher,twins,manager); }
    @Bean ScenarioService scenarioService(ScenarioStore store,FinancialTwinStore twins,SimulationEngine engine,ObjectProvider<Clock> clocks) {
        return new ScenarioService(store,twins,engine,clocks.getIfAvailable(Clock::systemUTC));
    }
}
