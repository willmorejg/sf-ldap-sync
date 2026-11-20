package com.acmeinsurance.auth;

import static com.acmeinsurance.auth.testsupport.TestDirectory.AGENCIES;
import static com.acmeinsurance.auth.testsupport.TestDirectory.AGENTS;
import static com.acmeinsurance.auth.testsupport.TestDirectory.CLIENT_ACCOUNTS;
import static com.acmeinsurance.auth.testsupport.TestDirectory.DEMO_APP_SECRET;
import static com.acmeinsurance.auth.testsupport.TestDirectory.FEATURES;
import static com.acmeinsurance.auth.testsupport.TestDirectory.MEMBERS;
import static com.acmeinsurance.auth.testsupport.TestDirectory.INTERNAL;
import static com.acmeinsurance.auth.testsupport.TestDirectory.OTHER;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.junit.jupiter.api.Test;

import com.acmeinsurance.auth.testsupport.LdapTestResource;
import com.acmeinsurance.auth.testsupport.TestDirectory;
import com.acmeinsurance.auth.token.Pkce;
import com.unboundid.util.StaticUtils;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

@QuarkusTest
@QuarkusTestResource(LdapTestResource.class)
class AuthFlowTest {

    private static final String PASSWORD = "Agent-Test-Password-1";
    private static final String REDIRECT = "http://127.0.0.1:8085/callback";
    private static final String AG0042 = "cn=AG0042," + AGENCIES;
    private static final String AGR_01 = "cn=AGR-0042-01," + AG0042;
    private static final String BK_340 = "cn=BK-000340,cn=AGR-0042-02," + AG0042;
    private static final String QUOTING = "cn=quoting," + FEATURES;
    private static final String READ_QUOTES = "cn=read-quotes," + QUOTING;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static TestDirectory ldap() {
        return LdapTestResource.directory();
    }

    /** Adds an agent with a password and the given feature and account grants. */
    private static void agent(String uid, List<String> features, List<String> accounts, String... extra)
            throws Exception {
        List<String> lines = new ArrayList<>(List.of("dn: uid=" + uid + "," + AGENTS,
                "objectClass: inetOrgPerson", "objectClass: acmeUser", "objectClass: acmeAccountAccess",
                "objectClass: acmeFeatureAccess", "uid: " + uid, "cn: Test " + uid, "sn: " + uid,
                "mail: " + uid + "@agency.example", "acmeUserType: agent", "userPassword: " + PASSWORD));
        features.forEach(f -> lines.add("acmeFeatureDN: " + f));
        accounts.forEach(a -> lines.add("acmeAccountDN: " + a));
        lines.addAll(List.of(extra));
        ldap().add(lines.toArray(String[]::new));
    }

    private static Response passwordGrant(String username, String password) {
        return given().auth().preemptive().basic("demo-app", DEMO_APP_SECRET)
                .formParam("grant_type", "password")
                .formParam("username", username)
                .formParam("password", password)
                .post("/oauth2/token");
    }

    private static Response refresh(String refreshToken, String clientId, String secret) {
        var req = given();
        if (secret != null) {
            req = req.auth().preemptive().basic(clientId, secret);
        } else {
            req = req.formParam("client_id", clientId);
        }
        return req.formParam("grant_type", "refresh_token").formParam("refresh_token", refreshToken)
                .post("/oauth2/token");
    }

