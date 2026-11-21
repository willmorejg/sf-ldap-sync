package com.acmeinsurance.ldapsync.core;

/**
 * One agency, agreement or book as read from Salesforce.
 *
 * @param sfId        18-character Salesforce ID
 * @param level       agency, agreement or book
 * @param cn          business identifier used as the entry's cn
 * @param parentSfId  18-character ID of the parent record; null for agencies
 * @param displayName human-readable name, may be null
 * @param underwritingCompany name (cn) of an agreement's underwriting company; null
 *                    when it has none, for agencies and books, and when the sync
 *                    does not manage underwriting companies
 */
public record AccountNode(String sfId, AccountLevel level, String cn, String parentSfId, String displayName,
                          String underwritingCompany) {
}
