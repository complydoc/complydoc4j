package io.github.complydoc4j.sensitive;

import io.github.complydoc4j.text.Py;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checksum and format validators, run after a pattern matches. A match that fails its
 * validators is discarded, so the counts in the report mean something.
 *
 * <p>Each is the Python package's validator of the same name, and is checked against it on
 * every value in the Python tests and a few thousand generated ones.
 */
public final class Validators {

    /**
     * Validators that check a shape rather than compute a check digit. A finding that passed
     * only these earns its tier from the label beside it, not {@code confirmed}.
     */
    public static final Set<String> FORMAT_ONLY = Set.of(
            "sort_code", "uk_postcode", "uk_phone", "plausible_dob", "in_pan", "card_issuer",
            "card_grouping", "dk_cpr", "e164_phone", "eu_vat", "nanp_phone", "ni_prefix",
            "us_ein", "us_ssn");

    public static final Map<String, Predicate<String>> VALIDATORS;

    static {
        Map<String, Predicate<String>> v = new LinkedHashMap<>();
        v.put("luhn", Validators::luhn);
        v.put("card_issuer", Validators::cardIssuer);
        v.put("iban_mod97", Validators::ibanMod97);
        v.put("ni_prefix", Validators::niPrefix);
        v.put("vat_mod97", Validators::vatMod97);
        v.put("sort_code", Validators::sortCode);
        v.put("uk_postcode", Validators::ukPostcode);
        v.put("uk_phone", Validators::ukPhone);
        v.put("plausible_dob", Validators::plausibleDob);
        v.put("us_ssn", Validators::usSsn);
        v.put("us_ein", Validators::usEin);
        v.put("aba_routing", Validators::abaRouting);
        v.put("nl_bsn", Validators::nlBsn);
        v.put("pt_nif", Validators::ptNif);
        v.put("es_dni", Validators::esDni);
        v.put("ie_pps", Validators::iePps);
        v.put("fr_nir", Validators::frNir);
        v.put("de_steuer_id", Validators::deSteuerId);
        v.put("eu_vat", Validators::euVat);
        v.put("card_grouping", Validators::cardGrouping);
        v.put("e164_phone", Validators::e164Phone);
        v.put("nanp_phone", Validators::nanpPhone);
        v.put("it_codice_fiscale", Validators::itCodiceFiscale);
        v.put("be_national_number", Validators::beNationalNumber);
        v.put("pl_pesel", Validators::plPesel);
        v.put("se_personnummer", Validators::sePersonnummer);
        v.put("dk_cpr", Validators::dkCpr);
        v.put("no_fnr", Validators::noFnr);
        v.put("ch_ahv", Validators::chAhv);
        v.put("br_cpf", Validators::brCpf);
        v.put("br_cnpj", Validators::brCnpj);
        v.put("in_aadhaar", Validators::inAadhaar);
        v.put("in_pan", Validators::inPan);
        v.put("ca_sin", Validators::caSin);
        v.put("au_tfn", Validators::auTfn);
        VALIDATORS = java.util.Collections.unmodifiableMap(v);
    }

    /** Whether every named validator passed, and the names of those that did, in order. */
    public record Outcome(boolean passed, List<String> names) {}

    private Validators() {}

    /** Run the named validators; an unknown name is skipped, as in Python. */
    public static Outcome validate(String value, List<String> names) {
        List<String> passed = new ArrayList<>();
        for (String name : names) {
            Predicate<String> check = VALIDATORS.get(name);
            if (check == null) {
                continue;
            }
            if (!check.test(value)) {
                return new Outcome(false, passed);
            }
            passed.add(name);
        }
        return new Outcome(true, passed);
    }

    // --- helpers ------------------------------------------------------------------

    private static String digits(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().filter(Py::isDigit)
                .forEach(c -> out.append((char) ('0' + Character.digit(c, 10))));
        return out.toString();
    }

    private static int d(String digits, int index) {
        return digits.charAt(index) - '0';
    }

    private static String noSpace(String value) {
        return String.join("", Py.split(value));
    }

    /** Digits as Python's {@code int()} reads them, ASCII or not. */
    private static boolean asciiDigits(String s) {
        return Py.allDigits(s);
    }