    private static JwtClaims verify(String accessToken) throws Exception {
        String jwks = given().get("/oauth2/jwks").then().statusCode(200).extract().asString();
        JwtConsumer consumer = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setExpectedIssuer("http://auth.test")
                .setExpectedAudience("acmeinsurance")
                .setExpectedType(true, "at+jwt")
                .setVerificationKeyResolver(new JwksVerificationKeyResolver(new JsonWebKeySet(jwks).getJsonWebKeys()))
                .build();
        return consumer.processToClaims(accessToken);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> map(JwtClaims c, String name) {
        return (Map<String, List<String>>) c.getClaimValue(name);
    }

    // ---- metadata ----------------------------------------------------------

    @Test
    void publishesMetadataAndJwks() throws Exception {
        given().get("/.well-known/oauth-authorization-server").then().statusCode(200)
                .body("issuer", equalTo("http://auth.test"))
                .body("token_endpoint", equalTo("http://auth.test/oauth2/token"))
                .body("code_challenge_methods_supported[0]", equalTo("S256"));
        String jwks = given().get("/oauth2/jwks").then().statusCode(200).extract().asString();
        JsonWebKeySet set = new JsonWebKeySet(jwks);
        assertEquals(1, set.getJsonWebKeys().size());
        assertEquals("RSA", set.getJsonWebKeys().get(0).getKeyType());
        assertFalse(jwks.contains("\"d\""), "private key material in JWKS");
    }

    // ---- password grant and claims ----------------------------------------

    @Test
    void agentTokenCarriesPermissionGrantAndHeldAccounts() throws Exception {
        agent("claims.one", List.of(READ_QUOTES), List.of(AGR_01));

        Response r = passwordGrant("claims.one", PASSWORD);
        r.then().statusCode(200).header("Cache-Control", containsString("no-store"))
                .body("token_type", equalTo("Bearer"));
        JwtClaims c = verify(r.path("access_token"));

        assertEquals("claims.one", c.getStringClaimValue("preferred_username"));
        assertEquals("agent", c.getStringClaimValue("user_type"));
        assertEquals("demo-app", c.getStringClaimValue("client_id"));
        assertEquals(ldap().get("uid=claims.one," + AGENTS).getAttributeValue("entryUUID"), c.getSubject());
        assertEquals(Map.of("quoting", List.of("read-quotes")), map(c, "features"));
        Map<String, List<String>> accounts = map(c, "accounts");
        assertEquals(List.of("AG0042/AGR-0042-01"), accounts.get("agreements"));
        assertEquals(List.of(), accounts.get("agencies"));
        assertEquals(List.of(), accounts.get("books"));
        assertEquals(List.of(), accounts.get("clients"));
        assertNotNull(r.path("refresh_token"));
        assertEquals("at+jwt", JsonWebSignature.fromCompactSerialization(r.path("access_token")).getHeader("typ"));
    }

    @Test
    void featureGrantExpandsToEveryPermissionAndAccountsAreSortedByLevel() throws Exception {
        agent("claims.two", List.of(QUOTING, READ_QUOTES),
                List.of(AG0042, BK_340, "cn=100234," + CLIENT_ACCOUNTS));

        JwtClaims c = verify(passwordGrant("claims.two", PASSWORD).then().statusCode(200).extract()
                .path("access_token"));

        assertEquals(Map.of("quoting", List.of("read-quotes", "write-quotes")), map(c, "features"));
        Map<String, List<String>> accounts = map(c, "accounts");
        assertEquals(List.of("AG0042"), accounts.get("agencies"));
        assertEquals(List.of("AG0042/AGR-0042-02/BK-000340"), accounts.get("books"));
        assertEquals(List.of("100234"), accounts.get("clients"));
    }

    @Test
    void membersGetTheirPolicyNumbersButNoFeatureOrAccountClaims() throws Exception {
        ldap().add("dn: uid=member.one," + MEMBERS, "objectClass: inetOrgPerson", "objectClass: acmeUser",
                "objectClass: acmeMemberAccess", "uid: member.one", "cn: Member One", "sn: One",
                "acmeUserType: member", "acmePolicyNumber: POL-2024-98765", "acmePolicyNumber: POL-2024-11111",
                "userPassword: " + PASSWORD);

        JwtClaims c = verify(passwordGrant("member.one", PASSWORD).then().statusCode(200).extract()
                .path("access_token"));

        assertEquals("member", c.getStringClaimValue("user_type"));
        assertEquals(List.of("POL-2024-11111", "POL-2024-98765"), c.getStringListClaimValue("policies"));
        assertFalse(c.hasClaim("features"));
        assertFalse(c.hasClaim("accounts"));
    }

    @Test
    void internalUsersGetFeaturesWithOptionalAccountScope() throws Exception {
        ldap().add("dn: uid=carol.csr," + INTERNAL, "objectClass: inetOrgPerson", "objectClass: acmeUser",
                "objectClass: acmeFeatureAccess", "objectClass: acmeAccountAccess", "uid: carol.csr", "cn: Carol",
                "sn: Csr", "acmeUserType: internal", "acmeFeatureDN: cn=claims," + FEATURES,
                "acmeAccountDN: " + AG0042, "userPassword: " + PASSWORD);

        JwtClaims c = verify(passwordGrant("carol.csr", PASSWORD).then().statusCode(200).extract()
                .path("access_token"));

        assertEquals("internal", c.getStringClaimValue("user_type"));
        assertEquals(Map.of("claims", List.of("adjust-claims", "approve-claims", "view-claims")), map(c, "features"));
        assertEquals(List.of("AG0042"), map(c, "accounts").get("agencies"));
        assertFalse(c.hasClaim("policies"));
    }

    @Test
    void otherUsersGetWhicheverGrantsTheyHold() throws Exception {
        ldap().add("dn: uid=casey," + OTHER, "objectClass: inetOrgPerson", "objectClass: acmeUser",
                "objectClass: acmeFeatureAccess", "objectClass: acmeAccountAccess", "objectClass: acmeMemberAccess",
                "uid: casey", "cn: Casey", "sn: Contractor", "acmeUserType: other",
                "acmeFeatureDN: cn=view-claims,cn=claims," + FEATURES, "acmeAccountDN: " + AG0042,
                "userPassword: " + PASSWORD);

        JwtClaims c = verify(passwordGrant("casey", PASSWORD).then().statusCode(200).extract()
                .path("access_token"));

        assertEquals(Map.of("claims", List.of("view-claims")), map(c, "features"));
        assertEquals(List.of("AG0042"), map(c, "accounts").get("agencies"));
        assertEquals(List.of(), c.getStringListClaimValue("policies"));
    }

    @Test
    void wrongPasswordUnknownUserAndDeactivatedUserAllGetTheSameError() throws Exception {
        agent("bad.pw", List.of(), List.of());
        agent("gone", List.of(), List.of(), "objectClass: acmeSyncManaged", "acmeDeactivated: 20261101000000Z");

        for (Response r : List.of(passwordGrant("bad.pw", "nope-nope-nope"), passwordGrant("nobody", PASSWORD),
                passwordGrant("gone", PASSWORD))) {
            r.then().statusCode(400).body("error", equalTo("invalid_grant"))
                    .body("error_description", equalTo("invalid username or password"));
        }
    }

    @Test
    void permanentlyLockedUserCannotSignIn() throws Exception {
        agent("locked", List.of(), List.of(), "pwdAccountLockedTime: 000001010000Z");
        passwordGrant("locked", PASSWORD).then().statusCode(400).body("error", equalTo("invalid_grant"));
    }

    @Test
    void clientMustAuthenticateAndBeAllowedThePasswordGrant() throws Exception {
        agent("client.checks", List.of(), List.of());
        given().auth().preemptive().basic("demo-app", "wrong-secret")
                .formParam("grant_type", "password").formParam("username", "client.checks")
                .formParam("password", PASSWORD)
                .post("/oauth2/token").then().statusCode(401).body("error", equalTo("invalid_client"))
                .header("WWW-Authenticate", startsWith("Basic"));
        given().formParam("client_id", "demo-spa")
                .formParam("grant_type", "password").formParam("username", "client.checks")
                .formParam("password", PASSWORD)
                .post("/oauth2/token").then().statusCode(400).body("error", equalTo("unauthorized_client"));
        given().formParam("client_id", "no-such-client").formParam("grant_type", "password")
                .post("/oauth2/token").then().statusCode(401).body("error", equalTo("invalid_client"));
        given().auth().preemptive().basic("demo-app", DEMO_APP_SECRET).formParam("grant_type", "client_credentials")
                .post("/oauth2/token").then().statusCode(400).body("error", equalTo("unsupported_grant_type"));
    }

    @Test
    void passwordGrantRefusesAUserWhoseResetPasswordMustBeChanged() throws Exception {
        agent("reset.pw", List.of(), List.of(), "pwdReset: TRUE");
        passwordGrant("reset.pw", PASSWORD).then().statusCode(400)
                .body("error_description", equalTo("password change required"));
    }

    // ---- refresh -----------------------------------------------------------

    @Test
    void refreshRereadsGrantsFromTheDirectory() throws Exception {
        agent("refresh.one", List.of(QUOTING), List.of(AGR_01));
        String rt = passwordGrant("refresh.one", PASSWORD).then().statusCode(200).extract().path("refresh_token");

        ldap().modify("dn: uid=refresh.one," + AGENTS, "changetype: modify",
                "replace: acmeFeatureDN", "acmeFeatureDN: " + READ_QUOTES,
                "-", "add: acmeAccountDN", "acmeAccountDN: " + BK_340);
        Response r = refresh(rt, "demo-app", DEMO_APP_SECRET);
        r.then().statusCode(200);
        JwtClaims c = verify(r.path("access_token"));

        assertEquals(Map.of("quoting", List.of("read-quotes")), map(c, "features"));
        assertEquals(List.of("AG0042/AGR-0042-02/BK-000340"), map(c, "accounts").get("books"));
        assertNotNull(r.path("refresh_token"));
    }

    @Test
    void refreshIsRefusedAfterDeactivationPasswordChangeOrForAnotherClient() throws Exception {
        agent("refresh.two", List.of(), List.of(), "objectClass: acmeSyncManaged");
        agent("refresh.three", List.of(), List.of());
        String rt2 = passwordGrant("refresh.two", PASSWORD).then().statusCode(200).extract().path("refresh_token");
        String rt3 = passwordGrant("refresh.three", PASSWORD).then().statusCode(200).extract().path("refresh_token");

        // demo-spa is public: it may not refresh at all, let alone another client's token.
        refresh(rt2, "demo-spa", null).then().statusCode(400).body("error", equalTo("unauthorized_client"));
        refresh(rt2, "demo-app", "wrong").then().statusCode(401);

        ldap().modify("dn: uid=refresh.two," + AGENTS, "changetype: modify", "replace: acmeDeactivated",
                "acmeDeactivated: 20261119000000Z");
        refresh(rt2, "demo-app", DEMO_APP_SECRET).then().statusCode(400).body("error", equalTo("invalid_grant"));

        ldap().modify("dn: uid=refresh.three," + AGENTS, "changetype: modify", "replace: pwdChangedTime",
                "pwdChangedTime: " + StaticUtils.encodeGeneralizedTime(System.currentTimeMillis() + 120_000));
        refresh(rt3, "demo-app", DEMO_APP_SECRET).then().statusCode(400)
                .body("error_description", containsString("password changed"));
    }

    @Test
    void anAccessTokenIsNotARefreshToken() throws Exception {
        agent("refresh.four", List.of(), List.of());
        String at = passwordGrant("refresh.four", PASSWORD).then().statusCode(200).extract().path("access_token");
        refresh(at, "demo-app", DEMO_APP_SECRET).then().statusCode(400).body("error", equalTo("invalid_grant"));
        refresh("not.a.jwt", "demo-app", DEMO_APP_SECRET).then().statusCode(400).body("error", equalTo("invalid_grant"));
    }

    // ---- authorization code + PKCE ----------------------------------------

    private static String verifier() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static Response authorize(String clientId, String redirect, String challenge) {
        var req = given().redirects().follow(false)
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirect).queryParam("state", "xyz");
        if (challenge != null) {
            req = req.queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
        }
        return req.get("/oauth2/authorize");
    }

