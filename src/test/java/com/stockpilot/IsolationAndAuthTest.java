package com.stockpilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class IsolationAndAuthTest extends IntegrationTest {

    @Test
    @DisplayName("Authentication: register, login, me; wrong password and duplicate email are rejected")
    void authFlow() {
        String email = "flow-" + UUID.randomUUID() + "@test.dev";
        call(HttpMethod.POST, "/api/auth/register", null, Map.of("email", email, "name", "Flo", "password", "short"), 400);
        call(HttpMethod.POST, "/api/auth/register", null, Map.of("email", "nope", "name", "Flo", "password", "password123"), 400);
        JsonNode reg = call(HttpMethod.POST, "/api/auth/register", null, Map.of("email", email.toUpperCase(), "name", "Flo", "password", "password123"), 201);
        assertEquals("owner", reg.get("data").get("workspaces").get(0).get("role").asString());
        assertEquals("EMAIL_TAKEN", errorCode(call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", email, "name", "Flo", "password", "password123"), 409)));

        JsonNode bad = call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", "wrong-password"), 401);
        assertEquals("UNAUTHENTICATED", errorCode(bad));
        JsonNode unknown = call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", "ghost@test.dev", "password", "wrong-password"), 401);
        assertEquals(bad.get("error").get("message").asString(), unknown.get("error").get("message").asString());

        String token = call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", "password123"), 200)
                .get("data").get("token").asString();
        JsonNode me = call(HttpMethod.GET, "/api/auth/me", token, null, 200).get("data");
        assertEquals(email, me.get("user").get("email").asString());
        assertFalse(me.toString().contains("password"), "never expose password data");
    }

    @Test
    @DisplayName("Private routes need a valid token")
    void protectedRoutes() {
        Session a = newOwner();
        call(HttpMethod.GET, ws(a) + "/products", null, null, 401);
        call(HttpMethod.GET, ws(a) + "/products", "not.a.jwt", null, 401);
        call(HttpMethod.GET, "/api/auth/me", null, null, 401);
        call(HttpMethod.GET, "/api/workspaces", null, null, 401);
        JsonNode body = call(HttpMethod.GET, ws(a) + "/orders", null, null, 401);
        assertEquals("UNAUTHENTICATED", errorCode(body));
        // health is public
        call(HttpMethod.GET, "/actuator/health", null, null, 200);
    }

    @Test
    @DisplayName("9. A member of workspace A cannot read or mutate workspace B's data by manipulating ids")
    void workspaceIsolation() {
        Session a = newOwner();
        Session b = newOwner();
        String aProduct = createProduct(a, "ISO-1", "10.00", 10, 1).get("id").asString();
        String bProduct = createProduct(b, "ISO-1", "10.00", 10, 1).get("id").asString();
        JsonNode aOrder = order(a, 201, aProduct, 2);
        String aOrderId = aOrder.get("data").get("id").asString();

        // 1) B addresses A's workspace id directly -> 404 for everything (existence is not disclosed).
        for (String path : new String[] {"/products", "/orders", "/dashboard", "/inventory/adjustments", "/members",
                "/products/" + aProduct, "/orders/" + aOrderId, "/products/" + aProduct + "/adjustments"}) {
            JsonNode r = call(HttpMethod.GET, "/api/workspaces/" + a.workspaceId() + path, b.token(), null, 404);
            assertEquals("NOT_FOUND", errorCode(r));
        }
        call(HttpMethod.POST, "/api/workspaces/" + a.workspaceId() + "/products", b.token(),
                Map.of("name", "Evil", "sku", "EVIL", "unitPrice", "1"), 404);
        call(HttpMethod.PATCH, "/api/workspaces/" + a.workspaceId() + "/products/" + aProduct, b.token(), Map.of("unitPrice", "0"), 404);
        call(HttpMethod.POST, "/api/workspaces/" + a.workspaceId() + "/orders/" + aOrderId + "/cancel", b.token(), null, 404);
        call(HttpMethod.POST, "/api/workspaces/" + a.workspaceId() + "/members", b.token(), Map.of("email", b.email(), "role", "owner"), 404);

        // 2) B uses its OWN workspace id with A's resource ids -> 404 (resource scoped by workspace).
        call(HttpMethod.GET, ws(b) + "/products/" + aProduct, b.token(), null, 404);
        call(HttpMethod.PATCH, ws(b) + "/products/" + aProduct, b.token(), Map.of("name", "hacked"), 404);
        call(HttpMethod.GET, ws(b) + "/orders/" + aOrderId, b.token(), null, 404);
        call(HttpMethod.POST, ws(b) + "/orders/" + aOrderId + "/cancel", b.token(), null, 404);
        call(HttpMethod.POST, ws(b) + "/orders/" + aOrderId + "/confirm", b.token(), null, 404);
        call(HttpMethod.POST, ws(b) + "/products/" + aProduct + "/adjustments", b.token(), Map.of("quantityChange", 100, "reasonType", "received"), 404);
        call(HttpMethod.GET, ws(b) + "/products/" + aProduct + "/adjustments", b.token(), null, 404);
        JsonNode cross = order(b, 400, aProduct, 1); // ordering another workspace's product
        assertEquals("VALIDATION_ERROR", errorCode(cross));

        // 3) Nothing leaked into lists, and A's data is untouched.
        assertEquals(1, get(b, ws(b) + "/products", 200).get("meta").get("total").asInt());
        assertEquals(bProduct, get(b, ws(b) + "/products", 200).get("data").get(0).get("id").asString());
        assertEquals(0, get(b, ws(b) + "/orders", 200).get("meta").get("total").asInt());
        assertEquals(8, stockOf(aProduct));
        assertEquals(10, stockOf(bProduct));
        assertEquals("pending", get(a, ws(a) + "/orders/" + aOrderId, 200).get("data").get("status").asString());
        assertEquals("10.00", get(a, ws(a) + "/products/" + aProduct, 200).get("data").get("unitPrice").asString());

        // 4) Workspace list only shows own workspaces; a user id in the body is never trusted.
        JsonNode mine = get(b, "/api/workspaces", 200).get("data");
        assertEquals(1, mine.size());
        assertEquals(b.workspaceId().toString(), mine.get(0).get("id").asString());
    }

    @Test
    @DisplayName("Malformed ids and unknown routes do not leak internals")
    void malformedIds() {
        Session a = newOwner();
        call(HttpMethod.GET, "/api/workspaces/not-a-uuid/products", a.token(), null, 400);
        call(HttpMethod.GET, ws(a) + "/products/not-a-uuid", a.token(), null, 400);
        call(HttpMethod.GET, ws(a) + "/products/" + UUID.randomUUID(), a.token(), null, 404);
        call(HttpMethod.GET, "/api/workspaces/" + UUID.randomUUID() + "/products", a.token(), null, 404);
        JsonNode r = call(HttpMethod.POST, ws(a) + "/products", a.token(), null, 400);
        assertFalse(r.toString().contains("Exception"), r.toString());
    }

    @Test
    @DisplayName("Removed members lose access immediately; a workspace always keeps an owner")
    void membershipLifecycle() {
        Session owner = newOwner();
        Session staff = addStaff(owner);
        get(staff, ws(owner) + "/products", 200);

        call(HttpMethod.DELETE, ws(owner) + "/members/" + staff.userId(), owner.token(), null, 204);
        get(staff, ws(owner) + "/products", 404); // same still-valid token, but no longer a member

        // sole owner cannot remove or demote themself
        call(HttpMethod.DELETE, ws(owner) + "/members/" + owner.userId(), owner.token(), null, 409);
        call(HttpMethod.PATCH, ws(owner) + "/members/" + owner.userId(), owner.token(), Map.of("role", "staff"), 409);
        // unknown email cannot be added
        call(HttpMethod.POST, ws(owner) + "/members", owner.token(), Map.of("email", "nobody@nowhere.dev"), 404);
    }
}
