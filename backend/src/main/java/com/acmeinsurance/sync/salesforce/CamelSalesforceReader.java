package com.acmeinsurance.sync.salesforce;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Queries Salesforce through camel-salesforce, which handles OAuth and token
 * refresh. Raw JSON is used so no generated DTOs are needed; paging follows
 * nextRecordsUrl until Salesforce reports done.
 */
@ApplicationScoped
public class CamelSalesforceReader implements SalesforceReader {

    /** Camel endpoints: the first page of a SOQL query, then each following page by its URL. */
    private static final String QUERY = "salesforce:query?rawPayload=true";
    private static final String QUERY_MORE = "salesforce:queryMore?rawPayload=true";
    /** Fields of a Salesforce REST query response. */
    private static final String RECORDS = "records";
    private static final String NEXT_RECORDS_URL = "nextRecordsUrl";
    private static final String DONE = "done";

    private static final Logger LOG = Logger.getLogger(CamelSalesforceReader.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ProducerTemplate template;
    private final ObjectMapper mapper;

    public CamelSalesforceReader(final ProducerTemplate template, final ObjectMapper mapper) {
        this.template = template;
        this.mapper = mapper;
    }

    @Override
    public List<Map<String, Object>> query(final String soql) throws Exception {
        final List<Map<String, Object>> records = new ArrayList<>();
        // The SOQL (and, for queryMore, the nextRecordsUrl) is read from the body
        // when no sObjectQuery header or option is set.
        String json = template.requestBody(QUERY, soql, String.class);
        int pages = 1;
        while (true) {
            final JsonNode root = mapper.readTree(json);
            for (final JsonNode rec : root.path(RECORDS)) {
                records.add(mapper.convertValue(rec, MAP));
            }
            final String next = root.path(NEXT_RECORDS_URL).asText("");
            if (root.path(DONE).asBoolean(true) || next.isEmpty()) {
                break;
            }
            json = template.requestBody(QUERY_MORE, next, String.class);
            pages++;
        }
        LOG.infof("Salesforce returned %d records in %d page(s) for: %s", records.size(), pages, soql);
        return records;
    }
}
