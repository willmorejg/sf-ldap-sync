package com.acmeinsurance.ldapsync.salesforce;

import org.apache.camel.component.salesforce.AuthenticationType;
import org.apache.camel.component.salesforce.SalesforceComponent;

import com.acmeinsurance.ldapsync.SyncConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Builds the "salesforce" Camel component from {@code sync.salesforce.*}.
 *
 * <p>Camel resolves this bean the first time a {@code salesforce:} endpoint is
 * used, so without Salesforce credentials the app still starts (CSV intake
 * works) and a Salesforce sync fails with a clear message in its report.
 * Configuring the component through {@code camel.component.salesforce.*}
 * instead would start it at boot, and it refuses to start without a client ID.
 */
@ApplicationScoped
public class SalesforceComponentProducer {

    @Produces
    @Singleton
    @Named("salesforce")
    SalesforceComponent salesforce(final SyncConfig cfg) {
        final SyncConfig.Salesforce sf = cfg.salesforce();
        final String clientId = sf.clientId().filter(s -> !s.isBlank()).orElseThrow(() -> new IllegalStateException(
                "Salesforce is enabled but SF_CLIENT_ID (sync.salesforce.client-id) is not set; "
                        + "set it, or set SF_ENABLED=false to run without Salesforce"));
        final SalesforceComponent c = new SalesforceComponent();
        c.setAuthenticationType(AuthenticationType.valueOf(sf.authenticationType()));
        c.setLoginUrl(sf.loginUrl());
        c.setClientId(clientId);
        sf.clientSecret().filter(s -> !s.isBlank()).ifPresent(c::setClientSecret);
        return c;
    }
}