    private static String requestId(String html) {
        Matcher m = Pattern.compile("name=\"request_id\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), html);
        return m.group(1);
    }

    private static Response login(String requestId, Map<String, String> fields) {
        var req = given().redirects().follow(false).formParam("request_id", requestId);
        for (var f : fields.entrySet()) {
            req = req.formParam(f.getKey(), f.getValue());
        }
        return req.post("/oauth2/authorize");
    }

    private static Map<String, String> query(String location) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : URI.create(location).getRawQuery().split("&")) {
            String[] kv = p.split("=", 2);
            out.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Response exchange(String code, String verifier) {
        return given().formParam("client_id", "demo-spa").formParam("grant_type", "authorization_code")
                .formParam("code", code).formParam("redirect_uri", REDIRECT).formParam("code_verifier", verifier)
                .post("/oauth2/token");
    }

    @Test
    void authorizationCodeWithPkceIssuesTokensOnceOnly() throws Exception {
        agent("code.one", List.of(READ_QUOTES), List.of(AGR_01));
        String verifier = verifier();
        Response page = authorize("demo-spa", REDIRECT, Pkce.challenge(verifier));
        page.then().statusCode(200).body(containsString("Demo single-page app"))
                .header("X-Frame-Options", "DENY");

        String id = requestId(page.asString());
        login(id, Map.of("username", "code.one", "password", "wrong-password-x")).then().statusCode(200)
                .body(containsString("Invalid username or password"));
        Response ok = login(id, Map.of("username", "code.one", "password", PASSWORD));
        ok.then().statusCode(303).header("Location", startsWith(REDIRECT + "?"));
        Map<String, String> q = query(ok.header("Location"));
        assertEquals("xyz", q.get("state"));

        exchange(q.get("code"), verifier()).then().statusCode(400).body("error", equalTo("invalid_grant"));
        // A failed exchange still uses up the code.
        exchange(q.get("code"), verifier).then().statusCode(400).body("error", equalTo("invalid_grant"));

        String verifier2 = verifier();
        String id2 = requestId(authorize("demo-spa", REDIRECT, Pkce.challenge(verifier2)).asString());
        String code2 = query(login(id2, Map.of("username", "code.one", "password", PASSWORD)).header("Location"))
                .get("code");
        Response tokens = exchange(code2, verifier2);
        tokens.then().statusCode(200);
        assertNull(tokens.path("refresh_token"), "public clients get no refresh token");
        JwtClaims c = verify(tokens.path("access_token"));
        assertEquals("demo-spa", c.getStringClaimValue("client_id"));
        assertEquals(Map.of("quoting", List.of("read-quotes")), map(c, "features"));
        exchange(code2, verifier2).then().statusCode(400).body("error", equalTo("invalid_grant"));

        // The login page is finished with once a code is issued.
        login(id2, Map.of("username", "code.one", "password", PASSWORD)).then().statusCode(400)
                .body(containsString("expired"));
    }

    @Test
    void authorizeRefusesUnknownRedirectsAndRequiresPkce() {
        authorize("demo-spa", "https://evil.example/cb", Pkce.challenge(verifier())).then().statusCode(400)
                .body(containsString("not registered"));
        authorize("no-such-client", REDIRECT, Pkce.challenge(verifier())).then().statusCode(400);
        Response r = authorize("demo-spa", REDIRECT, null);
        r.then().statusCode(303);
        Map<String, String> q = query(r.header("Location"));
        assertEquals("invalid_request", q.get("error"));
        assertEquals("xyz", q.get("state"));
        assertNull(q.get("code"));
    }

    @Test
    void userWithAResetPasswordChangesItAtSignIn() throws Exception {
        agent("code.reset", List.of(), List.of(), "pwdReset: TRUE");
        String verifier = verifier();
        String id = requestId(authorize("demo-spa", REDIRECT, Pkce.challenge(verifier)).asString());

        login(id, Map.of("username", "code.reset", "password", PASSWORD)).then().statusCode(200)
                .body(containsString("Choose a new password")).body(containsString("new_password"));
        login(id, Map.of("username", "code.reset", "password", PASSWORD, "new_password", "New-Password-12345",
                "confirm_password", "Different-12345")).then().statusCode(200)
                .body(containsString("do not match"));
        Response ok = login(id, Map.of("username", "code.reset", "password", PASSWORD,
                "new_password", "New-Password-12345", "confirm_password", "New-Password-12345"));
        ok.then().statusCode(303);

        assertFalse(ldap().get("uid=code.reset," + AGENTS).hasAttribute("pwdReset"));
        exchange(query(ok.header("Location")).get("code"), verifier).then().statusCode(200);
        passwordGrant("code.reset", "New-Password-12345").then().statusCode(200);
    }
}
