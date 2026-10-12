package com.stockpilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the whole application against a REAL PostgreSQL (started in-process, no Docker needed) with the real
 * Flyway migrations, so constraints, row locks and concurrency are exercised for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {
    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcClient jdbc;

    /** A signed-in user acting inside one workspace. */
    public record Session(String token, UUID userId, UUID workspaceId, String email) {}

    protected Session newOwner() {
        String email = "owner" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
        JsonNode data = call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", email, "name", "Owner", "password", "password123", "workspaceName", "WS " + email), 201).get("data");
        return new Session(data.get("token").asString(), UUID.fromString(data.get("user").get("id").asString()),
                UUID.fromString(data.get("workspaces").get(0).get("id").asString()), email);
    }

    /** Registers a new user and has {@code owner} add them to the owner's workspace as STAFF. */
    protected Session addStaff(Session owner) {
        String email = "staff" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
        JsonNode data = call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", email, "name", "Staff", "password", "password123"), 201).get("data");
        call(HttpMethod.POST, "/api/workspaces/" + owner.workspaceId() + "/members", owner.token(),
                Map.of("email", email, "role", "staff"), 201);
        return new Session(data.get("token").asString(), UUID.fromString(data.get("user").get("id").asString()), owner.workspaceId(), email);
    }

    protected String ws(Session s) {
        return "/api/workspaces/" + s.workspaceId();
    }

    protected JsonNode createProduct(Session s, String sku, String price, int stock, int threshold) {
        return call(HttpMethod.POST, ws(s) + "/products", s.token(),
                Map.of("name", "Product " + sku, "sku", sku, "unitPrice", price, "stockQuantity", stock, "lowStockThreshold", threshold), 201)
                .get("data");
    }

    protected String uniqueSku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected int stockOf(String productId) {
        return jdbc.sql("SELECT stock_quantity FROM products WHERE id = :id").param("id", UUID.fromString(productId)).query(Integer.class).single();
    }

    protected long count(String sql, Object... params) {
        var q = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) q = q.param(params[i]);
        return q.query(Long.class).single();
    }

    protected JsonNode order(Session s, int expected, Object... productIdAndQty) {
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < productIdAndQty.length; i += 2) {
            items.add(Map.of("productId", productIdAndQty[i], "quantity", productIdAndQty[i + 1]));
        }
        return call(HttpMethod.POST, ws(s) + "/orders", s.token(), Map.of("items", items), expected);
    }

    protected JsonNode call(HttpMethod method, String path, String token, Object body, int expectedStatus) {
        try {
            MockHttpServletRequestBuilder req = request(method, path);
            if (token != null) req.header("Authorization", "Bearer " + token);
            if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
            MvcResult res = mvc.perform(req).andReturn();
            String text = res.getResponse().getContentAsString();
            assertEquals(expectedStatus, res.getResponse().getStatus(), method + " " + path + " -> " + text);
            return text.isEmpty() ? om.createObjectNode() : om.readTree(text);
        } catch (Exception e) {
            Assertions.fail(e);
            return null;
        }
    }

    protected JsonNode get(Session s, String path, int expected) {
        return call(HttpMethod.GET, path, s == null ? null : s.token(), null, expected);
    }

    protected String errorCode(JsonNode body) {
        return body.get("error").get("code").asString();
    }
}
