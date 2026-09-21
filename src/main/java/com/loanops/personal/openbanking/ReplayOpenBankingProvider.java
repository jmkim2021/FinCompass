package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

/** Deterministic source emulator. The resulting raw JSON uses the normal persistence/mapping path. */
public final class ReplayOpenBankingProvider implements OpenBankingProvider {
    private final OpenBankingJson json;
    private final Clock clock;
    ReplayOpenBankingProvider(OpenBankingJson json, Clock clock) { this.json=json; this.clock=clock; }
    public OpenBankingMode mode() { return OpenBankingMode.DEMO_REPLAY; }
    public List<CapabilityState> capabilities() { return OpenBankingProvider.contractCapabilities(); }
    public ProviderResponse balance(Connection c) { return response(OpenBankingClient.BALANCE_PATH,fixture("balance.json")); }
    public ProviderResponse transactions(Connection c, LocalDate from, LocalDate to, String cursor) {
        ObjectNode body=(ObjectNode)json.parse(fixture("transactions.json"));
        ArrayNode filtered=JsonNodeFactory.instance.arrayNode();
        for(var row:body.withArray("res_list")) {
            LocalDate date=LocalDate.parse(row.get("tran_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
            if(!date.isBefore(from)&&!date.isAfter(to)) filtered.add(row);
        }
        int offset=0;
        try { if(cursor!=null) offset=Integer.parseInt(cursor); }
        catch(NumberFormatException ex) { throw new OpenBankingException(INVALID_REQUEST); }
        if(offset<0||offset>filtered.size()||offset%25!=0) throw new OpenBankingException(INVALID_REQUEST);
        ArrayNode page=JsonNodeFactory.instance.arrayNode();
        int end=Math.min(offset+25,filtered.size()); for(int i=offset;i<end;i++) page.add(filtered.get(i));
        body.set("res_list",page); body.put("page_record_cnt",page.size()); body.put("next_page_yn",end<filtered.size()?"Y":"N");
        if(end<filtered.size()) body.put("befor_inquiry_trace_info",Integer.toString(end)); else body.remove("befor_inquiry_trace_info");
        return response(OpenBankingClient.TRANSACTIONS_PATH,json.write(body));
    }
    private ProviderResponse response(String endpoint,String body) { return new ProviderResponse(new RawResponse(endpoint,clock.instant(),clock.instant(),200,body),List.of()); }
    private String fixture(String name) {
        try(var input=getClass().getResourceAsStream("/openbanking/demo-v1/"+name)) {
            if(input==null) throw new IllegalStateException();
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception ex) { throw new OpenBankingException(CONFIGURATION); }
    }
}