    private static java.math.BigInteger integer(String s) {
        return new java.math.BigInteger(digits(s));
    }

    private static boolean luhnValid(String digits) {
        int total = 0;
        for (int index = 0; index < digits.length(); index++) {
            int digit = d(digits, digits.length() - 1 - index);
            if (index % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }
        return total % 10 == 0;
    }

    private static int weightedMod(String digits, int[] weights, int modulus) {
        if (digits.length() != weights.length) {
            throw new IllegalArgumentException("lengths differ");
        }
        int total = 0;
        for (int i = 0; i < weights.length; i++) {
            total += d(digits, i) * weights[i];
        }
        return Math.floorMod(total, modulus);
    }

    private static int distinct(String s) {
        return (int) s.chars().distinct().count();
    }

    private static boolean plausibleDate(int day, int month) {
        return 1 <= month && month <= 12 && 1 <= day && day <= 31;
    }

    // --- cards ----------------------------------------------------------------------

    public static boolean luhn(String value) {
        String digits = digits(value);
        return 12 <= digits.length() && digits.length() <= 19 && luhnValid(digits);
    }

    private record Issuer(String low, String high, Set<Integer> lengths) {}

    private static Set<Integer> range(int from, int to) {
        Set<Integer> out = new java.util.HashSet<>();
        for (int i = from; i < to; i++) {
            out.add(i);
        }
        return out;
    }

    private static final List<Issuer> CARD_ISSUERS = List.of(
            new Issuer("4", "4", Set.of(13, 16, 19)),
            new Issuer("51", "55", Set.of(16)),
            new Issuer("2221", "2720", Set.of(16)),
            new Issuer("34", "34", Set.of(15)),
            new Issuer("37", "37", Set.of(15)),
            new Issuer("6011", "6011", range(16, 20)),
            new Issuer("644", "649", range(16, 20)),
            new Issuer("65", "65", range(16, 20)),
            new Issuer("300", "305", range(14, 20)),
            new Issuer("36", "36", range(14, 20)),
            new Issuer("38", "39", range(14, 20)),
            new Issuer("3528", "3589", range(16, 20)),
            new Issuer("62", "62", range(16, 20)),
            new Issuer("50", "50", range(12, 20)),
            new Issuer("56", "69", range(12, 20)),
            new Issuer("2200", "2204", range(16, 20)),
            new Issuer("60", "60", Set.of(16)),
            new Issuer("81", "82", Set.of(16)),
            new Issuer("508", "508", Set.of(16)),
            new Issuer("1", "1", Set.of(15)));

    public static boolean cardIssuer(String value) {
        String digits = digits(value);
        for (Issuer issuer : CARD_ISSUERS) {
            if (digits.length() < issuer.low().length()) {
                continue;
            }
            String prefix = digits.substring(0, issuer.low().length());
            if (issuer.low().compareTo(prefix) <= 0 && prefix.compareTo(issuer.high()) <= 0
                    && issuer.lengths().contains(digits.length())) {
                return true;
            }
        }
        return false;
    }

    private static final Set<List<Integer>> CARD_GROUPS = Set.of(
            List.of(4, 4, 4, 4), List.of(4, 6, 5), List.of(4, 6, 4), List.of(4, 4, 4, 4, 3),
            List.of(4, 4, 4, 1));

    public static boolean cardGrouping(String value) {
        List<Integer> groups = new ArrayList<>();
        for (String part : Py.strip(value).split("[ -]", -1)) {
            if (!part.isEmpty()) {
                groups.add(Py.len(part));
            }
        }
        long fours = groups.stream().filter(g -> g == 4).count();
        return groups.size() == 1 || CARD_GROUPS.contains(groups) || fours >= 3;
    }

    // --- UK -------------------------------------------------------------------------

    public static boolean ibanMod97(String value) {
        String cleaned = Py.upper(noSpace(value));
        int length = Py.len(cleaned);
        if (length < 15 || length > 34 || !Py.allAlpha(Py.head(cleaned, 2))
                || !Py.allDigits(Py.slice(cleaned, 2, 4))) {
            return false;
        }
        String rearranged = Py.slice(cleaned, 4, length) + Py.head(cleaned, 4);
        StringBuilder converted = new StringBuilder();
        for (int c : rearranged.codePoints().toArray()) {
            if (Py.isDigit(c)) {
                converted.append(Character.digit(c, 10));
            } else if (Py.isAlpha(c)) {
                converted.append(c - 55);
            } else {
                return false;
            }
        }
        try {
            return new java.math.BigInteger(converted.toString()).mod(java.math.BigInteger.valueOf(97))
                    .intValue() == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static final String NI_INVALID_FIRST = "DFIQUV";
    private static final String NI_INVALID_SECOND = "DFIOQUV";
    private static final Set<String> NI_RESERVED_PAIRS = Set.of("BG", "GB", "NK", "KN", "TN", "NT", "ZZ");

    public static boolean niPrefix(String value) {
        String cleaned = Py.upper(noSpace(value));
        if (Py.len(cleaned) < 2) {
            return false;
        }
        String first = Py.slice(cleaned, 0, 1);
        String second = Py.slice(cleaned, 1, 2);
        if (NI_INVALID_FIRST.contains(first) || NI_INVALID_SECOND.contains(second)) {
            return false;
        }
        return !NI_RESERVED_PAIRS.contains(Py.head(cleaned, 2));
    }

    public static boolean vatMod97(String value) {
        String digits = digits(value);
        if (digits.length() != 9 && digits.length() != 12) {
            return false;
        }
        int weighted = 0;
        int[] weights = {8, 7, 6, 5, 4, 3, 2};
        for (int i = 0; i < 7; i++) {
            weighted += d(digits, i) * weights[i];
        }
        int check = Integer.parseInt(digits.substring(7, 9));
        return (weighted + check) % 97 == 0 || (weighted + 55 + check) % 97 == 0;
    }

    public static boolean sortCode(String value) {
        String digits = digits(value);
        return digits.length() == 6 && distinct(digits) > 1;
    }

    private static final Pattern UK_POSTCODE = Pattern.compile(
            "^(GIR ?0AA|[A-PR-UWYZ]([0-9]{1,2}|([A-HK-Y][0-9]([0-9ABEHMNPRV-Y])?)"
                    + "|[0-9][A-HJKPS-UW]) ?[0-9][ABD-HJLNP-UW-Z]{2})$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean ukPostcode(String value) {
        // Python's `$` also matches before a final newline; strip() has removed any.
        return UK_POSTCODE.matcher(Py.strip(value)).find();
    }

    private static final List<String> UK_PHONE_PREFIXES = List.of("01", "02", "03", "05", "07", "08", "09");

    public static boolean ukPhone(String value) {
        String cleaned = noSpace(value).replace("-", "");
        if (cleaned.startsWith("+44")) {
            cleaned = "0" + cleaned.substring(3);
        }
        String digits = digits(cleaned);
        if (digits.length() != 10 && digits.length() != 11) {
            return false;
        }
        return UK_PHONE_PREFIXES.stream().anyMatch(digits::startsWith);
    }

    // --- dates of birth -------------------------------------------------------------

    private static final String DAY = "(3[01]|[12]\\d|0[1-9]|[1-9]| [1-9])";
    private static final String MONTH = "(1[0-2]|0[1-9]|[1-9])";
    private static final String YEAR = "(\\d\\d\\d\\d)";
    private static final String SHORT_YEAR = "(\\d\\d)";
    private static final List<String> MONTHS = List.of("january", "february", "march", "april",
            "may", "june", "july", "august", "september", "october", "november", "december");
    private static final String ABBREVIATED = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)";
    private static final String FULL = "(" + String.join("|", MONTHS) + ")";

    private enum Order { DMY, YMD }

    private record DateFormat(Pattern pattern, Order order, boolean shortYear, boolean named) {}

    /** The formats Python's {@code strptime} is tried with, in its order, as it reads them. */
    private static final List<DateFormat> DATE_FORMATS = List.of(
            format(DAY + "/" + MONTH + "/" + YEAR, Order.DMY, false, false),
            format(DAY + "-" + MONTH + "-" + YEAR, Order.DMY, false, false),
            format(DAY + "\\." + MONTH + "\\." + YEAR, Order.DMY, false, false),
            format(DAY + "/" + MONTH + "/" + SHORT_YEAR, Order.DMY, true, false),
            format(DAY + "\\s+" + ABBREVIATED + "\\s+" + YEAR, Order.DMY, false, true),
            format(DAY + "\\s+" + FULL + "\\s+" + YEAR, Order.DMY, false, true),
            format(YEAR + "-" + MONTH + "-" + DAY, Order.YMD, false, false));

    private static DateFormat format(String regex, Order order, boolean shortYear, boolean named) {
        return new DateFormat(Pattern.compile("(?i)" + regex), order, shortYear, named);
    }

    /** Overridable for tests: the day ages are measured from. */
    static java.util.function.Supplier<LocalDate> today = LocalDate::now;

    public static boolean plausibleDob(String value) {
        String text = Py.collapse(value);
        for (DateFormat format : DATE_FORMATS) {
            Matcher m = format.pattern().matcher(text);
            if (!m.matches()) {
                continue;
            }
            String dayText = format.order() == Order.DMY ? m.group(1) : m.group(3);
            String monthText = m.group(2);
            String yearText = format.order() == Order.DMY ? m.group(3) : m.group(1);
            int day = Integer.parseInt(dayText.trim());
            int month = format.named()
                    ? monthOf(monthText)
                    : Integer.parseInt(monthText);
            int year = Integer.parseInt(yearText);
            if (format.shortYear()) {
                year += year <= 68 ? 2000 : 1900;
            }
            LocalDate parsed;
            try {
                parsed = LocalDate.of(year, month, day);
            } catch (java.time.DateTimeException e) {
                continue;
            }
            double age = ChronoUnit.DAYS.between(parsed, today.get()) / 365.25;
            return 0 <= age && age <= 120;
        }
        return false;
    }

    private static int monthOf(String name) {
        String lower = Py.lower(name);
        for (int i = 0; i < MONTHS.size(); i++) {
            if (MONTHS.get(i).equals(lower) || MONTHS.get(i).startsWith(lower) && lower.length() == 3) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(name);
    }

    // --- other jurisdictions ----------------------------------------------------------

    public static boolean usSsn(String value) {
        String digits = digits(value);
        if (digits.length() != 9) {
            return false;
        }
        String area = digits.substring(0, 3);
        String group = digits.substring(3, 5);
        String serial = digits.substring(5);
        if (area.equals("000") || area.equals("666") || area.startsWith("9")) {
            return false;
        }
        return !group.equals("00") && !serial.equals("0000");
    }

    public static boolean usEin(String value) {
        String digits = digits(value);
        return digits.length() == 9
                && !Set.of("00", "07", "08", "09", "17", "18", "19").contains(digits.substring(0, 2));
    }

    public static boolean abaRouting(String value) {
        String digits = digits(value);
        return digits.length() == 9 && weightedMod(digits, new int[] {3, 7, 1, 3, 7, 1, 3, 7, 1}, 10) == 0;
    }

    public static boolean nlBsn(String value) {
        String digits = digits(value);
        if (digits.length() != 9 || digits.equals("000000000")) {
            return false;
        }
        int[] weights = {9, 8, 7, 6, 5, 4, 3, 2, -1};
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += d(digits, i) * weights[i];
        }
        return Math.floorMod(total, 11) == 0;
    }

    public static boolean ptNif(String value) {
        String digits = digits(value);
        if (digits.length() != 9 || "125689".indexOf(digits.charAt(0)) < 0) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 8; i++) {
            total += d(digits, i) * (9 - i);
        }
        int check = 11 - (total % 11);
        return d(digits, 8) == (check >= 10 ? 0 : check);
    }

    public static boolean esDni(String value) {
        String cleaned = Py.upper(noSpace(value)).replace("-", "");
        if (Py.len(cleaned) != 9) {
            return false;
        }
        String body = Py.head(cleaned, 8);
        String letter = Py.slice(cleaned, 8, 9);
        String first = Py.head(body, 1);
        if (first.equals("X") || first.equals("Y") || first.equals("Z")) {
            body = ("XYZ".indexOf(first)) + body.substring(1);
        }
        if (!asciiDigits(body) || !Py.allAlpha(letter)) {
            return false;
        }
        int index = integer(body).mod(java.math.BigInteger.valueOf(23)).intValue();
        return String.valueOf("TRWAGMYFPDXBNJZSQVHLCKE".charAt(index)).equals(letter);
    }

    public static boolean iePps(String value) {
        String cleaned = Py.upper(noSpace(value));
        int length = Py.len(cleaned);
        if ((length != 8 && length != 9) || !Py.allDigits(Py.head(cleaned, 7))) {
            return false;
        }
        String seven = digits(Py.head(cleaned, 7));
        int total = 0;
        for (int i = 0; i < 7; i++) {
            total += d(seven, i) * (8 - i);
        }
        if (length == 9 && Py.allAlpha(Py.slice(cleaned, 8, 9))) {
            total += (Py.slice(cleaned, 8, 9).codePointAt(0) - 64) * 9;
        }
        int index = Math.floorMod(total, 23);
        return String.valueOf("WABCDEFGHIJKLMNOPQRSTUV".charAt(index)).equals(Py.slice(cleaned, 7, 8));
    }

    public static boolean frNir(String value) {
        String cleaned = Py.upper(noSpace(value));
        String body = Py.head(cleaned, 13).replace("2A", "19").replace("2B", "18");
        String check = Py.slice(cleaned, 13, 15);
        if (!asciiDigits(body) || !asciiDigits(check)) {
            return false;
        }
        java.math.BigInteger ninetySeven = java.math.BigInteger.valueOf(97);
        return integer(check).intValue() == 97 - integer(body).mod(ninetySeven).intValue();
    }

    public static boolean deSteuerId(String value) {
        String digits = digits(value);
        if (digits.length() != 11) {
            return false;
        }
        String ten = digits.substring(0, 10);
        int most = 0;
        for (char c : ten.toCharArray()) {
            most = Math.max(most, (int) ten.chars().filter(x -> x == c).count());
        }
        if (most != 2 && most != 3) {
            return false;
        }
        int product = 10;
        for (int i = 0; i < 10; i++) {
            int total = (d(ten, i) + product) % 10;
            if (total == 0) {
                total = 10;
            }
            product = (2 * total) % 11;
        }
        int check = (11 - product) % 10;
        return check == d(digits, 10);
    }

    private static final Map<String, Pattern> EU_VAT_BODIES = new LinkedHashMap<>();

    static {
        String[][] bodies = {
            {"AT", "U\\d{8}"}, {"BE", "[01]\\d{9}"}, {"BG", "\\d{9,10}"}, {"CY", "\\d{8}[A-Z]"},
            {"CZ", "\\d{8,10}"}, {"DE", "\\d{9}"}, {"DK", "\\d{8}"}, {"EE", "\\d{9}"},
            {"EL", "\\d{9}"}, {"ES", "[A-Z0-9]\\d{7}[A-Z0-9]"}, {"FI", "\\d{8}"},
            {"FR", "[A-HJ-NP-Z0-9]{2}\\d{9}"}, {"HR", "\\d{11}"}, {"HU", "\\d{8}"},
            {"IE", "\\d{7}[A-Z]{1,2}|\\d[A-Z+*]\\d{5}[A-Z]"}, {"IT", "\\d{11}"},
            {"LT", "\\d{9}|\\d{12}"}, {"LU", "\\d{8}"}, {"LV", "\\d{11}"}, {"MT", "\\d{8}"},
            {"NL", "\\d{9}B\\d{2}"}, {"PL", "\\d{10}"}, {"PT", "\\d{9}"}, {"RO", "\\d{2,10}"},
            {"SE", "\\d{12}"}, {"SI", "\\d{8}"}, {"SK", "\\d{10}"},
        };
        for (String[] body : bodies) {
            EU_VAT_BODIES.put(body[0], Pattern.compile(body[1], Pattern.UNICODE_CHARACTER_CLASS));
        }
    }

    public static boolean euVat(String value) {
        String written = noSpace(value).replace("-", "");
        if (Py.len(written) < 4 || !Py.allAlpha(Py.head(written, 2)) || !Py.isUpper(Py.head(written, 2))) {
            return false;
        }
        String country = Py.head(written, 2);
        String body = Py.upper(Py.slice(written, 2, Py.len(written)));
        if (country.equals("GB")) {
            return (Py.len(body) == 9 || Py.len(body) == 12) && vatMod97(body);
        }
        Pattern shape = EU_VAT_BODIES.get(country);
        return shape != null && shape.matcher(body).matches();
    }

    private static final Set<String> TWO_DIGIT_COUNTRY_CODES = Set.of(
            "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45",
            "46", "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58", "60", "61",
            "62", "63", "64", "65", "66", "81", "82", "84", "86", "90", "91", "92", "93", "94",
            "95", "98");

    private static final Pattern LEAD = Pattern.compile("\\+(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);

    private static boolean countryCodeIsWhole(String value) {
        Matcher lead = LEAD.matcher(Py.strip(value));
        if (!lead.lookingAt()) {
            return false;
        }
        String first = lead.group(1);
        int length = Py.len(first);
        if (length > 3) {
            return true;
        }
        if (length == 1) {
            return first.equals("1") || first.equals("7");
        }
        if (length == 2) {
            return TWO_DIGIT_COUNTRY_CODES.contains(first);
        }
        return "17".indexOf(first.charAt(0)) < 0 && !TWO_DIGIT_COUNTRY_CODES.contains(Py.head(first, 2));
    }

    public static boolean e164Phone(String value) {
        String digits = digits(value);
        return Py.strip(value).startsWith("+")
                && 8 <= digits.length() && digits.length() <= 15
                && !digits.startsWith("44")
                && countryCodeIsWhole(value);
    }

    public static boolean nanpPhone(String value) {
        String digits = digits(value);
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }
        return digits.length() == 10 && "23456789".indexOf(digits.charAt(0)) >= 0
                && "23456789".indexOf(digits.charAt(3)) >= 0;
    }

    private static final String CF_KEYS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[] CF_ODD = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 1, 0, 5, 7, 9, 13,
        15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};

