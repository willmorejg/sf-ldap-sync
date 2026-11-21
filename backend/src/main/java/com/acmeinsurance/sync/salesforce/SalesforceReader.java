package com.acmeinsurance.sync.salesforce;

import java.util.List;
import java.util.Map;

/** Runs a SOQL query and returns every record (all pages) as nested maps. */
public interface SalesforceReader {

    List<Map<String, Object>> query(String soql) throws Exception;
}
