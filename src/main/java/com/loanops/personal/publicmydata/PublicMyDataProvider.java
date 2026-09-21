package com.loanops.personal.publicmydata;
import com.loanops.personal.gateway.PersonalFinanceProvider;
import static com.loanops.personal.canonical.PersonalTypes.*;
import java.util.Set;
/** Minimal internal bundle projection, not the government's transport schema. */
public interface PublicMyDataProvider extends PersonalFinanceProvider {
    String request(String fixtureName,Capability capability);
    @Override default ProviderCode code() { return ProviderCode.PUBLIC_MYDATA; }
    @Override default Set<Capability> consentScopes() { return Set.of(Capability.INCOME,Capability.EMPLOYMENT); }
}
