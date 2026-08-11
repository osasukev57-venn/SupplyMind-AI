package com.supplymind.day4.foundation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Test-scope reference arithmetic for Day 4 golden contracts.  It is deliberately independent
 * from production calculators: it never writes a business file and cannot make a future material
 * workflow pass.
 */
final class GoldenArithmeticHarness {

    private GoldenArithmeticHarness() {
    }

    static BigDecimal decimal(String lexical) {
        Objects.requireNonNull(lexical, "lexical");
        if (lexical.contains("e") || lexical.contains("E")) {
            throw new IllegalArgumentException("golden decimal must not use scientific notation: " + lexical);
        }
        return new BigDecimal(lexical);
    }

    static Summary summarize(List<String> lexicalValues, int calculationScale, RoundingMode roundingMode) {
        if (lexicalValues == null || lexicalValues.isEmpty()) {
            throw new IllegalArgumentException("missing is not zero: a summary needs at least one valid value");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (String lexicalValue : lexicalValues) {
            sum = sum.add(decimal(lexicalValue));
        }
        BigDecimal average = sum.divide(BigDecimal.valueOf(lexicalValues.size()), calculationScale, roundingMode);
        return new Summary(sum, lexicalValues.size(), average);
    }

    record Summary(BigDecimal sum, int validCount, BigDecimal average) {
        String sumText() {
            return sum.toPlainString();
        }

        String averageText() {
            return average.toPlainString();
        }
    }
}
