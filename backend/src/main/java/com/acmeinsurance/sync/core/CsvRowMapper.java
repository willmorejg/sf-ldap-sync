package com.acmeinsurance.sync.core;

import static com.acmeinsurance.common.Constants.CN;
import static com.acmeinsurance.common.Constants.CSV_COLUMN_ACCOUNTS;
import static com.acmeinsurance.common.Constants.CSV_COLUMN_ACTIVE;
import static com.acmeinsurance.common.Constants.CSV_COLUMN_FEATURES;
import static com.acmeinsurance.common.Constants.CSV_COLUMN_POLICY_NUMBERS;
import static com.acmeinsurance.common.Constants.DEPARTMENT_NUMBER;
import static com.acmeinsurance.common.Constants.DISPLAY_NAME;
import static com.acmeinsurance.common.Constants.EMPLOYEE_NUMBER;
import static com.acmeinsurance.common.Constants.GIVEN_NAME;
import static com.acmeinsurance.common.Constants.MAIL;
import static com.acmeinsurance.common.Constants.MOBILE;
import static com.acmeinsurance.common.Constants.SN;
import static com.acmeinsurance.common.Constants.TELEPHONE_NUMBER;
import static com.acmeinsurance.common.Constants.TITLE;
import static com.acmeinsurance.common.Constants.UID;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps one CSV row to a {@link PersonSpec}.
 *
 * <p>Column rules: {@code uid} is required. A column that is present replaces
 * the attribute (an empty cell removes it); a column that is absent leaves the
 * attribute untouched. Multiple values are separated with {@code |}.
 */
public final class CsvRowMapper {

    /** Multi-valued cells separate their values with "|". */
    private static final String VALUE_SEPARATOR = "\\|";
    /** Accepted spellings of the active column (any case); empty means true. */
    private static final String TRUE = "true";
    private static final String YES = "yes";
    private static final String Y = "y";
    private static final String ONE = "1";
    private static final String FALSE = "false";
    private static final String NO = "no";
    private static final String N = "n";
    private static final String ZERO = "0";

    /** Person attributes a CSV may set, in their canonical LDAP spelling. */
    public static final List<String> ATTRIBUTES = List.of(GIVEN_NAME, SN, CN, MAIL, TELEPHONE_NUMBER,
            MOBILE, TITLE, DISPLAY_NAME, EMPLOYEE_NUMBER, DEPARTMENT_NUMBER);
    public static final List<String> CONTROL_COLUMNS = List.of(UID, CSV_COLUMN_ACTIVE, CSV_COLUMN_ACCOUNTS,
            CSV_COLUMN_POLICY_NUMBERS, CSV_COLUMN_FEATURES);

    /**
     * @param accountRefs grant references (sf:ID, client:CN or a full DN) still to be
     *                    resolved; null when the accounts column is absent
     * @param featureRefs feature references (feature, feature/permission or a full DN)
     *                    still to be resolved; null when the features column is absent
     */
    public record Row(PersonSpec spec, Set<String> accountRefs, Set<String> featureRefs) {
    }

    private CsvRowMapper() {
    }

    /**
     * Validates a header and maps each column to its canonical name.
     *
     * @throws IllegalArgumentException for an unknown column or a missing uid column
     */
    public static Map<String, String> canonicalHeader(final Collection<String> header) {
        final Map<String, String> known = new LinkedHashMap<>();
        for (final String name : ATTRIBUTES) {
            known.put(name.toLowerCase(Locale.ROOT), name);
        }
        for (final String name : CONTROL_COLUMNS) {
            known.put(name.toLowerCase(Locale.ROOT), name);
        }

        final Map<String, String> out = new LinkedHashMap<>();
        for (final String col : header) {
            final String canonical = known.get(col.trim().toLowerCase(Locale.ROOT));
            if (canonical == null) {
                throw new IllegalArgumentException("Unknown CSV column '" + col + "'. Allowed: " + known.values());
            }
            out.put(col, canonical);
        }
        if (!out.containsValue(UID)) {
            throw new IllegalArgumentException("CSV has no uid column");
        }
        return out;
    }

    /** @param row canonical column name to cell value, for the columns present in the file */
    public static Row map(final Map<String, String> row) {
        final String uid = trimToNull(row.get(UID));
        if (uid == null) {
            throw new IllegalArgumentException("uid is empty");
        }
        final boolean active = !row.containsKey(CSV_COLUMN_ACTIVE) || parseBoolean(row.get(CSV_COLUMN_ACTIVE));
        final Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (final String attr : ATTRIBUTES) {
            if (row.containsKey(attr)) {
                attrs.put(attr, List.copyOf(split(row.get(attr))));
            }
        }
        final Set<String> accounts = row.containsKey(CSV_COLUMN_ACCOUNTS) ? split(row.get(CSV_COLUMN_ACCOUNTS)) : null;
        final Set<String> policies = row.containsKey(CSV_COLUMN_POLICY_NUMBERS)
                ? split(row.get(CSV_COLUMN_POLICY_NUMBERS)) : null;
        final Set<String> features = row.containsKey(CSV_COLUMN_FEATURES) ? split(row.get(CSV_COLUMN_FEATURES)) : null;
        return new Row(new PersonSpec(uid, null, active, attrs, null, policies), accounts, features);
    }

    static Set<String> split(final String cell) {
        if (cell == null) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(cell.split(VALUE_SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static boolean parseBoolean(final String v) {
        final String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "", TRUE, YES, Y, ONE -> true;
            case FALSE, NO, N, ZERO -> false;
            default -> throw new IllegalArgumentException("active must be true or false, not '" + v + "'");
        };
    }

    private static String trimToNull(final String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
