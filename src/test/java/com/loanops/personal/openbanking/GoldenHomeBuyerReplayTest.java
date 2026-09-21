package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GoldenHomeBuyerReplayTest {
    private final OpenBankingJson json=new OpenBankingJson(new ObjectMapper().findAndRegisterModules());
    private final OpenBankingMapper mapper=new OpenBankingMapper(json);
    private final Clock clock=Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"),ZoneOffset.UTC);
    private final ReplayOpenBankingProvider golden=new ReplayOpenBankingProvider(json,clock,"golden-homebuyer-v1");

    @Test void goldenBalanceIsSeparateAndOriginalFixtureIsUnchanged() {
        var value=mapper.balance(golden.balance(null).raw(),OpenBankingMode.DEMO_REPLAY);
        assertThat(value.balance()).isEqualByComparingTo("120000000");
        assertThat(value.available()).isEqualByComparingTo("120000000");
        assertThat(value.observedAt()).isEqualTo(Instant.parse("2026-09-21T00:00:00Z"));
        var original=new ReplayOpenBankingProvider(json,clock);
        assertThat(mapper.balance(original.balance(null).raw(),OpenBankingMode.DEMO_REPLAY).balance()).isEqualByComparingTo("1500000");
        assertThat(original.balance(null).raw().body()).contains("SYNTHETIC_DEMO_V1");
        assertThat(golden.balance(null).raw().body()).contains("SYNTHETIC_GOLDEN_HOME_BUYER_V1");
    }
    @Test void goldenMonthIsFullyPaginatedAndReconcilesExplicitInputs() {
        var first=mapper.transactions(golden.transactions(null,GoldenHomeBuyerSources.FROM,GoldenHomeBuyerSources.THROUGH,null).raw(),GoldenHomeBuyerSources.FROM,GoldenHomeBuyerSources.THROUGH);
        assertThat(first.transactions()).hasSize(25);assertThat(first.hasNext()).isTrue();
        var second=mapper.transactions(golden.transactions(null,GoldenHomeBuyerSources.FROM,GoldenHomeBuyerSources.THROUGH,first.nextCursor()).raw(),GoldenHomeBuyerSources.FROM,GoldenHomeBuyerSources.THROUGH);
        assertThat(second.transactions()).hasSize(7);assertThat(second.hasNext()).isFalse();
        var transactions=new ArrayList<>(first.transactions());transactions.addAll(second.transactions());
        Map<String,BigDecimal> totals=new HashMap<>();
        transactions.forEach(t->totals.merge(t.description(),t.amount(),BigDecimal::add));
        assertThat(totals.get("GOLDEN_V1_INCOME")).isEqualByComparingTo("4800000");
        assertThat(totals.get("GOLDEN_V1_LIVING_EXPENSE")).isEqualByComparingTo("2800000");
        assertThat(totals.get("GOLDEN_V1_DEBT_SERVICE")).isEqualByComparingTo("600000");
        assertThat(totals.get("GOLDEN_V1_TRANSFER")).isEqualByComparingTo("200000");
        assertThat(transactions.getLast().balanceAfter()).isEqualByComparingTo("120000000");
        assertThat(transactions).extracting(OpenBankingModels.ObservedTransaction::date)
                .allMatch(date->YearMonth.from(date).equals(YearMonth.of(2026,8)));
    }
    @Test void fixtureSelectionCannotReadArbitraryClasspathResources() {
        assertThatThrownBy(()->new ReplayOpenBankingProvider(json,clock,"../application"))
                .isInstanceOf(OpenBankingException.class);
    }
    @Test void originalAndGoldenTransactionWindowsDoNotOverlap() {
        var empty=mapper.transactions(golden.transactions(null,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),null).raw(),LocalDate.of(2026,9,1),LocalDate.of(2026,9,30));
        assertThat(empty.transactions()).isEmpty();assertThat(empty.hasNext()).isFalse();
        var original=new ReplayOpenBankingProvider(json,clock);
        var oldFirst=mapper.transactions(original.transactions(null,LocalDate.of(2026,9,1),LocalDate.of(2026,9,2),null).raw(),LocalDate.of(2026,9,1),LocalDate.of(2026,9,2));
        assertThat(oldFirst.transactions()).hasSize(25);assertThat(oldFirst.hasNext()).isTrue();
    }
}
