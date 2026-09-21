package com.loanops.fincompass.api;

import com.loanops.fincompass.agent.AgentOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.loanops.fincompass.agent.AgentModels.*;
import static com.loanops.fincompass.api.TwinController.DEMO_OWNER;

@RestController
@RequestMapping("/api/v1/agent")
@ConditionalOnProperty(name="fincompass.demo.enabled",havingValue="true")
public class AgentController {
    private final AgentOrchestrator agent;
    public AgentController(AgentOrchestrator agent){this.agent=agent;}
    @PostMapping("/chat") public ChatResponse chat(@RequestBody ChatRequest request){return agent.chat(DEMO_OWNER,request);}
    @GetMapping("/runs/{id}/trace") public RunTrace trace(@PathVariable UUID id){return agent.trace(DEMO_OWNER,id);}
}
