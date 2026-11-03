package io.github.efecaginyildirim.collector.dataset;

import io.github.efecaginyildirim.collector.model.ComputedFeature;

import java.util.Objects;

/**
 * "Exclude rows where {@code feature op value}", e.g. {@code IDLE > 0.5} to drop samples taken
 * while the machine was mostly idle.
 *
 * <p>A row whose value for {@code feature} is unknown does not match the rule and is kept; use
 * {@link DatasetSpec#dropIncomplete()} to drop such rows.
 *
 * @param feature feature to test; it does not have to be among the exported columns
 * @param op      comparison
 * @param value   threshold, finite
 */
public record ExcludeRule(ComputedFeature feature, Comparison op, double value) {

    /** Comparison operators. */
    public enum Comparison {
        /** Greater than. */
        GT(">"),
        /** Greater than or equal. */
        GE(">="),
        /** Less than. */
        LT("<"),
        /** Less than or equal. */
        LE("<=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        /** @return the operator as written, e.g. {@code >=} */
        public String symbol() {
            return symbol;
        }

        boolean test(double left, double right) {
            return switch (this) {
                case GT -> left > right;
                case GE -> left >= right;
                case LT -> left < right;
                case LE -> left <= right;
            };
        }

        /**
         * @param symbol {@code >}, {@code >=}, {@code <} or {@code <=}
         * @return the operator
         * @throws IllegalArgumentException for anything else
         */
        public static Comparison fromSymbol(String symbol) {
            for (Comparison c : values()) {
                if (c.symbol.equals(symbol)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("unknown comparison '" + symbol + "'");
        }
    }

    public ExcludeRule {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(op, "op");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("rule value must be a finite number");
        }
    }

    /**
     * @param actual the row's value, or {@code null} if unknown
     * @return whether the row must be excluded
     */
    public boolean excludes(Double actual) {
        return actual != null && op.test(actual, value);
    }

    @Override
    public String toString() {
        return feature.apiName() + " " + op.symbol() + " " + value;
    }
}
