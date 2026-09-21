package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.loanops.finance.AnnualInterestRate;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;
import static org.assertj.core.api.Assertions.*;

class OpenBankingUnitTest {
    private final OpenBankingJson json=new OpenBankingJson(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final OpenBankingMapper mapper=new OpenBankingMapper(json);
    private final Instant now=Instant.parse("2026-09-21T00:00:00Z");
    private RawResponse raw(String body) { return new RawResponse(OpenBankingClient.BALANCE_PATH,now,now,200,body); }
    @Test void annualRateHasExplicitPercentAndFractionFactories() {
        assertThat(AnnualInterestRate.fromPercent(new BigDecimal("4.5")).decimalFraction()).isEqualByComparingTo("0.045");
        assertThat(AnnualInterestRate.fromPercent(new BigDecimal("6.1")).decimalFraction()).isEqualByComparingTo("0.061");
        assertThat(AnnualInterestRate.fromDecimalFraction(new BigDecimal("0.045")).percent()).isEqualByComparingTo("4.5");
        assertThatThrownBy(()->AnnualInterestRate.fromPercent(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void signedBalanceMapsAndOldObservationIsExplicitlyStale() {
        var balance=mapper.balance(raw("{\"rsp_code\":\"A0000\",\"balance_amt\":\"-100.10\",\"api_tran_dtm\":\"20260901090000000\"}"),OpenBankingMode.LIVE_TEST);
        assertThat(balance.balance()).isEqualByComparingTo("-100.10");
        assertThat(balance.available()).isNull();
        assertThat(balance.sourceType()).isEqualTo("OPEN_BANKING_TEST");
        assertThat(OpenBankingMapper.stale(balance,now,Duration.ofDays(1))).isTrue();
    }
    @Test void missingObservationTimeUsesReceiptTime() {
        assertThat(mapper.balance(raw("{\"rsp_code\":\"A0000\",\"balance_amt\":\"100\"}"),OpenBankingMode.DEMO_REPLAY).observedAt()).isEqualTo(now);
    }
    @Test void invalidAndFutureBalanceAreRejected() {
        for(String body:List.of("{}","{\"rsp_code\":\"A0000\",\"balance_amt\":\"NaN\"}",
                "{\"rsp_code\":\"A0000\",\"balance_amt\":\"100\",\"api_tran_dtm\":\"20269999000000000\"}",
                "{\"rsp_code\":\"A0000\",\"balance_amt\":\"100\",\"api_tran_dtm\":\"20270901000000000\"}"))
            assertThatThrownBy(()->mapper.balance(raw(body),OpenBankingMode.DEMO_REPLAY)).isInstanceOf(OpenBankingException.class);
    }
    @Test void encryptionAuthenticatesContextAndHasNoPlaintextOrDeterministicCiphertext() {
        SecretBox box=new SecretBox(Base64.getEncoder().encodeToString(new byte[32]),"synthetic-test");
        String first=box.seal("one","synthetic-sensitive-value");
        assertThat(first).doesNotContain("synthetic-sensitive-value").isNotEqualTo(box.seal("one","synthetic-sensitive-value"));
        assertThat(box.open("one",first)).isEqualTo("synthetic-sensitive-value");
        assertThatThrownBy(()->box.open("two",first)).isInstanceOf(OpenBankingException.class);
        assertThat(box.identity("same")).isEqualTo(box.identity("same")).isNotEqualTo(box.identity("other"));
    }
    @Test void secretDtosAndConfigurationDoNotSerializeTokensOrLogSecrets() {
        Token token=new Token("synthetic-access","synthetic-refresh",now,"inquiry");
        assertThat(json.write(token)).doesNotContain("synthetic-access","synthetic-refresh");
        assertThat(token.toString()).doesNotContain("synthetic-access","synthetic-refresh");
        assertThat(json.write(raw("secretbody"))).doesNotContain("secretbody");
        assertThat(raw("secretbody").toString()).doesNotContain("secretbody");
    }
    @Test void rawSanitizationRemovesNestedTokenAndHeaderFieldsAndMalformedBodies() {
        var sanitized=json.sanitize("{\"nested\":{\"access_token\":\"synthetic-token\"},\"headers\":{\"Authorization\":\"Bearer value\"},\"text\":\"known-secret\"}",List.of("known-secret"));
        assertThat(sanitized.body()).doesNotContain("synthetic-token","Bearer value","known-secret");
        assertThat(sanitized.redacted()).isTrue();
        assertThat(json.sanitize("malformed known-secret",List.of()).body()).doesNotContain("known-secret");
        assertThat(json.sanitize("{\"amount\":\"10\"}",List.of()).redacted()).isFalse();
    }
    @Test void escapedSecretInUnknownFieldOrArrayIsRedactedAfterJsonDecoding() {
        String secret="synthetic-quoted-\"-secret";
        String source=json.write(Map.of("diagnostic",secret,"details",List.of(secret)));
        var sanitized=json.sanitize(source,List.of(secret));
        assertThat(sanitized.redacted()).isTrue();
        assertThat(sanitized.body()).doesNotContain("synthetic-quoted");
    }
    @Test void liveEndpointValidationRejectsExternalPlainHttpAndEmbeddedCredentials() {
        for(String endpoint:List.of("http://example.com","https://secret@example.com","https://example.com?token=bad","file:///tmp/file"))
            assertThatThrownBy(()->OpenBankingProperties.safeEndpoint(endpoint)).isInstanceOf(OpenBankingException.class);
        assertThatCode(()->OpenBankingProperties.safeEndpoint("http://127.0.0.1:12345/token")).doesNotThrowAnyException();
    }
}
