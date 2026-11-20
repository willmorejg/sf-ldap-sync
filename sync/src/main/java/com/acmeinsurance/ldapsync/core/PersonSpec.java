package com.acmeinsurance.ldapsync.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a source says one person's directory entry should look like.
 *
 * @param uid           login name; becomes the entry's RDN
 * @param sfId          18-character Salesforce User ID, or null for non-Salesforce sources
 * @param active        false locks the entry
 * @param attributes    attributes this source owns; each is replaced, and an empty list removes it
 * @param accountDns    acmeAccountDN grants inside the source's scope; null leaves grants untouched
 * @param policyNumbers acmePolicyNumber grants; null leaves them untouched
 * @param featureDns    acmeFeatureDN grants (features or permissions); null leaves them untouched
 */
public record PersonSpec(String uid, String sfId, boolean active, Map<String, List<String>> attributes,
                         Set<String> accountDns, Set<String> policyNumbers, Set<String> featureDns) {

    /** A spec that leaves feature grants alone (the Salesforce sync never manages them). */
    public PersonSpec(String uid, String sfId, boolean active, Map<String, List<String>> attributes,
                      Set<String> accountDns, Set<String> policyNumbers) {
        this(uid, sfId, active, attributes, accountDns, policyNumbers, null);
    }

    public PersonSpec withAccountDns(Set<String> dns) {
        return new PersonSpec(uid, sfId, active, attributes, dns, policyNumbers, featureDns);
    }

    public PersonSpec withFeatureDns(Set<String> dns) {
        return new PersonSpec(uid, sfId, active, attributes, accountDns, policyNumbers, dns);
    }
}