    public static boolean itCodiceFiscale(String value) {
        String code = Py.upper(noSpace(value));
        if (Py.len(code) != 16 || !Py.allAlnum(code)) {
            return false;
        }
        int[] chars = code.codePoints().toArray();
        int total = 0;
        for (int position = 0; position < 15; position++) {
            int c = chars[position];
            if (position % 2 == 0) {
                int key = c < 128 ? CF_KEYS.indexOf(c) : -1;
                if (key < 0) {
                    return false;
                }
                total += CF_ODD[key];
            } else {
                total += Py.isDigit(c) ? Character.digit(c, 10) : c - 65;
            }
        }
        return total % 26 + 65 == chars[15];
    }

    public static boolean beNationalNumber(String value) {
        String digits = digits(value);
        if (digits.length() != 11) {
            return false;
        }
        long body = Long.parseLong(digits.substring(0, 9));
        int check = Integer.parseInt(digits.substring(9));
        long withTwo = Long.parseLong("2" + digits.substring(0, 9));
        return check == 97 - body % 97 || check == 97 - withTwo % 97;
    }

    public static boolean plPesel(String value) {
        String digits = digits(value);
        if (digits.length() != 11) {
            return false;
        }
        int total = weightedMod(digits.substring(0, 10), new int[] {1, 3, 7, 9, 1, 3, 7, 9, 1, 3}, 10);
        return (10 - total) % 10 == d(digits, 10);
    }

