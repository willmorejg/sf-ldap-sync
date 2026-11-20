package com.acmeinsurance.ldapsync.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class AccountTreePlannerTest {

    static final String BASE = "ou=agencies,ou=accounts,dc=acmeinsurance,dc=com";

    static final String AG = "001000000000001AAA";
    static final String AR = "001000000000002AAA";
    static final String BK = "001000000000003AAA";

    static AccountNode agency(final String id, final String cn) {
        return new AccountNode(id, AccountLevel.AGENCY, cn, null, null);
    }

    static AccountNode agreement(final String id, final String cn, final String parent) {
        return new AccountNode(id, AccountLevel.AGREEMENT, cn, parent, null);
    }

    static AccountNode book(final String id, final String cn, final String parent) {
        return new AccountNode(id, AccountLevel.BOOK, cn, parent, null);
    }

    @Test
    void placesParentsBeforeChildrenWhateverTheInputOrder() {
        final var plan = AccountTreePlanner.plan(List.of(book(BK, "BK-311", AR), agreement(AR, "AGR-01", AG), agency(AG, "AG0042")), BASE);

        assertEquals(List.of(AG, AR, BK), plan.ordered().stream().map(p -> p.node().sfId()).toList());
        assertEquals("cn=BK-311,cn=AGR-01,cn=AG0042," + BASE, plan.ordered().get(2).dn());
        assertTrue(plan.rejected().isEmpty());
    }

    @Test
    void rejectsMissingAndWrongLevelParentsAndTheirDescendants() {
        final String orphan = "001000000000009AAA";
        final var plan = AccountTreePlanner.plan(List.of(
                agency(AG, "AG0042"),
                book(BK, "BK-311", AG),                       // book directly under an agency
                agreement(orphan, "AGR-09", "001000000000777AAA"), // parent not in result
                book("001000000000010AAA", "BK-10", orphan)), BASE);

        assertEquals(1, plan.ordered().size());
        assertEquals(3, plan.rejected().size());
        assertTrue(plan.rejected().stream().anyMatch(r -> r.reason().contains("must sit under a agreement")));
        assertTrue(plan.rejected().stream().anyMatch(r -> r.reason().contains("was itself rejected")));
        // Rejected records still count as seen, so the sync will not delete their entries.
        assertEquals(4, plan.seenIds().size());
    }

    @Test
    void rejectsSiblingsWithTheSameCnButAllowsTheSameCnElsewhere() {
        final String ag2 = "001000000000004AAA";
        final var plan = AccountTreePlanner.plan(List.of(
                agency(AG, "AG0042"), agency(ag2, "AG0050"),
                agreement("001000000000005AAA", "AGR-01", AG),
                agreement("001000000000006AAA", "agr-01", AG),  // clashes (cn is case-insensitive)
                agreement("001000000000007AAA", "AGR-01", ag2)), BASE);

        assertEquals(4, plan.ordered().size());
        assertEquals(1, plan.rejected().size());
    }

    @Test
    void escapesSpecialCharactersInCn() {
        final var plan = AccountTreePlanner.plan(List.of(agency(AG, "Smith, Jones + Co")), BASE);
        assertEquals("cn=Smith\\, Jones \\+ Co," + BASE, plan.ordered().get(0).dn());
    }

    @Test
    void mapperSkipsUnknownRecordTypesAndFallsBackToIdForCn() {
        final var mapping = new AccountMapper.Mapping("Id", "ParentId", "RecordType.DeveloperName", "AccountNumber",
                "Name", Map.of("Agency", AccountLevel.AGENCY));
        final List<Map<String, Object>> rows = List.of(
                Map.of("Id", "001000000000001", "Name", "Example", "RecordType", Map.of("DeveloperName", "Agency")),
                Map.of("Id", "001000000000002", "RecordType", Map.of("DeveloperName", "Household")));

        final var result = AccountMapper.map(rows, mapping);

        assertEquals(1, result.nodes().size());
        assertEquals(1, result.skipped());
        assertEquals(SalesforceIds.to18("001000000000001"), result.nodes().get(0).cn());
        assertEquals(1, result.warnings().size());
    }
}
