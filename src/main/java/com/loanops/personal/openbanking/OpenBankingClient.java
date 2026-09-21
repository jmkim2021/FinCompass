package com.loanops.personal.openbanking;

import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

/** No redirects, no HTTP/body/header logging, bounded response, no automatic POST retry. */
public final class OpenBankingClient {
    public static final String BALANCE_PATH = "/v2.0/account/balance/fin_num";
    public static final String TRANSACTIONS_PATH = "/v2.0/account/transaction_list/fin_num";
    private final OpenBankingProperties properties;
    private final HttpClient http;
    private final Clock clock;
    private final OpenBankingJson json;
    OpenBankingClient(OpenBankingProperties properties, Clock clock, OpenBankingJson json) {
        this.properties = properties; this.clock = clock; this.json = json;
        http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    Token token(Map<String,String> grant, String previousRefresh, String previousScope) {
        Map<String,String> form = new LinkedHashMap<>(grant);
        form.put("client_id", properties.clientId()); form.put("client_secret", properties.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.tokenUri())).timeout(properties.readTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query(form))).build();
        RawResponse response = execute("oauth/token", request);
        if (response.httpStatus() != 200) throw new OpenBankingException(HTTP_ERROR);
        var body = json.parse(response.body());
        try {
            if (!"Bearer".equalsIgnoreCase(OpenBankingJson.required(body, "token_type"))) throw new IllegalArgumentException();
            String access = OpenBankingJson.required(body, "access_token");
            if (access.length() > 8192 || !access.matches("[A-Za-z0-9._~+/=-]+")) throw new IllegalArgumentException();
            long expiry = Long.parseLong(OpenBankingJson.required(body, "expires_in"));
            if (expiry < 1 || expiry > 31536000) throw new IllegalArgumentException();
            String refresh = body.hasNonNull("refresh_token") ? body.get("refresh_token").asText() : previousRefresh;
            String scope = body.hasNonNull("scope") ? OpenBankingJson.required(body, "scope") : previousScope;
            if (scope == null || scope.isBlank() || scope.length() > 1024 || (refresh != null && refresh.length() > 8192)) throw new IllegalArgumentException();
            return new Token(access, refresh, clock.instant().plusSeconds(expiry), scope);
        } catch (IllegalArgumentException ex) { throw new OpenBankingException(INVALID_RESPONSE); }
    }
    RawResponse get(String path, String accessToken, Map<String,String> params) {
        if (!Set.of(BALANCE_PATH, TRANSACTIONS_PATH).contains(path)) throw new OpenBankingException(INVALID_REQUEST);
        String base = properties.baseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path + "?" + query(params))).timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET().build();
        return execute(path, request);
    }
    private RawResponse execute(String endpoint, HttpRequest request) {
        Instant requested = clock.instant();
        try {
            HttpResponse<byte[]> response = http.send(request, info -> new LimitedBody(properties.maxResponseBytes()));
            return new RawResponse(endpoint, requested, clock.instant(), response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) { throw new OpenBankingException(TIMEOUT); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new OpenBankingException(CONNECTION_ERROR); }
        catch (java.io.IOException ex) { throw new OpenBankingException(CONNECTION_ERROR); }
        catch (IllegalArgumentException ex) { throw new OpenBankingException(INVALID_REQUEST); }
    }
    static String query(Map<String,String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int max; private Flow.Subscription subscription;
        LimitedBody(int max) { this.max = max; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > max - bytes.size()) { subscription.cancel(); result.completeExceptionally(new java.io.IOException("Response limit exceeded")); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
