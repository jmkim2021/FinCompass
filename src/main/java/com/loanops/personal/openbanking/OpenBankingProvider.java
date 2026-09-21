package com.loanops.personal.openbanking;

import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import static com.loanops.personal.openbanking.OpenBankingModels.*;

public interface OpenBankingProvider {
    OpenBankingMode mode();
    List<CapabilityState> capabilities();
    ProviderResponse balance(Connection connection);
    ProviderResponse transactions(Connection connection, LocalDate from, LocalDate to, String cursor);
    record ProviderResponse(RawResponse raw, @JsonIgnore Collection<String> secrets) {
        @Override public String toString() { return "ProviderResponse[REDACTED]"; }
    }
    static List<CapabilityState> contractCapabilities() {
        return Arrays.stream(OpenBankingCapability.values()).map(c -> new CapabilityState(c,
                c == OpenBankingCapability.BALANCE || c == OpenBankingCapability.TRANSACTIONS,
                c == OpenBankingCapability.BALANCE || c == OpenBankingCapability.TRANSACTIONS
                        ? "KFTC_ACCOUNT_INQUIRY_CONTRACT" : "SOURCE_CAPABILITY_MISSING")).toList();
    }
}
