package com.loanops.fincompass.simulation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.RoundingMode;
import static com.loanops.fincompass.simulation.SimulationModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialCalculationAuditTest {
    private final SimulationEngine engine=new SimulationEngine();

    @Test void amortizingPaymentMatchesIndependentClosedFormReference(){
        double principal=590_000_000d,monthlyRate=.045d/12d;int months=360;
        double independent=principal*monthlyRate/(1d-Math.pow(1d+monthlyRate,-months));
        BigDecimal expected=BigDecimal.valueOf(independent).setScale(2,RoundingMode.HALF_EVEN);
        LoanResult actual=engine.loan(new LoanInput(new BigDecimal("590000000"),new BigDecimal(".045"),months,RepaymentType.AMORTIZING),null);
        assertThat(expected).isEqualByComparingTo("2989443.33");assertThat(actual.regularMonthlyPayment()).isEqualByComparingTo(expected);
        assertThat(actual.schedule().getLast().closingBalance()).isEqualByComparingTo("0.00");
        assertThat(actual.totalRepayment().subtract(actual.input().principal())).isEqualByComparingTo(actual.totalInterest());
    }
}
