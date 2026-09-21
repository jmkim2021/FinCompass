package com.loanops.personal.openbanking;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.security.SecureRandom;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

public final class LiveTestOpenBankingProvider implements OpenBankingProvider {
    private final OpenBankingClient client;
    private final OpenBankingTokenService tokens;
    private final OpenBankingStore store;
    private final OpenBankingProperties config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    LiveTestOpenBankingProvider(OpenBankingClient client, OpenBankingTokenService tokens, OpenBankingStore store, OpenBankingProperties config, Clock clock) {
        this.client=client; this.tokens=tokens; this.store=store; this.config=config; this.clock=clock;
    }
    public OpenBankingMode mode() { return OpenBankingMode.LIVE_TEST; }
    public List<CapabilityState> capabilities() { return OpenBankingProvider.contractCapabilities(); }
    public ProviderResponse balance(Connection c) { return request(c,OpenBankingClient.BALANCE_PATH,Map.of()); }
    public ProviderResponse transactions(Connection c, LocalDate from, LocalDate to, String cursor) {
        Map<String,String> params=new LinkedHashMap<>();
        params.put("inquiry_type","A"); params.put("inquiry_base","D"); params.put("sort_order","A");
        params.put("from_date",from.format(DateTimeFormatter.BASIC_ISO_DATE)); params.put("to_date",to.format(DateTimeFormatter.BASIC_ISO_DATE));
        if (cursor!=null) params.put("befor_inquiry_trace_info",cursor);
        return request(c,OpenBankingClient.TRANSACTIONS_PATH,params);
    }
    private ProviderResponse request(Connection c, String endpoint, Map<String,String> parameters) {
        Token token=tokens.valid(c);
        if (!Arrays.asList(token.scope().split("\\s+")).contains("inquiry")) throw new OpenBankingException(SOURCE_CAPABILITY_MISSING);
        Map<String,String> params=new LinkedHashMap<>(parameters);
        params.put("fintech_use_num",store.account(c));
        // KFTC: ten-character institution prefix + 'U' + nine-character unique component.
        String alphabet="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder suffix=new StringBuilder(); for(int i=0;i<9;i++) suffix.append(alphabet.charAt(random.nextInt(alphabet.length())));
        params.put("bank_tran_id",config.bankTranPrefix()+"U"+suffix);
        params.put("tran_dtime",DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(clock.instant()));
        List<String> secrets=new ArrayList<>(); secrets.add(token.accessToken()); secrets.add(config.clientSecret());
        if(token.refreshToken()!=null) secrets.add(token.refreshToken());
        return new ProviderResponse(client.get(endpoint,token.accessToken(),params),List.copyOf(secrets));
    }
}
