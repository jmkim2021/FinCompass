package com.loanops.finance;

import java.math.BigDecimal;
import java.util.Objects;

/** Canonical annual interest rate is a decimal fraction. Source percentages stay unchanged in raw/product data. */
public record AnnualInterestRate(BigDecimal decimalFraction) {
    public AnnualInterestRate {
        Objects.requireNonNull(decimalFraction, "decimalFraction");
        if (decimalFraction.signum() < 0) throw new IllegalArgumentException("Annual interest rate must be non-negative");
        decimalFraction = decimalFraction.stripTrailingZeros();
    }
    public static AnnualInterestRate fromPercent(BigDecimal sourcePercent) {
        return new AnnualInterestRate(Objects.requireNonNull(sourcePercent).movePointLeft(2));
    }
    public static AnnualInterestRate fromDecimalFraction(BigDecimal fraction) {
        return new AnnualInterestRate(fraction);
    }
    public BigDecimal percent() { return decimalFraction.movePointRight(2); }
}
