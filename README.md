# StockPilot API

Multi-workspace inventory and order management. This repository is the **backend** (Spring Boot + PostgreSQL).
The React frontend lives in the repository [StockPilot-FE](https://github.com/sanat-buyya/StockPilot-FE).

| | |
|---|---|
| Deployed app | Frontend: https://sanatstockpilot.vercel.app · API: https://stockpilot-be-rh1l.onrender.com (health: `/actuator/health`) |
| Demo logins | `owner@stockpilot.demo` and `staff@stockpilot.demo`, password `Demo@12345` (synthetic data, loaded with `SEED_DEMO_DATA=true`) |
| Self-registration | Open to anyone: register, and you become the owner of a new workspace |

> Hosting note: free tiers sleep when idle; the first request after a pause can take 30-60 s. See [Deployment](#deployment).

## What is implemented

- Registration/login (BCrypt, signed JWT), multiple workspaces per user, role per workspace (`owner` / `staff`).
- Product catalogue with workspace-scoped unique SKU, exact decimal prices, active/inactive state, search, filters, sorting, pagination.
- Inventory adjustments with a full audit trail (who, why, when, resulting stock) and filters by product, reason and date.
- Multi-line orders with server-side pricing, price/name/SKU snapshots, lifecycle `pending -> confirmed -> completed`, cancellation of pending/confirmed orders.
- Dashboard (counts, order value, status breakdown, recent orders and stock changes).
- Optional `Idempotency-Key` for safe retries of order creation.
- Team management (owner adds an already-registered user by email, changes roles, removes members).

## Stack and architecture

- Java 21, Spring Boot 4.0, Spring Security (stateless JWT resource server), Spring JDBC (`JdbcClient`), Flyway, PostgreSQL 14+.
- Tests: JUnit 5 + MockMvc against a **real PostgreSQL started in-process** (`io.zonky.test:embedded-postgres`), so constraints, row locks and concurrency are tested for real. No Docker needed.

```
controller  (HTTP, validation, picks the required Permission)
   |  WorkspaceAccess.require(auth, workspaceId, permission) -> Ctx(userId, workspaceId, role)
service     (business rules, transaction boundaries)
repository  (SQL; every query is scoped by workspace_id)
PostgreSQL  (constraints are the last line of defence)
```

Packages: `auth`, `workspace` (membership, `Permission`, `Ctx`), `product`, `inventory`, `order`, `dashboard`, `common` (errors, envelopes), `config`.

### Data model

```
users 1--* workspace_members *--1 workspaces
workspaces 1--* products
workspaces 1--* orders 1--* order_items *--1 products
products   1--* inventory_adjustments *--(0..1) orders
```

Key constraints (see `src/main/resources/db/migration/V1__init.sql`):

- `UNIQUE (workspace_id, sku)`; SKUs are stored upper-case, so `abc` and `ABC` collide.
- `CHECK (stock_quantity >= 0)`, `CHECK (unit_price >= 0)`, `CHECK (quantity > 0)`, `CHECK (low_stock_threshold >= 0)`.
- **Composite foreign keys** `(product_id, workspace_id)` and `(order_id, workspace_id)` on `order_items` and `inventory_adjustments`: an order or audit row physically cannot reference another workspace's product.
- `UNIQUE (order_id, product_id)` on `order_items` (one line per product).
- `order_items.line_total = unit_price * quantity` is a CHECK.
- Partial unique index `(order_id, product_id, reason_type) WHERE order_id IS NOT NULL` on adjustments: a second "release" for the same order line is rejected by the database.
- Money is `numeric(12,2)` in the database and `BigDecimal` in Java; the API exposes decimal strings (`"19.99"`). No floats anywhere.
- All timestamps are `timestamptz`, stored and returned in UTC. Date *filters* use IST calendar days (see below).

## Order lifecycle and stock strategy

**Strategy: reserve stock at order creation.**

| Action | Allowed from | Result | Stock effect |
|---|---|---|---|
| create | n/a | `pending` | **Deducted** (recorded as `order_reserve` adjustment) |
| confirm | `pending` | `confirmed` | none |
| complete | `confirmed` | `completed` | none (deduction is kept) |
| cancel | `pending`, `confirmed` | `cancelled` | **Returned once** (recorded as `order_release`) |

Anything else returns `409 INVALID_TRANSITION` (e.g. complete a pending order, cancel a completed or already-cancelled order).

Rules enforced on create: every line is validated and the whole order fails if any line is unknown (400), inactive (409 `INACTIVE_PRODUCT`) or short of stock (409 `INSUFFICIENT_STOCK`, with per-line `requested`/`available`). Duplicate lines for one product are **merged** (quantities summed) before checking stock. Totals come only from the database price; unknown JSON fields such as a client-sent `total` or `unitPrice` are ignored.

### Why it is correct under failure and concurrency

1. **One transaction per operation.** Order + items + stock changes + audit rows commit together or not at all (`TransactionTemplate`; any exception rolls back). A failing last line leaves no order and no stock change (tested).
2. **Row locks.** Creating an order runs `SELECT ... FROM products WHERE id IN (...) ORDER BY id FOR UPDATE`. Locking in id order means two orders with the same products in opposite order cannot deadlock (tested). The locked rows also give a consistent price/stock to compute from.
3. **Conditional UPDATE.** The decrement is `UPDATE products SET stock_quantity = stock_quantity + :d WHERE ... AND stock_quantity + :d >= 0`. Even without the lock this cannot oversell; the `CHECK (stock_quantity >= 0)` constraint is a final backstop.
4. **Cancellation exactly once.** The order row is locked (`FOR UPDATE`) and its status checked first, so concurrent cancels are serialised and the loser sees `cancelled` and gets 409. Independently, the unique index on `order_release` rows makes a double release impossible even if the status check were bypassed. Stock is restored in the same transaction as the status change.
5. **Audit consistency.** Current stock always equals the sum of recorded `quantity_change` rows for a product (asserted in the concurrency tests). Product edits cannot change stock; only adjustments, orders and cancellations can.
6. **Idempotent creation.** `Idempotency-Key` header: a retry returns the original order (200) instead of reserving twice; concurrent same-key requests create exactly one order (unique index + re-check after taking locks).

## Roles and permissions (enforced server-side)

Identity comes only from the verified JWT subject. Membership is re-read from the database on **every** request (a removed member loses access immediately, even with a valid token). A non-member gets **404** (not 403) for any workspace, so ids cannot be probed. A member without permission gets 403.

| Capability | Owner | Staff |
|---|:-:|:-:|
| View products, orders, stock history, dashboard | yes | yes |
| Create / edit / (de)activate products | yes | no |
| Adjust stock | yes | yes |
| Create, confirm, complete, cancel orders | yes | yes |
| View/add/change/remove members | yes | no |

The matrix lives in one place: `workspace/Permission.java`. A workspace must always keep at least one owner. Staff membership demo flow: the staff user registers, then an owner adds them by email on the Team page (or `POST /members`).

## Dashboard metric definitions

All metrics are computed from persisted data; the same definitions apply to the API and the UI. Date range = `from`/`to` (IST days, both inclusive; default: last 30 days).

- **Active products**: products with `is_active = true` (point in time, not range-bound).
- **Low stock**: *active* products with `stock_quantity <= low_stock_threshold` (point in time).
- **Orders by status**: orders **created** in the range, grouped by their *current* status (all four statuses always present, `0` if none).
- **Order value**: sum of `total` of orders created in the range whose status is **not** `cancelled` (pending + confirmed + completed). `valueByStatus` gives the split.
- **Recent orders / recent stock changes**: latest 5 in the workspace, regardless of range.

Date filters everywhere (`from`, `to`) are `YYYY-MM-DD`, interpreted as calendar days in **IST (Asia/Kolkata, UTC+05:30)**, the documented business timezone; `to` includes that whole day. Timestamps themselves are stored and returned in UTC (ISO-8601 with `Z`) and shown in IST by the UI.

## Run locally

Requirements: JDK 21+ (the Maven wrapper downloads Maven). PostgreSQL is optional (see below).

```bash
# 1. a database. Either use your own PostgreSQL, or start the bundled throwaway one (no install, no Docker):
scripts/dev-db.cmd            # Windows; keeps data in ./.pgdata, listens on localhost:54329

# 2. configure (copy values from .env.example into your environment)
export JWT_SECRET="$(node -e "console.log(require('crypto').randomBytes(48).toString('hex'))")"   # or any 32+ char string
export DB_URL=jdbc:postgresql://localhost:54329/postgres DB_USER=postgres   # or your own database
export SEED_DEMO_DATA=true                                                  # optional demo data

# 3. run (Flyway creates the schema automatically on boot)
./mvnw spring-boot:run        # http://localhost:8080  (set PORT to change)
```

On Windows, `scripts/dev-api.cmd` sets sensible local defaults (port 8090, embedded DB, demo data) and starts the API.

### Environment variables

| Name | Required | Purpose |
|---|:-:|---|
| `JWT_SECRET` | yes | HMAC signing key, 32+ chars. App refuses to start without it. |
| `DB_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://host:5432/db?sslmode=require` |
| `DB_USER`, `DB_PASSWORD` | yes | Database credentials |
| `CORS_ORIGINS` | prod | Comma-separated allowed browser origins (your frontend URL) |
| `PORT` | no | default 8080 |
| `JWT_EXPIRES_MINUTES` | no | default 480 |
| `BCRYPT_STRENGTH` | no | default 10 |
| `DB_POOL_MAX` | no | default 10 |
| `AUTH_RATE_LIMIT_PER_MINUTE` | no | per-IP limit on login/register, default 20 |
| `SEED_DEMO_DATA`, `SEED_DEMO_PASSWORD` | no | idempotent synthetic demo data on boot |

No secrets are committed; `.env*` files are git-ignored. Only `.env.example` (placeholders) is tracked.

### Database and seed

Schema = Flyway migrations in `src/main/resources/db/migration`, applied automatically at start-up on an empty database. Demo data: set `SEED_DEMO_DATA=true`; it creates two users, two workspaces (one reusing a SKU to show isolation), 11 products in the main workspace (low-stock, out-of-stock and inactive examples), manual adjustments and orders in every status, all created through the real services so audit history and snapshots are genuine. It does nothing if the demo owner already exists.

## Tests

```bash
./mvnw test
```

36 tests, ~1 minute (the first test boots an embedded PostgreSQL). No setup required.

| Required scenario | Where |
|---|---|
| 1 SKU uniqueness (same vs other workspace) | `ProductAndInventoryTest.skuUniqueness` |
| 2 Stock boundaries | `stockBoundaries`, `auditTrail` |
| 3 Server-side totals, client totals ignored | `OrderLifecycleTest.serverSideTotals` |
| 4 Insufficient stock | `insufficientStock` |
| 5 Multi-item atomicity | `atomicity` |
| 6 Cancel restores once | `cancellationRestoresOnce`, `ConcurrencyTest.concurrentCancelRestoresOnce` |
| 7 Invalid transitions | `invalidTransitions`, `DomainRulesTest` |
| 8 Price snapshots | `priceSnapshots` |
| 9 Workspace isolation | `IsolationAndAuthTest.workspaceIsolation` |
| 10 Concurrency (30 threads / 10 units, deadlock, concurrent cancels, adjustments, idempotency) | `ConcurrencyTest` |

## API

Base: `/api`. List endpoints are paginated with `page` (default 1) and `pageSize` (default 10, max 100; larger values are rejected with 400). Auth: `Authorization: Bearer <token>` (from register/login). Success: `{"data": ..., "meta": {page,pageSize,total,totalPages}?}`. Errors: `{"error": {"code","message","details"?}}` with codes `VALIDATION_ERROR` (400), `UNAUTHENTICATED` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), `CONFLICT` / `DUPLICATE_SKU` / `EMAIL_TAKEN` / `INSUFFICIENT_STOCK` / `INACTIVE_PRODUCT` / `INVALID_TRANSITION` (409), `RATE_LIMITED` (429). Internal errors never expose stack traces or SQL.

| Method & path | Permission | Notes |
|---|---|---|
| `POST /auth/register`, `POST /auth/login` | public | returns `{token, user, workspaces}` |
| `GET /auth/me` | any user | |
| `GET/POST /workspaces` | any user | list mine / create (you become owner) |
| `GET/POST /workspaces/{w}/members`, `PATCH/DELETE .../members/{userId}` | owner | |
| `GET /workspaces/{w}/products` | member | `q, active, lowStock, sort(name,sku,price,stock,created), dir, page, pageSize<=100` |
| `POST /workspaces/{w}/products` | owner | opening stock is recorded as an `initial_stock` adjustment |
| `GET /workspaces/{w}/products/{id}` | member | |
| `PATCH /workspaces/{w}/products/{id}` | owner | name, sku, description, unitPrice, lowStockThreshold, active (not stock) |
| `POST /workspaces/{w}/products/{id}/adjustments` | member | `{quantityChange, reasonType, note}` |
| `GET /workspaces/{w}/products/{id}/adjustments` | member | `from, to, page, pageSize` |
| `GET /workspaces/{w}/inventory/adjustments` | member | `productId, reasonType, from, to, page, pageSize` |
| `GET /workspaces/{w}/orders` | member | `status, q (ORD-000012 or id), from, to, page, pageSize` |
| `POST /workspaces/{w}/orders` | member | optional `Idempotency-Key` header; 201 created / 200 replay |
| `GET /workspaces/{w}/orders/{id}` | member | |
| `POST /workspaces/{w}/orders/{id}/confirm`, `/complete`, `/cancel` | member | |
| `GET /workspaces/{w}/dashboard` | member | `from, to` |
| `GET /actuator/health` | public | health check |

Adjustment reasons: `received` (must be +), `returned` (+), `damaged` (-), `count_correction` (either), `other` (either, note required). System reasons (`initial_stock`, `order_reserve`, `order_release`) cannot be submitted by clients.

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@stockpilot.demo","password":"Demo@12345"}' | jq -r .data.token)
WS=$(curl -s localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN" | jq -r '.data.workspaces[0].id')

curl -s localhost:8080/api/workspaces/$WS/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-order-0001' \
  -d '{"items":[{"productId":"<id>","quantity":2},{"productId":"<id2>","quantity":1}],"notes":"example"}'
```

## Deployment

Backend (Docker, e.g. Render / Fly / Railway): the `Dockerfile` builds a runnable JAR. Provide the environment variables above, with `DB_URL` in JDBC form (`jdbc:postgresql://<host>/<db>?sslmode=require`) from any managed PostgreSQL (Neon, Supabase, Render Postgres). Flyway migrates on first boot. Set `CORS_ORIGINS` to the frontend's exact origin and (for the demo) `SEED_DEMO_DATA=true`. Health check path: `/actuator/health`.

Frontend (`StockPilot-FE`): build with `VITE_API_URL=<api url>`; `vercel.json` / `public/_redirects` provide SPA routing for Vercel/Netlify.

Because auth is a bearer token (no cookies), there is no cross-site cookie / `SameSite` configuration to get wrong; only `CORS_ORIGINS` must match.

## Technical decisions

| Decision | Why |
|---|---|
| **Spring JDBC (`JdbcClient`) instead of JPA/Hibernate** | Stock correctness depends on exact SQL (`FOR UPDATE`, conditional `UPDATE ... WHERE stock + :d >= 0`, `RETURNING`). Writing it explicitly keeps the locking visible and reviewable instead of hidden behind an ORM. |
| **Reserve stock at creation** (not at confirmation) | Pending orders hold stock, so a customer can never be promised units that were sold elsewhere. Cancel has a single, simple reverse path. |
| **Database constraints as the last line of defence** | Non-negative stock, one line per product, composite FKs for workspace isolation and a unique index for release-once mean a bug in application code still cannot corrupt data. |
| **Pessimistic locking, ordered by id** | Contention on one product is expected for a small business; locking is simpler to reason about than optimistic retries, and id ordering prevents deadlocks. |
| **Stateless JWT bearer token** | No cookies, so no cross-site cookie/`SameSite` problems when frontend and API are on different domains. Trade-off: token in `localStorage` (see limitations). |
| **Membership checked on every request** | Roles are not baked into the token, so removing a member or changing a role takes effect immediately. |
| **404 for non-members, 403 for missing permission** | Does not reveal whether a workspace or resource exists. |
| **Money as `numeric(12,2)` / `BigDecimal`, API as strings** | No floating-point rounding; JSON clients cannot lose precision. |
| **Flyway migrations** | Reproducible schema on any fresh database; runs automatically on boot. |
| **Real PostgreSQL in tests (embedded, no Docker)** | H2/mocks cannot prove row locking or constraint behaviour. The embedded binary runs anywhere with a JDK. |
| **Uniform error envelope, no stack traces** | Clients get stable `code`s; internals and SQL are only logged server-side. |
| **Idempotency-Key on order creation** | Double clicks and network retries cannot reserve stock twice. |

## Known limitations and trade-offs

- **Free-tier hosting**: the API (Render) sleeps when idle, so the first request after a pause can take 60 to 90 seconds; the free Render PostgreSQL may be deleted after its trial period, after which the app re-creates the schema and demo data on the next boot.
- The JWT lives in the browser's `localStorage` (simple, cross-origin friendly, but readable by any XSS). Tokens are not revocable before expiry (8 h); membership is nevertheless checked live on every request. No refresh tokens.
- No email verification, password reset or invitations: owners add users who already have accounts. Adding by email reveals to an owner whether an address is registered.
- Login/register rate limiting is in-memory, per instance.
- Orders cannot be edited after creation (cancel and recreate). No partial cancellation or returns.
- Currency is not modelled (single implicit currency; the UI formats amounts as Indian Rupees, ₹).
- Authorization is per-workspace-role only; there is no per-user product ownership or finer permissions.
- Stock history uses offset pagination; fine for this scale, not for millions of rows.
- `order_number` is a global identity sequence, so numbers are unique but not gap-free or per-workspace.

## AI use disclosure

I used an AI coding assistant, **Claude (Claude Code)**, to build most of this project, and I want to be upfront about how.

**What I used it for**
- Writing most of the backend code, the database migration, the tests and the READMEs, based on the assessment brief.
- Writing the first version of the frontend, and then changing it as I asked: moving the backend to Spring Boot, using Redux with Axios on the frontend, switching to rupees and IST, and reshaping the layout, filters and pages.
- Helping me debug problems, like the IntelliJ setup errors and the Render and Vercel deployment steps.

**What I did myself**
- I chose the stack (Spring Boot, React with Tailwind and lucide-react, PostgreSQL, Render and Vercel) and gave feedback on how the screens should look and behave.
- I ran the app, clicked through it, and decided what to keep, change or remove.

**How the code was checked**
- The backend has 36 automated tests that run against a real PostgreSQL, including tests for stock never going negative, atomic multi-item orders, cancelling only once, and several requests competing for the same stock. They pass, including on a fresh clone of the repository.
- The frontend has its own tests, and the app was exercised in a browser against the real API: login, products, stock changes, orders, roles, workspaces and the team page.

**Real bugs found along the way and fixed**
- A fractional quantity like `1.5` was silently accepted as `1`, so I turned that off.
- Filters like `status=cancelled` failed because the conversion was case-sensitive.
- The quantity stepper could lose a click when pressed very fast.
- One of the tests had the wrong expected number. The server was right and the test was wrong.

**Assumptions I made, which a reviewer should check**
- Stock is reserved when an order is created, and given back once if it is cancelled.
- Staff can view things, adjust stock and manage orders, but cannot edit products or manage the team.
- Order value leaves out cancelled orders.
- Date filters use IST calendar days, while timestamps are stored in UTC.

**Things I should be able to explain**
- How an order is created safely in one transaction (`OrderService.doCreate`).
- How cancelling restores stock only once (`OrderService.apply` and `releaseStock`).
- How every request is checked for workspace membership (`WorkspaceAccess.require`).
- Which database constraints protect the data, and why.

I am responsible for everything in this repository, including the parts the AI wrote.
