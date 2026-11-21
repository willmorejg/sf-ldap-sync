package com.acmeinsurance.directory;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** The InsureDirectory HTTP surface for a browser that has not signed in. */
@QuarkusTest
class ApiTest {

    @Test
    void theApiNeedsASession() {
        given().when().get("/api/me").then().statusCode(401)
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
                .body("error", equalTo("sign_in_required"));
        given().header("Cookie", "idir_session=made-up").when().get("/api/kinds").then().statusCode(401);
    }

    @Test
    void changesNeedTheRequestedWithHeader() {
        given().contentType("application/json").body("{}").when().post("/api/query/run").then().statusCode(403)
                .body("error", equalTo("csrf"));
    }

    @Test
    void loginRedirectsToTheTokenServiceOnTheSameOriginWithPkce() {
        given().redirects().follow(false).queryParam("returnTo", "/people/member").when().get("/auth/login")
                .then().statusCode(302)
                .header("Location", startsWith("/oauth2/authorize?response_type=code"))
                .header("Location", containsString("client_id=insure-directory"))
                .header("Location", containsString("code_challenge_method=S256"))
                .header("Location", containsString("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fcallback"));
    }

    @Test
    void aCallbackWithoutAPendingSignInEndsOnTheSignedOutPage() {
        given().redirects().follow(false).queryParam("code", "x").queryParam("state", "unknown").when()
                .get("/auth/callback").then().statusCode(302).header("Location", equalTo("/signed-out?reason=expired"));
    }

    @Test
    void requestHeadersAreNotEchoed() {
        given().header("Cookie", "idir_session=secret-value").when().get("/api/me").then()
                .header("Cookie", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void theOpenApiDocumentListsTheOperations() {
        given().when().get("/openapi").then().statusCode(200)
                .body(containsString("/api/entries/{kind}/{id}/password-reset"));
    }
}
