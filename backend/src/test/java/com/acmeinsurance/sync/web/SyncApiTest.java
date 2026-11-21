package com.acmeinsurance.sync.web;

import static com.acmeinsurance.common.testsupport.TestDirectory.AGENTS;
import static com.acmeinsurance.common.testsupport.TestDirectory.DEMO_APP_SECRET;
import static com.acmeinsurance.common.testsupport.TestDirectory.FEATURES;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.acmeinsurance.common.testsupport.LdapTestResource;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The sync trigger needs an access token with sync: run-sync. The tests run with
 * SF_ENABLED=false, so a request that passes the check gets the "disabled" 503.
 */
@QuarkusTest
class SyncApiTest {

    private static final String PATH = "/sync/salesforce";
    private static final String PASSWORD = "Sync-test-Passw0rd!";
    private static final String RUN_SYNC = "cn=run-sync,cn=sync," + FEATURES;

    @BeforeAll
    static void users() throws Exception {
        agent("sync.runner", "acmeFeatureDN: " + RUN_SYNC);
        agent("sync.nobody", "acmeFeatureDN: cn=read-quotes,cn=quoting," + FEATURES);
    }

    private static void agent(final String uid, final String feature) throws Exception {
        if (LdapTestResource.directory().server().getEntry("uid=" + uid + "," + AGENTS) != null) {
            return;
        }
        LdapTestResource.directory().add("dn: uid=" + uid + "," + AGENTS, "objectClass: inetOrgPerson",
                "objectClass: acmeUser", "objectClass: acmeAccountAccess", "objectClass: acmeFeatureAccess",
                "uid: " + uid, "cn: " + uid, "sn: " + uid, "acmeUserType: agent", "userPassword: " + PASSWORD,
                feature);
    }

    private static String token(final String uid) {
        return given().auth().preemptive().basic("demo-app", DEMO_APP_SECRET)
                .formParam("grant_type", "password").formParam("username", uid).formParam("password", PASSWORD)
                .post("/oauth2/token").then().statusCode(200).extract().path("access_token");
    }

    @Test
    void withoutATokenTheTriggerAnswers401() {
        given().when().post(PATH).then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"))
                .body("error", equalTo("invalid_token"));
        given().header("Authorization", "Bearer not-a-jwt").when().get(PATH).then().statusCode(401)
                .header("WWW-Authenticate", containsString("error=\"invalid_token\""));
    }

    @Test
    void aTokenWithoutRunSyncIsRefused() {
        given().header("Authorization", "Bearer " + token("sync.nobody")).when().post(PATH).then().statusCode(403)
                .header("WWW-Authenticate", containsString("insufficient_scope"))
                .body("error", equalTo("insufficient_scope"));
    }

    @Test
    void aTokenWithRunSyncPassesTheCheck() {
        given().header("Authorization", "Bearer " + token("sync.runner")).when().post(PATH).then().statusCode(503)
                .body(containsString("disabled"));
    }

    @Test
    void theAuthorizationHeaderIsNotEchoed() {
        given().header("Authorization", "Bearer " + token("sync.runner")).when().get(PATH).then()
                .header("Authorization", org.hamcrest.Matchers.nullValue());
    }
}
