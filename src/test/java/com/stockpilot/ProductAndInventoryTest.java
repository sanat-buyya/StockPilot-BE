package com.stockpilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class ProductAndInventoryTest extends IntegrationTest {

    @Test
    @DisplayName("1. SKU is unique per workspace (case-insensitive) but reusable across workspaces")
    void skuUniqueness() {
        Session a = newOwner();
        Session b = newOwner();
        createProduct(a, "ABC-1", "10.00", 5, 1);

        JsonNode dup = call(HttpMethod.POST, ws(a) + "/products", a.token(),
                Map.of("name", "Other", "sku", "abc-1", "unitPrice", "1.00"), 409);
        assertEquals("DUPLICATE_SKU", errorCode(dup));

        // The same SKU in a different workspace is fine.
        createProduct(b, "ABC-1", "10.00", 5, 1);
        assertEquals(1, count("SELECT count(*) FROM products WHERE workspace_id = ? AND sku = 'ABC-1'", a.workspaceId()));
    }

    @Test
    @DisplayName("Product input is validated on the server")
    void productValidation() {
        Session a = newOwner();
        String url = ws(a) + "/products";
        call(HttpMethod.POST, url, a.token(), Map.of("name", "X", "sku", "S1", "unitPrice", "-1"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("name", "X", "sku", "S2", "unitPrice", "1.999"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("name", "X", "sku", "S3", "unitPrice", "1.00", "stockQuantity", -1), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("name", "X", "sku", "S4", "unitPrice", "1.00", "lowStockThreshold", -3), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("name", "X", "sku", "bad sku!", "unitPrice", "1.00"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("name", "  ", "sku", "S5", "unitPrice", "1.00"), 400);
        JsonNode r = call(HttpMethod.POST, url, a.token(), Map.of("name", "X", "sku", "S6"), 400);
        assertEquals("VALIDATION_ERROR", errorCode(r));
        assertTrue(r.get("error").get("details").toString().contains("unitPrice"));
        assertEquals(0, count("SELECT count(*) FROM products WHERE workspace_id = ?", a.workspaceId()));
    }

    @Test
    @DisplayName("Money is exact decimal, never a float")
    void moneyIsExact() {
        Session a = newOwner();
        JsonNode p = createProduct(a, "M1", "0.10", 10, 0);
        assertEquals("0.10", p.get("unitPrice").asString());
        JsonNode updated = call(HttpMethod.PATCH, ws(a) + "/products/" + p.get("id").asString(), a.token(), Map.of("unitPrice", "19.9"), 200).get("data");
        assertEquals("19.90", updated.get("unitPrice").asString());
    }

    @Test
    @DisplayName("2. Stock adjustments: invalid ones are rejected and stock never goes negative")
    void stockBoundaries() {
        Session a = newOwner();
        String id = createProduct(a, "ST-1", "5.00", 10, 2).get("id").asString();
        String url = ws(a) + "/products/" + id + "/adjustments";

        JsonNode tooMuch = call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", -11, "reasonType", "count_correction"), 409);
        assertEquals("INSUFFICIENT_STOCK", errorCode(tooMuch));
        assertEquals(10, stockOf(id));

        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", 0, "reasonType", "received"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", -1, "reasonType", "received"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", 1, "reasonType", "damaged"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", -1, "reasonType", "other"), 400); // note required
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", 1, "reasonType", "order_reserve"), 400); // system-only
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", 1, "reasonType", "nonsense"), 400);
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", 1.5, "reasonType", "received"), 400);
        assertEquals(10, stockOf(id));

        // Exactly to zero is allowed; one more is not.
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", -10, "reasonType", "damaged", "note", "flood"), 201);
        assertEquals(0, stockOf(id));
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", -1, "reasonType", "damaged"), 409);
        assertEquals(0, stockOf(id));
    }

    @Test
    @DisplayName("Every stock change has an audit row and the history reconciles with current stock")
    void auditTrail() {
        Session a = newOwner();
        Session staff = addStaff(a);
        String id = createProduct(a, "AUD-1", "5.00", 10, 2).get("id").asString();
        String url = ws(a) + "/products/" + id + "/adjustments";
        call(HttpMethod.POST, url, staff.token(), Map.of("quantityChange", 5, "reasonType", "received", "note", "PO-7"), 201);
        call(HttpMethod.POST, url, a.token(), Map.of("quantityChange", -3, "reasonType", "damaged"), 201);

        JsonNode history = get(a, url, 200);
        assertEquals(3, history.get("meta").get("total").asInt()); // initial_stock + 2 adjustments
        JsonNode newest = history.get("data").get(0);
        assertEquals(12, newest.get("resultingStock").asInt());
        assertEquals(-3, newest.get("quantityChange").asInt());
        assertEquals(a.userId().toString(), newest.get("createdBy").get("id").asString());
        assertEquals(staff.userId().toString(), history.get("data").get(1).get("createdBy").get("id").asString());

        long sum = count("SELECT COALESCE(sum(quantity_change),0) FROM inventory_adjustments WHERE product_id = ?::uuid", id);
        assertEquals(stockOf(id), sum, "sum of recorded changes must equal current stock");
        assertEquals(12, stockOf(id));

        // Filters: by reason and date range (UTC days)
        JsonNode damaged = get(a, ws(a) + "/inventory/adjustments?reasonType=damaged&productId=" + id, 200);
        assertEquals(1, damaged.get("meta").get("total").asInt());
        JsonNode future = get(a, ws(a) + "/inventory/adjustments?from=2999-01-01", 200);
        assertEquals(0, future.get("meta").get("total").asInt());
        get(a, ws(a) + "/inventory/adjustments?from=not-a-date", 400);
        get(a, ws(a) + "/inventory/adjustments?pageSize=1000", 400);
    }

    @Test
    @DisplayName("Editing a product cannot change stock (only audited adjustments can)")
    void stockNotEditableViaPatch() {
        Session a = newOwner();
        JsonNode p = createProduct(a, "NE-1", "5.00", 10, 2);
        call(HttpMethod.PATCH, ws(a) + "/products/" + p.get("id").asString(), a.token(), Map.of("stockQuantity", 999, "name", "Renamed"), 200);
        assertEquals(10, stockOf(p.get("id").asString()));
    }

    @Test
    @DisplayName("Search, filters, sorting and pagination are applied in the database")
    void listing() {
        Session a = newOwner();
        createProduct(a, "LS-A", "1.00", 1, 5);   // low stock
        createProduct(a, "LS-B", "2.00", 50, 5);
        String c = createProduct(a, "LS-C", "3.00", 50, 5).get("id").asString();
        call(HttpMethod.PATCH, ws(a) + "/products/" + c, a.token(), Map.of("active", false), 200);

        assertEquals(3, get(a, ws(a) + "/products", 200).get("meta").get("total").asInt());
        assertEquals(1, get(a, ws(a) + "/products?lowStock=true", 200).get("meta").get("total").asInt());
        assertEquals(1, get(a, ws(a) + "/products?active=false", 200).get("meta").get("total").asInt());
        assertEquals(1, get(a, ws(a) + "/products?q=ls-b", 200).get("meta").get("total").asInt());
        assertEquals(0, get(a, ws(a) + "/products?q=%25", 200).get("meta").get("total").asInt(), "LIKE wildcards are escaped");
        JsonNode page = get(a, ws(a) + "/products?pageSize=2&page=2&sort=price&dir=desc", 200);
        assertEquals(1, page.get("data").size());
        assertEquals(3, page.get("meta").get("total").asInt());
        assertEquals(2, page.get("meta").get("totalPages").asInt());
        get(a, ws(a) + "/products?sort=password", 400);
        get(a, ws(a) + "/products?page=0", 400);
    }

    @Test
    @DisplayName("Role matrix: staff can read and adjust stock but cannot manage products or members")
    void roles() {
        Session owner = newOwner();
        Session staff = addStaff(owner);
        String id = createProduct(owner, "RL-1", "5.00", 10, 2).get("id").asString();

        get(staff, ws(owner) + "/products", 200);
        call(HttpMethod.POST, ws(owner) + "/products", staff.token(), Map.of("name", "X", "sku", "NEW", "unitPrice", "1"), 403);
        call(HttpMethod.PATCH, ws(owner) + "/products/" + id, staff.token(), Map.of("active", false), 403);
        call(HttpMethod.POST, ws(owner) + "/products/" + id + "/adjustments", staff.token(), Map.of("quantityChange", 1, "reasonType", "received"), 201);
        call(HttpMethod.GET, ws(owner) + "/members", staff.token(), null, 403);
        call(HttpMethod.POST, ws(owner) + "/members", staff.token(), Map.of("email", "x@y.zz"), 403);
        get(staff, ws(owner) + "/dashboard", 200);
        get(owner, ws(owner) + "/members", 200);
    }
}
