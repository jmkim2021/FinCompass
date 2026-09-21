package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanops.fincompass.matching.*;
import com.loanops.fincompass.opportunity.OpportunityService;
import com.loanops.fincompass.simulation.ScenarioService;
import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="fincompass.personal.enabled",havingValue="true")
public class AgentConfiguration {
    @Bean @ConditionalOnMissingBean(AgentModelClient.class) AgentModelClient deterministicAgentModelClient(){return new DeterministicAgentModelClient();}
    @Bean NumericGrounding numericGrounding(){return new NumericGrounding();}
    @Bean AgentStore agentStore(JdbcTemplate jdbc,PersonalFinanceCipher cipher,FinancialTwinStore twins,PlatformTransactionManager manager){return new AgentStore(jdbc,cipher,twins,manager);}
    @Bean AgentToolbox agentToolbox(ObjectMapper json,FinancialTwinStore twins,ScenarioService scenarios,MatchService matches,ProductCatalogReader products,OpportunityService opportunities){return new AgentToolbox(json,twins,scenarios,matches,products,opportunities);}
    @Bean AgentOrchestrator agentOrchestrator(AgentModelClient model,AgentToolbox tools,AgentStore store,FinancialTwinStore twins,ScenarioService scenarios,PersonalFinanceCipher cipher,ObjectMapper json,NumericGrounding grounding,ObjectProvider<Clock> clocks){return new AgentOrchestrator(model,tools,store,twins,scenarios,cipher,json,grounding,clocks.getIfAvailable(Clock::systemUTC));}
}