    public static boolean sePersonnummer(String value) {
        String digits = digits(value);
        if (digits.length() == 12) {
            digits = digits.substring(2);
        }
        if (digits.length() != 10) {
            return false;
        }
        int day = Integer.parseInt(digits.substring(4, 6));
        return plausibleDate(day > 60 ? day - 60 : day, Integer.parseInt(digits.substring(2, 4)))
                && luhnValid(digits);
    }

    public static boolean dkCpr(String value) {
        String digits = digits(value);
        return digits.length() == 10
                && plausibleDate(Integer.parseInt(digits.substring(0, 2)), Integer.parseInt(digits.substring(2, 4)));
    }

    public static boolean noFnr(String value) {
        String digits = digits(value);
        if (digits.length() != 11) {
            return false;
        }
        int day = Integer.parseInt(digits.substring(0, 2));
        int month = Integer.parseInt(digits.substring(2, 4));
        if (!plausibleDate(day > 40 ? day - 40 : day, month)) {
            return false;
        }
        int[] w1 = {3, 7, 6, 1, 8, 9, 4, 5, 2};
        int[] w2 = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int r1 = weightedMod(digits.substring(0, 9), w1, 11);
        int k1 = r1 == 0 ? 0 : 11 - r1;
        if (k1 != d(digits, 9)) {
            return false;
        }
        int r2 = weightedMod(digits.substring(0, 10), w2, 11);
        int k2 = r2 == 0 ? 0 : 11 - r2;
        return k2 == d(digits, 10);
    }

