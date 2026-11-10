package io.github.complydoc4j.evals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code difflib.SequenceMatcher} on strings, with no junk and {@code autojunk}
 * off, as complydoc scores a near miss: the same matching blocks, so the same ratios to the
 * last digit. Strings are compared by code point.
 */
final class SequenceMatcher {

    private int[] a = new int[0];
    private int[] b = new int[0];
    private Map<Integer, List<Integer>> b2j = new HashMap<>();
    private Map<Integer, Integer> fullBCount;
    private Integer matches;

    void setSeq1(String text) {
        a = text.codePoints().toArray();
        matches = null;
    }

    void setSeq2(String text) {
        b = text.codePoints().toArray();
        b2j = new HashMap<>();
        for (int i = 0; i < b.length; i++) {
            b2j.computeIfAbsent(b[i], k -> new ArrayList<>()).add(i);
        }
        fullBCount = null;
        matches = null;
    }

    /** An upper bound on {@link #ratio()}, from the lengths alone. */
    double realQuickRatio() {
        return ratioOf(Math.min(a.length, b.length));
    }

    /** An upper bound on {@link #ratio()}, from the characters in common, in any order. */
    double quickRatio() {
        if (fullBCount == null) {
            fullBCount = new HashMap<>();
            for (int c : b) {
                fullBCount.merge(c, 1, Integer::sum);
            }
        }
        Map<Integer, Integer> avail = new HashMap<>();
        int found = 0;
        for (int c : a) {
            int left = avail.containsKey(c) ? avail.get(c) : fullBCount.getOrDefault(c, 0);
            avail.put(c, left - 1);
            if (left > 0) {
                found++;
            }
        }
        return ratioOf(found);
    }

    double ratio() {
        if (matches == null) {
            matches = matchingCharacters();
        }
        return ratioOf(matches);
    }

    private double ratioOf(int found) {
        int length = a.length + b.length;
        return length == 0 ? 1.0 : 2.0 * found / length;
    }

    /** The characters in the matching blocks, as {@code get_matching_blocks} finds them. */
    private int matchingCharacters() {
        int total = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[] {0, a.length, 0, b.length});
        while (!queue.isEmpty()) {
            int[] range = queue.pop();
            int[] match = longestMatch(range[0], range[1], range[2], range[3]);
            int size = match[2];
            if (size > 0) {
                total += size;
                if (range[0] < match[0] && range[2] < match[1]) {
                    queue.push(new int[] {range[0], match[0], range[2], match[1]});
                }
                if (match[0] + size < range[1] && match[1] + size < range[3]) {
                    queue.push(new int[] {match[0] + size, range[1], match[1] + size, range[3]});
                }
            }
        }
        return total;
    }

    /** {@code find_longest_match} with no junk: the earliest of the longest matching runs. */
    private int[] longestMatch(int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            for (int j : b2j.getOrDefault(a[i], List.of())) {
                if (j < blo) {
                    continue;
                }
                if (j >= bhi) {
                    break;
                }
                int k = j2len.getOrDefault(j - 1, 0) + 1;
                newj2len.put(j, k);
                if (k > bestsize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestsize = k;
                }
            }
            j2len = newj2len;
        }
        // With no junk, Python's extension past junk elements changes nothing.
        return new int[] {besti, bestj, bestsize};
    }
}
