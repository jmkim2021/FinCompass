package com.loanops.fincompass.agent;

import static com.loanops.fincompass.agent.AgentModels.*;

/** Vendor-neutral language-model seam. It receives tool-grounded context, never raw database access. */
public interface AgentModelClient {
    String provider();
    String version();
    Plan plan(ChatRequest request);
    String draft(DraftContext context);
}