    public static boolean chAhv(String value) {
        String digits = digits(value);
        if (digits.length() != 13 || !digits.startsWith("756")) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 12; i++) {
            total += d(digits, i) * (i % 2 == 1 ? 3 : 1);
        }
        return (10 - total % 10) % 10 == d(digits, 12);
    }

    private static int mod11Digit(String digits, int[] weights) {
        int remainder = weightedMod(digits, weights, 11);
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static int[] descending(int from, int length) {
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            out[i] = from - i;
        }
        return out;
    }

    public static boolean brCpf(String value) {
        String digits = digits(value);
        if (digits.length() != 11 || distinct(digits) == 1) {
            return false;
        }
        int first = mod11Digit(digits.substring(0, 9), descending(10, 9));
        int second = mod11Digit(digits.substring(0, 10), descending(11, 10));
        return digits.substring(9).equals("" + first + second);
    }

    public static boolean brCnpj(String value) {
        String digits = digits(value);
        if (digits.length() != 14 || distinct(digits) == 1) {
            return false;
        }
        int first = mod11Digit(digits.substring(0, 12), new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int second = mod11Digit(digits.substring(0, 13), new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return digits.substring(12).equals("" + first + second);
    }

    private static final int[][] VERHOEFF_D = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
        {2, 3, 4, 0, 1, 7, 8, 9, 5, 6}, {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
        {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
        {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
        {8, 7, 6, 5, 9, 3, 2, 1, 0, 4}, {9, 8, 7, 6, 5, 4, 3, 2, 1, 0},
    };
    private static final int[][] VERHOEFF_P = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
        {5, 8, 0, 3, 7, 9, 6, 1, 4, 2}, {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
        {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
        {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8},
    };

    static boolean verhoeff(String value) {
        String digits = digits(value);
        int checksum = 0;
        for (int position = 0; position < digits.length(); position++) {
            int digit = d(digits, digits.length() - 1 - position);
            checksum = VERHOEFF_D[checksum][VERHOEFF_P[position % 8][digit]];
        }
        return checksum == 0;
    }

    public static boolean inAadhaar(String value) {
        String digits = digits(value);
        return digits.length() == 12 && "01".indexOf(digits.charAt(0)) < 0 && verhoeff(digits);
    }

    public static boolean inPan(String value) {
        String code = Py.upper(noSpace(value));
        return Py.len(code) == 10
                && Py.allAlpha(Py.head(code, 5))
                && Py.allDigits(Py.slice(code, 5, 9))
                && Py.allAlpha(Py.slice(code, 9, 10))
                && "ABCFGHJLPT".contains(Py.slice(code, 3, 4));
    }

    public static boolean caSin(String value) {
        String digits = digits(value);
        return digits.length() == 9 && "08".indexOf(digits.charAt(0)) < 0 && luhnValid(digits);
    }

    public static boolean auTfn(String value) {
        String digits = digits(value);
        return digits.length() == 9 && weightedMod(digits, new int[] {1, 4, 3, 7, 5, 8, 6, 9, 10}, 11) == 0;
    }
}
