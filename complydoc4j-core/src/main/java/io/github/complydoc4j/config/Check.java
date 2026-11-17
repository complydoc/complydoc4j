package io.github.complydoc4j.config;

import java.util.List;
import java.util.Map;

/**
 * A check digit declared in {@code sensitive.yaml} under {@code checks}, named in a
 * category's validators like one written in code. Python's {@code CheckConfig}.
 *
 * @param lengths how many digits the number may have
 * @param startsWith what its digits may start with; anything when empty
 * @param method {@code weighted} or {@code luhn}
 * @param weights one weight per digit, from the first
 * @param modulus what the weighted sum is taken modulo
 * @param zero the sum of the whole number is a multiple of {@code modulus}
 * @param subtractFrom the check digit is this minus the remainder; null for the remainder itself
 * @param replace results written as another digit
 * @param invalid results no valid number has
 */
public record Check(List<Integer> lengths, List<String> startsWith, String method, List<Integer> weights,
        int modulus, boolean zero, Integer subtractFrom, Map<Integer, Integer> replace, List<Integer> invalid) {

    public Check {
        lengths = List.copyOf(lengths);
        startsWith = List.copyOf(startsWith);
        weights = List.copyOf(weights);
        replace = Map.copyOf(replace);
        invalid = List.copyOf(invalid);
    }
}
