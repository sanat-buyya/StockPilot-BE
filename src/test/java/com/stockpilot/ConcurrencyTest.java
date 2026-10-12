package com.stockpilot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Real threads, real PostgreSQL row locks. All workers are released at the same instant to maximise contention.
 */
class ConcurrencyTest extends IntegrationTest {

    private record Result(int status, String body) {}

    private Result send(String token, String path, Object body, String idempotencyKey) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : om.writeValueAsString(body));
        if (idempotencyKey != null) req.header("Idempotency-Key", idempotencyKey);
        var res = mvc.perform(req).andReturn().getResponse();
        return new Result(res.getStatus(), res.getContentAsString());
    }

    private List<Result> race(int threads, java.util.function.IntFunction<Callable<Result>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Result> task = work.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        go.countDown();
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return results;
    }

    private long countStatus(List<Result> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    @Test
    @DisplayName("10. Competing orders against limited stock can never consume more than exists")
    void noOverselling() throws Exception {
        Session s = newOwner();
        String p = createProduct(s, uniqueSku(), "3.00", 10, 0).get("id").asString();
        var body = Map.of("items", List.of(Map.of("productId", p, "quantity", 1)));

        List<Result> results = race(30, i -> () -> send(s.token(), ws(s) + "/orders", body, null));

        assertEquals(10, countStatus(results, 201), "exactly the available stock can be sold: " + results);
        assertEquals(20, countStatus(results, 409));
        assertTrue(results.stream().filter(r -> r.status() == 409).allMatch(r -> r.body().contains("INSUFFICIENT_STOCK")));
        assertEquals(0, stockOf(p));
        assertEquals(10, count("SELECT count(*) FROM orders WHERE workspace_id = ?", s.workspaceId()));
        assertEquals(10, count("SELECT COALESCE(sum(quantity), 0) FROM order_items WHERE workspace_id = ?", s.workspaceId()));
        assertReconciles(p);
    }

    @Test
    @DisplayName("Larger competing orders: total reserved never exceeds stock")
    void noOversellingWithLargerQuantities() throws Exception {
        Session s = newOwner();
        String p = createProduct(s, uniqueSku(), "3.00", 10, 0).get("id").asString();
        var body = Map.of("items", List.of(Map.of("productId", p, "quantity", 3)));

        List<Result> results = race(12, i -> () -> send(s.token(), ws(s) + "/orders", body, null));

        assertEquals(3, countStatus(results, 201));
        assertEquals(1, stockOf(p));
        assertReconciles(p);
    }

    @Test
    @DisplayName("Concurrent multi-item orders with opposite item order complete without deadlock")
    void noDeadlock() throws Exception {
        Session s = newOwner();
        String a = createProduct(s, uniqueSku(), "1.00", 1000, 0).get("id").asString();
        String b = createProduct(s, uniqueSku(), "1.00", 1000, 0).get("id").asString();
        var ab = Map.of("items", List.of(Map.of("productId", a, "quantity", 1), Map.of("productId", b, "quantity", 1)));
        var ba = Map.of("items", List.of(Map.of("productId", b, "quantity", 1), Map.of("productId", a, "quantity", 1)));

        List<Result> results = race(30, i -> () -> send(s.token(), ws(s) + "/orders", i % 2 == 0 ? ab : ba, null));

        assertEquals(30, countStatus(results, 201), results.toString());
        assertEquals(970, stockOf(a));
        assertEquals(970, stockOf(b));
    }

    @Test
    @DisplayName("6b. Concurrent cancellations of one order restore stock exactly once")
    void concurrentCancelRestoresOnce() throws Exception {
        Session s = newOwner();
        String p = createProduct(s, uniqueSku(), "3.00", 10, 0).get("id").asString();
        JsonNode created = order(s, 201, p, 4);
        String orderId = created.get("data").get("id").asString();
        assertEquals(6, stockOf(p));

        List<Result> results = race(12, i -> () -> send(s.token(), ws(s) + "/orders/" + orderId + "/cancel", null, null));

        assertEquals(1, countStatus(results, 200), results.toString());
        assertEquals(11, countStatus(results, 409));
        assertEquals(10, stockOf(p), "stock must be restored once, not once per request");
        assertEquals(1, count("SELECT count(*) FROM inventory_adjustments WHERE order_id = ?::uuid AND reason_type = 'order_release'", orderId));
        assertReconciles(p);
    }

    @Test
    @DisplayName("Concurrent negative adjustments cannot push stock below zero")
    void concurrentAdjustments() throws Exception {
        Session s = newOwner();
        String p = createProduct(s, uniqueSku(), "3.00", 10, 0).get("id").asString();
        var body = Map.of("quantityChange", -1, "reasonType", "damaged");

        List<Result> results = race(25, i -> () -> send(s.token(), ws(s) + "/products/" + p + "/adjustments", body, null));

        assertEquals(10, countStatus(results, 201));
        assertEquals(15, countStatus(results, 409));
        assertEquals(0, stockOf(p));
        assertReconciles(p);
    }

    @Test
    @DisplayName("Concurrent retries with one Idempotency-Key create exactly one order")
    void concurrentIdempotentCreate() throws Exception {
        Session s = newOwner();
        String p = createProduct(s, uniqueSku(), "3.00", 10, 0).get("id").asString();
        var body = Map.of("items", List.of(Map.of("productId", p, "quantity", 4)));
        String key = "idem-" + java.util.UUID.randomUUID();

        List<Result> results = race(10, i -> () -> send(s.token(), ws(s) + "/orders", body, key));

        assertEquals(10, countStatus(results, 201) + countStatus(results, 200), results.toString());
        assertEquals(1, countStatus(results, 201));
        assertEquals(1, count("SELECT count(*) FROM orders WHERE workspace_id = ?", s.workspaceId()));
        assertEquals(6, stockOf(p), "stock reserved once");
        assertReconciles(p);
    }

    /** Current stock must always equal the sum of its recorded changes: the audit trail never contradicts the product. */
    private void assertReconciles(String productId) {
        long sum = count("SELECT COALESCE(sum(quantity_change), 0) FROM inventory_adjustments WHERE product_id = ?::uuid", productId);
        assertEquals(stockOf(productId), sum);
        long negative = count("SELECT count(*) FROM products WHERE id = ?::uuid AND stock_quantity < 0", productId);
        assertEquals(0, negative);
    }
}
