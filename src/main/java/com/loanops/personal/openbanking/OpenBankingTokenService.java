package com.loanops.personal.openbanking;

import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

public final class OpenBankingTokenService {
    private final OpenBankingStore store;
    private final OpenBankingClient client;
    private final OpenBankingProperties config;
    private final Clock clock;
    OpenBankingTokenService(OpenBankingStore store, OpenBankingClient client, OpenBankingProperties config, Clock clock) {
        this.store=store; this.client=client; this.config=config; this.clock=clock;
    }
    public URI authorization(UUID connectionId, String owner) {
        Connection c = liveConnection(connectionId,owner);
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.saveState(c,OpenBankingJson.hash(state),clock.instant().plusSeconds(600));
        return URI.create(config.authorizationUri()+"?"+OpenBankingClient.query(Map.of("response_type","code","client_id",config.clientId(),
                "redirect_uri",config.redirectUri(),"scope","inquiry","state",state)));
    }
    public void exchange(UUID connectionId, String owner, String code, String state) {
        Connection c = liveConnection(connectionId,owner);
        if (code == null || code.isBlank() || code.length()>4096 || state == null || state.length()>128
                || !store.consumeState(c,OpenBankingJson.hash(state))) throw new OpenBankingException(INVALID_STATE);
        try {
            store.updateToken(c,previous -> client.token(Map.of("grant_type","authorization_code","code",code,"redirect_uri",config.redirectUri()),null,"inquiry"));
        } catch (OpenBankingException ex) { throw new OpenBankingException(TOKEN_EXCHANGE_FAILED); }
    }
    Token valid(Connection c) {
        return store.updateToken(c,previous -> {
            if (previous == null) throw new OpenBankingException(TOKEN_REQUIRED);
            if (previous.expiresAt().isAfter(clock.instant().plusSeconds(30))) return previous;
            if (previous.refreshToken() == null || previous.refreshToken().isBlank()) throw new OpenBankingException(TOKEN_EXPIRED);
            try { return client.token(Map.of("grant_type","refresh_token","refresh_token",previous.refreshToken(),"scope",previous.scope()),previous.refreshToken(),previous.scope()); }
            catch (OpenBankingException ex) { throw new OpenBankingException(TOKEN_REFRESH_FAILED); }
        });
    }
    private Connection liveConnection(UUID id,String owner) {
        Connection c=store.connection(id,owner);
        if (config.mode()!=OpenBankingMode.LIVE_TEST || c.mode()!=OpenBankingMode.LIVE_TEST) throw new OpenBankingException(INVALID_REQUEST);
        return c;
    }
}
