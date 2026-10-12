package com.stockpilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Strategy under test: stock is RESERVED when an order is created; cancel returns it exactly once. */
class OrderLifecycleTest extends IntegrationTest {

    private String orderUrl(Session s, JsonNode order, String suffix) {
        return ws(s) + "/orders/" + order.get("data").get("id").asString() + suffix;
    }

    @Test
    @DisplayName("3. Backend calculates line and order totals; client-supplied totals/prices are ignored")
    void serverSideTotals() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "19.99", 100, 5).get("id").asString();
        String b = createProduct(s, uniqueSku(), "0.10", 100, 5).get("id").asString();

        Map<String, Object> body = Map.of(
                "total", "0.01",
                "items", List.of(
                        Map.of("productId", a, "quantity", 3, "unitPrice", "0.01", "lineTotal", "0.01"),
                        Map.of("productId", b, "quantity", 3, "unitPrice", "9999")));
        JsonNode created = call(HttpMethod.POST, ws(s) + "/orders", s.token(), body, 201);
        JsonNode order = created.get("data");

        assertEquals("60.27", order.get("total").asString()); // 3*19.99 + 3*0.10, exact decimal (no 60.2700000001)
        assertEquals("pending", order.get("status").asString());
        assertEquals(2, order.get("items").size());
        for (JsonNode item : order.get("items")) {
            if (item.get("productId").asString().equals(a)) {
                assertEquals("19.99", item.get("unitPrice").asString());
                assertEquals("59.97", item.get("lineTotal").asString());
            } else {
                assertEquals("0.10", item.get("unitPrice").asString());
                assertEquals("0.30", item.get("lineTotal").asString());
            }
        }
        // Stock is reserved at creation.
        assertEquals(97, stockOf(a));
        assertEquals(97, stockOf(b));
    }

    @Test
    @DisplayName("Duplicate lines for one product are combined before stock is applied")
    void duplicateLinesMerged() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "2.00", 10, 0).get("id").asString();
        JsonNode created = order(s, 201, a, 2, a, 3);
        assertEquals(1, created.get("data").get("items").size());
        assertEquals(5, created.get("data").get("items").get(0).get("quantity").asInt());
        assertEquals("10.00", created.get("data").get("total").asString());
        assertEquals(5, stockOf(a));
        // Combined demand is checked against stock too.
        assertEquals("INSUFFICIENT_STOCK", errorCode(order(s, 409, a, 3, a, 3)));
        assertEquals(5, stockOf(a));
    }

    @Test
    @DisplayName("4. Insufficient stock rejects the order and changes nothing")
    void insufficientStock() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 4, 0).get("id").asString();
        JsonNode err = order(s, 409, a, 5);
        assertEquals("INSUFFICIENT_STOCK", errorCode(err));
        assertEquals(4, err.get("error").get("details").get(0).get("available").asInt());
        assertEquals(4, stockOf(a));
        assertEquals(0, count("SELECT count(*) FROM orders WHERE workspace_id = ?", s.workspaceId()));
        // exactly the available quantity is fine
        order(s, 201, a, 4);
        assertEquals(0, stockOf(a));
    }

    @Test
    @DisplayName("5. Multi-item atomicity: if one line fails nothing is created and no stock moves")
    void atomicity() {
        Session s = newOwner();
        String ok1 = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();
        String ok2 = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();
        String scarce = createProduct(s, uniqueSku(), "5.00", 1, 0).get("id").asString();
        long adjBefore = count("SELECT count(*) FROM inventory_adjustments WHERE workspace_id = ?", s.workspaceId());

        // The failing line is LAST, so earlier lines would already have been reserved in a naive implementation.
        order(s, 409, ok1, 2, ok2, 3, scarce, 2);

        assertEquals(10, stockOf(ok1));
        assertEquals(10, stockOf(ok2));
        assertEquals(1, stockOf(scarce));
        assertEquals(0, count("SELECT count(*) FROM orders WHERE workspace_id = ?", s.workspaceId()));
        assertEquals(0, count("SELECT count(*) FROM order_items WHERE workspace_id = ?", s.workspaceId()));
        assertEquals(adjBefore, count("SELECT count(*) FROM inventory_adjustments WHERE workspace_id = ?", s.workspaceId()));

        // An unknown product id also fails the whole order cleanly.
        order(s, 400, ok1, 1, java.util.UUID.randomUUID().toString(), 1);
        assertEquals(10, stockOf(ok1));
    }

    @Test
    @DisplayName("Inactive products cannot be ordered, and existing orders keep their line history")
    void inactiveProducts() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();
        JsonNode first = order(s, 201, a, 2);
        call(HttpMethod.PATCH, ws(s) + "/products/" + a, s.token(), Map.of("active", false), 200);

        assertEquals("INACTIVE_PRODUCT", errorCode(order(s, 409, a, 1)));
        JsonNode reloaded = get(s, orderUrl(s, first, ""), 200).get("data");
        assertEquals(1, reloaded.get("items").size());
        assertEquals("5.00", reloaded.get("items").get(0).get("unitPrice").asString());
        // A pending order for a now-inactive product can still be cancelled and its stock returns.
        call(HttpMethod.POST, orderUrl(s, first, "/cancel"), s.token(), null, 200);
        assertEquals(10, stockOf(a));
    }

    @Test
    @DisplayName("Quantity must be a positive whole number")
    void quantityValidation() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();
        order(s, 400, a, 0);
        order(s, 400, a, -1);
        order(s, 400, a, 1.5);
        call(HttpMethod.POST, ws(s) + "/orders", s.token(), Map.of("items", List.of()), 400);
        call(HttpMethod.POST, ws(s) + "/orders", s.token(), Map.of(), 400);
        assertEquals(10, stockOf(a));
    }

    @Test
    @DisplayName("6. Cancelling restores stock exactly once, even when requested repeatedly")
    void cancellationRestoresOnce() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();
        String b = createProduct(s, uniqueSku(), "7.00", 10, 0).get("id").asString();
        JsonNode created = order(s, 201, a, 4, b, 6);
        assertEquals(6, stockOf(a));
        assertEquals(4, stockOf(b));

        JsonNode cancelled = call(HttpMethod.POST, orderUrl(s, created, "/cancel"), s.token(), null, 200).get("data");
        assertEquals("cancelled", cancelled.get("status").asString());
        assertNotNull(cancelled.get("stockReleasedAt"));
        assertEquals(10, stockOf(a));
        assertEquals(10, stockOf(b));

        // Retries must not restore again.
        for (int i = 0; i < 3; i++) {
            JsonNode again = call(HttpMethod.POST, orderUrl(s, created, "/cancel"), s.token(), null, 409);
            assertEquals("INVALID_TRANSITION", errorCode(again));
        }
        assertEquals(10, stockOf(a));
        assertEquals(10, stockOf(b));
        assertEquals(2, count("SELECT count(*) FROM inventory_adjustments WHERE order_id = ?::uuid AND reason_type = 'order_release'",
                created.get("data").get("id").asString()));
    }

    @Test
    @DisplayName("Confirmed orders can be cancelled (stock returns); completed orders cannot")
    void cancelConfirmedButNotCompleted() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();

        JsonNode o1 = order(s, 201, a, 3);
        call(HttpMethod.POST, orderUrl(s, o1, "/confirm"), s.token(), null, 200);
        assertEquals(7, stockOf(a), "confirming must not deduct a second time");
        call(HttpMethod.POST, orderUrl(s, o1, "/cancel"), s.token(), null, 200);
        assertEquals(10, stockOf(a));

        JsonNode o2 = order(s, 201, a, 3);
        call(HttpMethod.POST, orderUrl(s, o2, "/confirm"), s.token(), null, 200);
        JsonNode done = call(HttpMethod.POST, orderUrl(s, o2, "/complete"), s.token(), null, 200).get("data");
        assertEquals("completed", done.get("status").asString());
        assertEquals(7, stockOf(a), "completion keeps the deduction");
        call(HttpMethod.POST, orderUrl(s, o2, "/cancel"), s.token(), null, 409);
        assertEquals(7, stockOf(a));
    }

    @Test
    @DisplayName("7. Invalid status transitions are rejected")
    void invalidTransitions() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();

        JsonNode pending = order(s, 201, a, 1);
        call(HttpMethod.POST, orderUrl(s, pending, "/complete"), s.token(), null, 409); // pending -> completed
        call(HttpMethod.POST, orderUrl(s, pending, "/confirm"), s.token(), null, 200);
        JsonNode again = call(HttpMethod.POST, orderUrl(s, pending, "/confirm"), s.token(), null, 409); // confirmed -> confirmed
        assertEquals("INVALID_TRANSITION", errorCode(again));
        call(HttpMethod.POST, orderUrl(s, pending, "/complete"), s.token(), null, 200);
        call(HttpMethod.POST, orderUrl(s, pending, "/confirm"), s.token(), null, 409); // completed -> confirmed
        call(HttpMethod.POST, orderUrl(s, pending, "/complete"), s.token(), null, 409);
        call(HttpMethod.POST, orderUrl(s, pending, "/cancel"), s.token(), null, 409);

        JsonNode cancelled = order(s, 201, a, 1);
        call(HttpMethod.POST, orderUrl(s, cancelled, "/cancel"), s.token(), null, 200);
        call(HttpMethod.POST, orderUrl(s, cancelled, "/confirm"), s.token(), null, 409); // cancelled -> confirmed
        call(HttpMethod.POST, orderUrl(s, cancelled, "/complete"), s.token(), null, 409);
        assertEquals(9, stockOf(a)); // only the completed order's unit remains deducted
    }

    @Test
    @DisplayName("8. Changing a product price (or name) does not alter existing orders")
    void priceSnapshots() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "10.00", 50, 0).get("id").asString();
        JsonNode created = order(s, 201, a, 3);
        assertEquals("30.00", created.get("data").get("total").asString());

        call(HttpMethod.PATCH, ws(s) + "/products/" + a, s.token(), Map.of("unitPrice", "99.99", "name", "Renamed"), 200);

        JsonNode reloaded = get(s, orderUrl(s, created, ""), 200).get("data");
        assertEquals("30.00", reloaded.get("total").asString());
        assertEquals("10.00", reloaded.get("items").get(0).get("unitPrice").asString());
        assertEquals("30.00", reloaded.get("items").get(0).get("lineTotal").asString());
        assertTrue(reloaded.get("items").get(0).get("productName").asString().startsWith("Product "));

        // A new order uses the new price.
        assertEquals("99.99", order(s, 201, a, 1).get("data").get("total").asString());
    }

    @Test
    @DisplayName("Idempotency-Key makes a retried create return the same order without reserving stock twice")
    void idempotentCreate() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "5.00", 10, 0).get("id").asString();
        var body = Map.of("items", List.of(Map.of("productId", a, "quantity", 4)));
        String key = "key-" + java.util.UUID.randomUUID();

        JsonNode first = callWithKey(s, key, body, 201);
        JsonNode second = callWithKey(s, key, body, 200);
        assertEquals(first.get("data").get("id").asString(), second.get("data").get("id").asString());
        assertEquals(6, stockOf(a));
        assertEquals(1, count("SELECT count(*) FROM orders WHERE workspace_id = ?", s.workspaceId()));
    }

    private JsonNode callWithKey(Session s, String key, Object body, int expected) {
        try {
            var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ws(s) + "/orders")
                    .header("Authorization", "Bearer " + s.token()).header("Idempotency-Key", key)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
            var res = mvc.perform(req).andReturn().getResponse();
            assertEquals(expected, res.getStatus(), res.getContentAsString());
            return om.readTree(res.getContentAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Order list filters: status, order number, date range; paginated")
    void orderListing() {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "1.00", 100, 0).get("id").asString();
        JsonNode o1 = order(s, 201, a, 1);
        order(s, 201, a, 1);
        order(s, 201, a, 1);
        call(HttpMethod.POST, orderUrl(s, o1, "/cancel"), s.token(), null, 200);

        assertEquals(3, get(s, ws(s) + "/orders", 200).get("meta").get("total").asInt());
        assertEquals(1, get(s, ws(s) + "/orders?status=cancelled", 200).get("meta").get("total").asInt());
        assertEquals(2, get(s, ws(s) + "/orders?status=pending&pageSize=1", 200).get("meta").get("totalPages").asInt());
        String number = o1.get("data").get("orderNumber").asString();
        assertEquals(1, get(s, ws(s) + "/orders?q=" + number, 200).get("meta").get("total").asInt());
        assertEquals(1, get(s, ws(s) + "/orders?q=" + o1.get("data").get("id").asString(), 200).get("meta").get("total").asInt());
        assertEquals(0, get(s, ws(s) + "/orders?q=garbage", 200).get("meta").get("total").asInt());
        assertEquals(3, get(s, ws(s) + "/orders?from=2000-01-01&to=2999-12-31", 200).get("meta").get("total").asInt());
        assertEquals(0, get(s, ws(s) + "/orders?to=2000-01-01", 200).get("meta").get("total").asInt());
        get(s, ws(s) + "/orders?status=bogus", 400);
        get(s, ws(s) + "/orders?from=2025-02-01&to=2025-01-01", 400);
    }

    @Test
    @DisplayName("Dashboard metrics come from persisted data using the documented definitions")
    void dashboard() {
        Session s = newOwner();
        JsonNode empty = get(s, ws(s) + "/dashboard", 200).get("data");
        assertEquals(0, empty.get("activeProducts").asInt());
        assertEquals("0.00", empty.get("orderValue").asString());
        assertEquals(0, empty.get("recentOrders").size());

        String low = createProduct(s, uniqueSku(), "10.00", 3, 5).get("id").asString();   // low stock (3 <= 5)
        String ok = createProduct(s, uniqueSku(), "20.00", 100, 5).get("id").asString();
        String inactive = createProduct(s, uniqueSku(), "1.00", 0, 5).get("id").asString(); // would be low but inactive
        call(HttpMethod.PATCH, ws(s) + "/products/" + inactive, s.token(), Map.of("active", false), 200);

        JsonNode o1 = order(s, 201, ok, 2);  // 40.00 -> completed
        call(HttpMethod.POST, orderUrl(s, o1, "/confirm"), s.token(), null, 200);
        call(HttpMethod.POST, orderUrl(s, o1, "/complete"), s.token(), null, 200);
        order(s, 201, low, 1);               // 10.00 pending
        JsonNode o3 = order(s, 201, ok, 5);  // 100.00 -> cancelled (excluded from value)
        call(HttpMethod.POST, orderUrl(s, o3, "/cancel"), s.token(), null, 200);

        JsonNode d = get(s, ws(s) + "/dashboard", 200).get("data");
        assertEquals(2, d.get("activeProducts").asInt());
        assertEquals(1, d.get("lowStockProducts").asInt());
        assertEquals(1, d.get("ordersByStatus").get("completed").asInt());
        assertEquals(1, d.get("ordersByStatus").get("pending").asInt());
        assertEquals(1, d.get("ordersByStatus").get("cancelled").asInt());
        assertEquals(0, d.get("ordersByStatus").get("confirmed").asInt());
        assertEquals("50.00", d.get("orderValue").asString());
        assertEquals("100.00", d.get("valueByStatus").get("cancelled").asString());
        assertEquals(3, d.get("recentOrders").size());
        assertTrue(d.get("recentAdjustments").size() > 0);

        JsonNode past = get(s, ws(s) + "/dashboard?from=2000-01-01&to=2000-01-31", 200).get("data");
        assertEquals("0.00", past.get("orderValue").asString());
        assertEquals(2, past.get("activeProducts").asInt()); // product counts are point-in-time, not range-bound
    }
}
