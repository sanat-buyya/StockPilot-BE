package com.stockpilot.config;

import com.stockpilot.auth.AuthDtos.RegisterRequest;
import com.stockpilot.auth.AuthService;
import com.stockpilot.inventory.InventoryService;
import com.stockpilot.inventory.Reason;
import com.stockpilot.order.OrderService;
import com.stockpilot.order.OrderService.ItemInput;
import com.stockpilot.order.OrderStatus;
import com.stockpilot.product.Product;
import com.stockpilot.product.ProductDtos.CreateProductRequest;
import com.stockpilot.product.ProductService;
import com.stockpilot.workspace.Ctx;
import com.stockpilot.workspace.Role;
import com.stockpilot.workspace.WorkspaceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Idempotent, synthetic demo data (owner + staff + two workspaces). Goes through the real services so the
 * stock levels, audit history and order snapshots are exactly what the app itself would have produced.
 * Enabled with SEED_DEMO_DATA=true; does nothing if the demo owner already exists.
 */
@Component
@ConditionalOnProperty(name = "stockpilot.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    public static final String OWNER_EMAIL = "owner@stockpilot.demo";
    public static final String STAFF_EMAIL = "staff@stockpilot.demo";

    private final JdbcClient jdbc;
    private final AuthService auth;
    private final WorkspaceService workspaces;
    private final ProductService products;
    private final InventoryService inventory;
    private final OrderService orders;
    private final AppProperties props;

    public DemoDataSeeder(JdbcClient jdbc, AuthService auth, WorkspaceService workspaces, ProductService products,
                          InventoryService inventory, OrderService orders, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.workspaces = workspaces;
        this.products = products;
        this.inventory = inventory;
        this.orders = orders;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", OWNER_EMAIL).query(Long.class).single() > 0;
        if (exists) {
            log.info("Demo data already present; skipping seed");
            return;
        }
        String pw = props.seed().password();
        var owner = auth.register(new RegisterRequest(OWNER_EMAIL, "Olivia Owner", pw, "Acme Supplies"));
        var staff = auth.register(new RegisterRequest(STAFF_EMAIL, "Sam Staff", pw, "Sam's Sandbox"));
        UUID ownerId = owner.user().id();
        UUID staffId = staff.user().id();
        UUID acme = owner.workspaces().get(0).id();

        // Staff joins Acme as STAFF; owner also owns a second workspace that reuses SKUs (isolation demo).
        Ctx ownerCtx = new Ctx(ownerId, acme, Role.OWNER);
        workspaces.addMember(ownerCtx, STAFF_EMAIL, Role.STAFF);
        Ctx staffCtx = new Ctx(staffId, acme, Role.STAFF);

        record P(String name, String sku, String price, int stock, int threshold) {}
        List<P> catalogue = List.of(
                new P("Wireless Mouse", "MOUSE-001", "799.00", 120, 20),
                new P("Mechanical Keyboard", "KEYB-001", "2999.00", 45, 10),
                new P("USB-C Cable 1m", "CABLE-USBC-1", "249.00", 300, 50),
                new P("27\" Monitor", "MON-27", "12999.00", 18, 5),
                new P("Laptop Stand", "STAND-001", "1499.00", 8, 10),
                new P("Webcam 1080p", "CAM-1080", "2499.00", 4, 5),
                new P("Desk Mat", "MAT-XL", "599.00", 60, 15),
                new P("HDMI Cable 2m", "CABLE-HDMI-2", "399.00", 150, 30),
                new P("Noise-Cancelling Headset", "HEAD-NC", "4999.00", 22, 8),
                new P("Docking Station", "DOCK-USB4", "7999.00", 0, 3));
        List<Product> created = catalogue.stream().map(p -> products.create(ownerCtx,
                new CreateProductRequest(p.name(), p.sku(), null, new BigDecimal(p.price()), p.stock(), p.threshold(), true))).toList();
        // A retired product: inactive products cannot be ordered.
        products.create(ownerCtx, new CreateProductRequest("Legacy VGA Adapter", "VGA-OLD", "Discontinued", new BigDecimal("99.00"), 5, 0, false));

        inventory.adjust(staffCtx, created.get(2).id(), 100, Reason.RECEIVED, "Supplier delivery PO-1042");
        inventory.adjust(staffCtx, created.get(5).id(), -1, Reason.DAMAGED, "Cracked lens in storage");

        var o1 = orders.create(ownerCtx, List.of(new ItemInput(created.get(0).id(), 10), new ItemInput(created.get(2).id(), 25)), "Office refresh", "seed-order-0001").view();
        orders.apply(ownerCtx, o1.order().id(), OrderStatus.Action.CONFIRM);
        orders.apply(ownerCtx, o1.order().id(), OrderStatus.Action.COMPLETE);
        var o2 = orders.create(staffCtx, List.of(new ItemInput(created.get(3).id(), 2), new ItemInput(created.get(1).id(), 2)), null, "seed-order-0002").view();
        orders.apply(staffCtx, o2.order().id(), OrderStatus.Action.CONFIRM);
        orders.create(staffCtx, List.of(new ItemInput(created.get(6).id(), 5)), "Waiting on payment", "seed-order-0003");
        var o4 = orders.create(ownerCtx, List.of(new ItemInput(created.get(7).id(), 20)), null, "seed-order-0004").view();
        orders.apply(ownerCtx, o4.order().id(), OrderStatus.Action.CANCEL);

        UUID sandbox = staff.workspaces().get(0).id();
        Ctx sandboxCtx = new Ctx(staffId, sandbox, Role.OWNER);
        products.create(sandboxCtx, new CreateProductRequest("Wireless Mouse", "MOUSE-001", "Same SKU as Acme: allowed in another workspace",
                new BigDecimal("699.00"), 10, 2, true));
        log.info("Seeded demo data: {} / {} (workspaces: Acme Supplies, Sam's Sandbox)", OWNER_EMAIL, STAFF_EMAIL);
    }
}
