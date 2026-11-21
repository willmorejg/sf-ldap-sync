package com.acmeinsurance.ldapsync;

import java.util.Map;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** All {@code sync.*} settings; see application.properties for documented values. */
@ConfigMapping(prefix = "sync")
public interface SyncConfig {

    Ldap ldap();

    Accounts accounts();

    Agents agents();

    Csv csv();

    Safety safety();

    /*
     * Schedule and CDC settings are read by the YAML routes through {{...}}
     * placeholders. They are declared here because every property under the
     * "sync" prefix must map, or config validation fails at startup.
     */
    Schedule schedule();

    Cdc cdc();

    Salesforce salesforce();

    /** Salesforce connection; see SalesforceComponentProducer. */
    interface Salesforce {
        /** false: the Salesforce routes are not created (no credentials needed). */
        @WithDefault("true")
        boolean enabled();

        @WithDefault("https://login.salesforce.com")
        String loginUrl();

        /** OAuth client credentials flow; JWT would need more settings. */
        @WithDefault("CLIENT_CREDENTIALS")
        String authenticationType();

        Optional<String> clientId();

        Optional<String> clientSecret();
    }

    interface Ldap {
        String url();

        String bindDn();

        String bindPassword();

        @WithDefault("4")
        int poolSize();

        @WithDefault("ou=people,dc=acmeinsurance,dc=com")
        String peopleDn();

        @WithDefault("ou=accounts,dc=acmeinsurance,dc=com")
        String accountsDn();

        @WithDefault("ou=agencies,ou=accounts,dc=acmeinsurance,dc=com")
        String agenciesDn();

        @WithDefault("ou=clients,ou=accounts,dc=acmeinsurance,dc=com")
        String clientsDn();

        @WithDefault("ou=features,dc=acmeinsurance,dc=com")
        String featuresDn();

        @WithDefault("ou=underwritingCompanies,dc=acmeinsurance,dc=com")
        String underwritingCompaniesDn();
    }

    /** Agency, agreement and book records (Account record types in Salesforce). */
    interface Accounts {
        String soql();

        @WithDefault("Id")
        String idField();

        @WithDefault("ParentId")
        String parentField();

        @WithDefault("RecordType.DeveloperName")
        String levelField();

        /** Business identifier used as cn, e.g. AccountNumber or a custom field. */
        @WithDefault("AccountNumber")
        String cnField();

        @WithDefault("Name")
        String nameField();

        /** Record-type value to level (agency, agreement or book). */
        Map<String, String> recordTypes();

        /**
         * Agreement field holding the name (cn) of its underwriting company, e.g.
         * Underwriting_Company__r.Name. Unset: the sync never touches an agreement's
         * acmeUnderwritingCompanyDN, so it can be maintained by hand.
         */
        Optional<String> underwritingCompanyField();
    }

    /** Brokers and agents (Salesforce User records). */
    interface Agents {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("ou=agents,ou=people,dc=acmeinsurance,dc=com")
        String ou();

        String soql();

        @WithDefault("Id")
        String idField();

        @WithDefault("Username")
        String uidField();

        @WithDefault("IsActive")
        String activeField();

        @WithDefault("ContactId")
        String contactField();

        /** User field holding the agent's own agency, e.g. Contact.AccountId. */
        Optional<String> agencyField();

        /** LDAP attribute to User field, e.g. mail=Email. */
        Map<String, String> attributes();

        /** Optional junction query giving extra agency/agreement/book grants per contact. */
        Optional<String> entitlementSoql();

        @WithDefault("ContactId")
        String entitlementContactField();

        @WithDefault("AccountId")
        String entitlementAccountField();

        /** Remove agency/agreement/book grants that Salesforce no longer lists. */
        @WithDefault("true")
        boolean pruneGrants();

        @WithDefault("cn=agents,ou=policies,dc=acmeinsurance,dc=com")
        String passwordPolicy();
    }

    interface Csv {
        @WithDefault("inbox")
        String inbox();

        @WithDefault("cn=internal,ou=policies,dc=acmeinsurance,dc=com")
        String internalPasswordPolicy();
    }

    interface Schedule {
        @WithDefault("0 15 2 * * ?")
        String cron();

        @WithDefault("true")
        boolean runOnStartup();
    }

    /** Salesforce Change Data Capture subscription. */
    interface Cdc {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("data/ChangeEvents")
        String channel();

        @WithDefault("120000")
        long debounceMs();
    }

    interface Safety {
        /** Largest share of a source's entries one run may delete or deactivate. */
        @WithDefault("10")
        int maxRemovePercent();

        /** Let a source take over matching entries that were created by hand. */
        @WithDefault("false")
        boolean adoptUnowned();

        /** Log every directory write instead of applying it. */
        @WithDefault("false")
        boolean dryRun();
    }
}
