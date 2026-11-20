package com.acmeinsurance.ldapsync.core;

/**
 * One agency, agreement or book as read from Salesforce.
 *
 * @param sfId        18-character Salesforce ID
 * @param level       agency, agreement or book
 * @param cn          business identifier used as the entry's cn
 * @param parentSfId  18-character ID of the parent record; null for agencies
 * @param displayName human-readable name, may be null
 */
public record AccountNode(String sfId, AccountLevel level, String cn, String parentSfId, String displayName) {
}
