package com.acmeinsurance.ldapsync.salesforce;

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
        String json = template.requestBody("salesforce:query?rawPayload=true", soql, String.class);
        int pages = 1;
        while (true) {
            final JsonNode root = mapper.readTree(json);
            for (final JsonNode rec : root.path("records")) {
                records.add(mapper.convertValue(rec, MAP));
            }
            final String next = root.path("nextRecordsUrl").asText("");
            if (root.path("done").asBoolean(true) || next.isEmpty()) {
                break;
            }
            json = template.requestBody("salesforce:queryMore?rawPayload=true", next, String.class);
            pages++;
        }
        LOG.infof("Salesforce returned %d records in %d page(s) for: %s", records.size(), pages, soql);
        return records;
    }
}
