package com.acmeinsurance.sync.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class CoreLogicTest {

    @Test
    void convertsFifteenCharacterIds() {
        // Checksum worked by hand: uppercase positions per 5-char chunk select A..Z,0..5.
        assertEquals("001A0000006Vm9rIAC", SalesforceIds.to18("001A0000006Vm9r"));
        assertEquals("001000000000001AAA", SalesforceIds.to18("001000000000001"));
        assertEquals("001A0000006Vm9rIAC", SalesforceIds.to18("001A0000006Vm9rIAC"));
        assertThrows(IllegalArgumentException.class, () -> SalesforceIds.to18("short"));
    }

    @Test
    void readsNestedPaths() {
        final Map<String, Object> rec = new HashMap<>();
        rec.put("Contact", Map.of("AccountId", "001000000000001AAA"));
        rec.put("Blank", "  ");
        assertEquals("001000000000001AAA", Records.path(rec, "Contact.AccountId"));
        assertNull(Records.path(rec, "Contact.Missing"));
        assertNull(Records.path(rec, "Blank"));
        assertNull(Records.path(rec, "Contact"));
    }

    @Test
    void diffOnlyRemovesValuesInsideTheScope() {
        final String agencies = "ou=agencies,ou=accounts,dc=acmeinsurance,dc=com";
        final var existing = List.of("cn=AG1," + agencies, "cn=OLD," + agencies, "cn=100234,ou=clients,ou=accounts,dc=acmeinsurance,dc=com");
        final var desired = List.of("CN=AG1," + agencies, "cn=AG2," + agencies);

        final var d = ValueDiff.diff(existing, desired, v -> v.toLowerCase(Locale.ROOT).endsWith(agencies), true,
                v -> v.toLowerCase(Locale.ROOT));

        assertEquals(List.of("cn=AG2," + agencies), d.add());
        assertEquals(List.of("cn=OLD," + agencies), d.remove());

        final var noPrune = ValueDiff.diff(existing, desired, v -> true, false, v -> v.toLowerCase(Locale.ROOT));
        assertTrue(noPrune.remove().isEmpty());
    }

    @Test
    void guardRefusesEmptySourceAndLargeRemovals() {
        assertTrue(SafetyGuard.checkRemoval(0, 50, 50, 10).isPresent());
        assertTrue(SafetyGuard.checkRemoval(90, 100, 11, 10).isPresent());
        assertFalse(SafetyGuard.checkRemoval(90, 100, 10, 10).isPresent());
        assertFalse(SafetyGuard.checkRemoval(0, 0, 0, 10).isPresent());
    }

    @Test
    void agentMapperResolvesGrantsAndFillsRequiredNames() {
        final String user = "005000000000001AAA";
        final String contact = "003000000000001AAA";
        final String agency = "001000000000001AAA";
        final String book = "001000000000003AAA";
        final String unknown = "001000000000099AAA";

        final Map<String, Object> rec = new HashMap<>();
        rec.put("Id", user);
        rec.put("Username", "jdoe@agency.example");
        rec.put("LastName", "Doe");
        rec.put("IsActive", true);
        rec.put("ContactId", contact);
        rec.put("Contact", Map.of("AccountId", agency));

        final var ents = AgentMapper.indexEntitlements(List.of(
                Map.of("ContactId", contact, "AccountId", book),
                Map.of("ContactId", contact, "AccountId", unknown)), "ContactId", "AccountId");

        final Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("givenName", "FirstName");
        attrs.put("sn", "LastName");
        attrs.put("mail", "Email");
        final var mapping = new AgentMapper.Mapping("Id", "Username", "IsActive", "ContactId", "Contact.AccountId",
                attrs);
        final Map<String, String> dns = Map.of(agency, "cn=AG1,x", book, "cn=BK1,cn=AR1,cn=AG1,x");

        final var result = AgentMapper.map(List.of(rec), ents, mapping, dns::get);

        final PersonSpec p = result.people().get(0);
        assertEquals(Set.of("cn=AG1,x", "cn=BK1,cn=AR1,cn=AG1,x"), p.accountDns());
        assertEquals(List.of("Doe"), p.attributes().get("cn"));   // no first name: cn falls back to sn
        assertEquals(List.of(), p.attributes().get("mail"));      // empty means "remove"
        assertEquals(1, result.warnings().size());                // the unknown account
    }

    @Test
    void csvColumnsPresentReplaceAndAbsentAreLeftAlone() {
        final var header = CsvRowMapper.canonicalHeader(List.of("UID", "sn", "policynumbers", "Active"));
        assertEquals("policyNumbers", header.get("policynumbers"));
        assertThrows(IllegalArgumentException.class, () -> CsvRowMapper.canonicalHeader(List.of("uid", "surname")));
        assertThrows(IllegalArgumentException.class, () -> CsvRowMapper.canonicalHeader(List.of("sn")));

        final var row =
                CsvRowMapper.map(Map.of("uid", "m1001", "sn", "", "policyNumbers", "PA-1 | HO-2|", "active", "no"));
        assertEquals(List.of(), row.spec().attributes().get("sn"));
        assertFalse(row.spec().attributes().containsKey("mail"));
        assertEquals(Set.of("PA-1", "HO-2"), row.spec().policyNumbers());
        assertFalse(row.spec().active());
        assertNull(row.accountRefs());
    }
}
