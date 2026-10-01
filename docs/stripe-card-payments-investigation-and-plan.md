# Codebase Investigation: Stripe Credit Card Rent Payments

Investigation of the existing `be-take-home` codebase against the requirements in `TASK.md` (tenants pay rent charges with saved credit cards via Stripe, with a full payment lifecycle). No code was modified.

---

## 1. Architecture at a glance

- **Stack:** Kotlin, Spring Boot 3.3, jOOQ (not JPA, despite what the README says), MySQL 8 with Flyway, SQS via LocalStack.
- **Organization:** code is grouped by business area, and each area has three layers:

| Layer | Role | Example |
|---|---|---|
| `*Api` | `@RestController`: auth checks, request/response mapping | `ledger/LedgerApi.kt` |
| `*Module` | `@Service`: business logic, `@Transactional` | `ledger/LedgerModule.kt` |
| `*DataAccess` | jOOQ queries plus private `Record.toModel()` mappers | `ledger/LedgerDataAccess.kt` |

- **Models:** plain immutable data classes in `model/`.
- **Status enums:** stored as `VARCHAR` with an SQL `CHECK` constraint.

---

## 2. Main execution flows

### Synchronous request

```
HTTP → JwtAuthenticationFilter (parses JWT → UserPrincipal{userId, role, tenantId, pmId})
     → @PreAuthorize("hasRole(...)") on the Api method
     → XxxApi → XxxModule (@Transactional) → XxxDataAccess (jOOQ) → MySQL
     → XxxResponse.from(model)  |  exceptions → GlobalExceptionHandler
```

### Asynchronous job

This is the template for anything Stripe-related that runs in the background.

```
Api → JobPublisher.publish(BackgroundJobRequest(type, params)) → SQS
SqsWorker.poll() (@Scheduled, only when worker.enabled=true)
     → handlers[type].handleRaw(params) → deserialize → process → deleteMessage
```

Adding a job takes three steps:
1. Add an enum entry in `BackgroundJobType`.
2. Implement `BackgroundJob<T>`.
3. Annotate the implementation with `@Component`.

The worker then discovers it automatically.

### Current payment flow (`LedgerModule.recordPayment`)

`POST /api/payments` (property manager only) → load the charge → insert a `Payment` → set the charge to `PAID`.

- Everything happens in one transaction.
- There is no payment status at all.

---

## 3. Components relevant to the feature

### Ledger (the core area to extend)

- **`model/ManualPayment.kt`**
  - Contains `Payment` and `enum PaymentMethod { CASH, CHECK, OTHER }`.
  - The file name is stale.
  - There is no status or external-reference field.
- **`model/RentCharge.kt`:** `enum RentChargeStatus { PENDING, PAID, OVERDUE }`.
- **`LedgerDataAccess.savePayment`:** insert-only. If `id != 0`, it returns without writing.
- **`LedgerDataAccess.saveCharge`:** a blind status update with no version check and no compare-and-set.
- **`payments` table** (created in V1, renamed in V3):
  - Columns: `amount DECIMAL(10,2)`, `payment_method VARCHAR(20)`, `recorded_by NOT NULL`.
  - Constraint: `CHECK chk_manual_payments_method IN ('CASH','CHECK','OTHER')`.
  - That constraint must be dropped and re-added to allow a card method.

### Identity and ownership

- **Caller identity:** `UserPrincipal.current()` gives `tenantId` for tenant users.
- **Ownership chain:**
  - `users.tenant_id → tenants.id`
  - `rent_charges.lease_id → leases.tenant_id`
  - So a charge is owned via *charge → lease → tenantId*.
  - `LeaseModule.getById` and `LedgerModule.getChargeById` already provide the lookups.
- **Existing ownership-check idiom** (`TenantApi.get`): if the resource isn't the caller's, throw `ResourceNotFoundException`. The caller gets a 404 and can't tell whether the resource exists.
- **Stripe customer link:** `Tenant` has no `stripe_customer_id`. A new column or a separate mapping table is needed.

### Infrastructure to reuse

- **`TransactionHelper.executeWithRetry(isolation, retries)`:** retries on deadlocks and lock-wait timeouts. Good for webhook handling and row locking.
- **`CursorPage`:** `sanitizeLimit`, fetch limit+1, then `of()`. This is the pagination pattern for "list my cards" and "list payments".
- **`JobPublisher` / `BackgroundJob` / `SqsWorker`:** can carry asynchronous webhook processing or reconciliation.
- **`GlobalExceptionHandler`:**
  - `ResourceNotFoundException` → 404.
  - Validation errors and `IllegalArgumentException` (i.e. `require(...)`) → 400.
- **DTO conventions** (`Requests.kt` / `Responses.kt`):
  - Requests use Jakarta validation on `@field:` targets.
  - Each response has a `companion from(model)`.

### Testing

- **Unit tests:** MockK mocks of `*DataAccess` (see `LedgerModuleTest`), with model builders in `TestFixtures`.
- **Controller tests:** `@WebMvcTest` with an inline `TestSecurityConfig` and real JWTs (see `LeaseApiTest`).
- **Integration tests:** `IntegrationTestBase` uses `@SpringBootTest`, H2 in MySQL mode, an ElasticMQ Testcontainer and Awaitility. Tests are tagged `integration`.

---

## 4. Constraints and gotchas that will shape the design

1. **jOOQ code is generated from the migration SQL**, not from a live database (`DDLDatabase` in `build.gradle.kts`). Every migration must:
   - parse in jOOQ's DDL parser, and
   - run on both MySQL 8 and H2.

   Avoid exotic MySQL syntax.
2. **There is no Stripe SDK dependency yet.**
   - Add `com.stripe:stripe-java` and a config bean.
   - Hide Stripe behind your own interface (e.g. `PaymentGateway`) so unit and integration tests can use a fake or `stripe-mock`.
3. **Every route needs a JWT.** In `SecurityConfig`, only `/api/auth/**` is permitAll. A Stripe webhook endpoint needs:
   - its own permitAll rule,
   - access to the raw request body, and
   - `Stripe-Signature` verification.
4. **The worker has weak retry semantics.**
   - If a job fails, the message isn't deleted and reappears after the 30s visibility timeout, forever.
   - There's no dead-letter queue.
   - Messages with an unknown type are also never deleted.

   Any Stripe job must be idempotent.
5. **The charge→PAID transition has no concurrency control.**
   - `recordPayment` doesn't check the amount, the charge's current status, or locks.
   - Paying twice at the same time would double-charge.
   - Needed: some combination of `SELECT … FOR UPDATE`, a conditional `UPDATE … WHERE status=?`, a unique constraint on active payments, and Stripe idempotency keys.
6. **Money is `BigDecimal` with two decimal places; Stripe uses integer minor units (cents).** Centralize that conversion.
7. **There are existing authorization holes in the area you'll touch.**
   - `GET /api/rent-charges/{id}` and `GET /api/rent-charges?leaseId=` have no ownership check, so any tenant can read any charge.
   - `LeaseApi.get` has the same problem.
8. **There's a bug in `LedgerApi.getChargesByLeaseAndStatus`.** Any status other than `PENDING` returns *all* charges unfiltered. `findChargesByLeaseIdAndStatusCursor` already exists to fix this.
9. **`JacksonConfig` defines its own `ObjectMapper` bean.**
   - The `spring.jackson.*` settings in `application.yml` are therefore ignored.
   - `LocalDate` serializes as `[y,m,d]`; the integration test works around this.
10. **The README is out of date.** It describes `/api/manual-payments`, JPA, and `controller/` and `repository/` packages. It needs rewriting anyway as a deliverable.

---

## 5. APIs and code to reuse directly

| Need | Reuse |
|---|---|
| Who the caller is | `UserPrincipal.current().tenantId` |
| Load a charge or lease, with 404 | `LedgerModule.getChargeById`, `LeaseModule.getById` |
| Ownership-check idiom | `TenantApi.get` |
| Paginated lists | `CursorPage.sanitizeLimit` + `CursorPage.of` + `xxxCursorCondition` |
| Retrying transactions | `TransactionHelper.executeWithRetry` |
| Asynchronous processing | `JobPublisher.publish` + new `BackgroundJobType` / `BackgroundJob` |
| Recording an offline payment | Keep `LedgerModule.recordPayment`; card payments become a new path on the same `payments` table |
| Tests | `TestFixtures`, `LedgerModuleTest` style, `LeaseApiTest` security setup, `IntegrationTestBase` |

### Extension points

Build a new `payments`/`billing` area, or extend `ledger`, covering:
- the card setup flow, using a Stripe SetupIntent;
- card-backed payments, using a Stripe PaymentIntent;
- status changes driven by Stripe webhooks.

---
---

# Minimum Implementation Plan: End-to-End Happy Path

**Goal:** the smallest change that lets a tenant:
1. save a card through Stripe;
2. pay one of their own `PENDING` rent charges with it;
3. see the payment move `INITIATED → SUCCEEDED` (or `FAILED` / `REFUNDED`), with the charge becoming `PAID`.

**Approach:** reuse the existing Api → Module → DataAccess layering, jOOQ, Flyway, `UserPrincipal`, `CursorPage` and the testing patterns. Do not introduce new frameworks.

---

## 1. Key design decisions (kept deliberately simple)

| Decision | Choice | Why |
|---|---|---|
| How cards are collected | Stripe **SetupIntent**. The backend returns a `clientSecret`; the client confirms it with Stripe.js/Elements; the backend then registers the resulting `pm_...` | Stripe's recommended flow. Raw card data never touches our server. |
| Where card data lives | We store only the Stripe IDs plus display metadata (`brand`, `last4`, `exp_month`, `exp_year`) | No PCI scope. |
| Stripe customer | One per tenant, created lazily on the first setup-intent call and stored in `tenants.stripe_customer_id` | Smallest model change. |
| How charging works | **PaymentIntent** with `confirm=true`, `off_session=true`, the saved `payment_method`, and amount in cents | Single API call. |
| Where status updates come from | Stripe **webhooks** are the source of truth. The synchronous PaymentIntent response is applied using the same idempotent transition function. | Card payments are asynchronous; this also makes the happy path work immediately. |
| Payment table | **Reuse `payments`** and add a `CARD` method, `status` and the Stripe IDs. Manual payments default to `SUCCEEDED`. | One payment ledger per charge; existing endpoints keep working. |
| Stripe isolation | A `PaymentGateway` interface, with a `StripePaymentGateway` implementation and a fake in tests | Testable without network access or Stripe keys. |
| Refunds | Represented in the lifecycle and driven by the `charge.refunded` webhook. Refunds are issued from the Stripe dashboard; there's no refund API endpoint in the minimum version. | Covers the lifecycle requirement at minimal cost. |
| Currency | USD only | The existing schema has no currency column. |

---

## 2. Payment status lifecycle

```
               ┌──────────► SUCCEEDED ──────► REFUNDED
 INITIATED ────┤
               └──────────► FAILED
```

### Meaning of each status
- **`INITIATED`:** the row was created before calling Stripe. It also covers Stripe's `processing` and `requires_action` states.
- **`SUCCEEDED`:** set by `payment_intent.succeeded`, or by a synchronous `status=succeeded`. The rent charge becomes `PAID` in the same transaction.
- **`FAILED`:** set by `payment_intent.payment_failed`, a synchronous card error, or a Stripe call that throws. The charge stays `PENDING`, so the tenant can retry.
- **`REFUNDED`:** set by `charge.refunded` (full refund). The charge goes back to `PENDING`.
- **Manual (cash/check) payments** are inserted directly as `SUCCEEDED`, as today.

### Transition rules
- Allowed transitions are encoded in the enum: `PaymentStatus.canTransitionTo(next)`.
- Updates are a conditional write: `UPDATE payments SET status=? WHERE id=? AND status=?`.
  - This makes duplicate or out-of-order webhooks harmless.
  - An illegal transition is logged and ignored, not thrown, so Stripe doesn't retry forever.

---

## 3. Database migration: `V4__add_stripe_card_payments.sql`

This must parse in jOOQ's DDL parser and run on both MySQL 8 and H2; plain DDL works on both.

```sql
ALTER TABLE tenants ADD COLUMN stripe_customer_id VARCHAR(255) NULL;
ALTER TABLE tenants ADD CONSTRAINT uk_tenants_stripe_customer UNIQUE (stripe_customer_id);

CREATE TABLE payment_cards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stripe_payment_method_id VARCHAR(255) NOT NULL,
    brand VARCHAR(50) NOT NULL,
    last4 VARCHAR(4) NOT NULL,
    exp_month INT NOT NULL,
    exp_year INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_cards_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uk_payment_cards_pm UNIQUE (stripe_payment_method_id)
);
CREATE INDEX idx_payment_cards_tenant ON payment_cards (tenant_id);

ALTER TABLE payments ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED';
ALTER TABLE payments ADD COLUMN payment_card_id BIGINT NULL;
ALTER TABLE payments ADD COLUMN stripe_payment_intent_id VARCHAR(255) NULL;
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(500) NULL;
ALTER TABLE payments ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE payments ADD CONSTRAINT fk_payments_card FOREIGN KEY (payment_card_id) REFERENCES payment_cards(id);
ALTER TABLE payments ADD CONSTRAINT uk_payments_stripe_pi UNIQUE (stripe_payment_intent_id);
ALTER TABLE payments DROP CONSTRAINT chk_manual_payments_method;
ALTER TABLE payments ADD CONSTRAINT chk_payments_method CHECK (payment_method IN ('CASH','CHECK','OTHER','CARD'));
ALTER TABLE payments ADD CONSTRAINT chk_payments_status CHECK (status IN ('INITIATED','SUCCEEDED','FAILED','REFUNDED'));
CREATE INDEX idx_payments_charge_status ON payments (rent_charge_id, status);
```

> **Verify early:** `DROP CONSTRAINT` on a CHECK works on MySQL ≥ 8.0.16, H2 and the jOOQ parser. If any of them rejects it, fall back to `DROP CHECK`.

---

## 4. Code changes, by layer

### 4.1 Build and config
- **`build.gradle.kts`:** add `implementation("com.stripe:stripe-java:<latest>")`.
- **`application.yml`:**

  ```yaml
  stripe:
    secret-key: ${STRIPE_SECRET_KEY:sk_test_placeholder}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_placeholder}
  ```

  Add the same block with dummy values to both test `application.yml` files.

### 4.2 Models (`model/`)
- **Rename** `ManualPayment.kt` to `Payment.kt`.
- **`PaymentMethod`:** add `CARD`.
- **New `enum PaymentStatus { INITIATED, SUCCEEDED, FAILED, REFUNDED }`**, with `canTransitionTo()`.
- **`Payment`:** add `status`, `paymentCardId?`, `stripePaymentIntentId?`, `failureReason?` and `updatedAt`.
- **New `PaymentCard`** data class: `id`, `tenantId`, `stripePaymentMethodId`, `brand`, `last4`, `expMonth`, `expYear`, `createdAt`.
- **`Tenant`:** add `stripeCustomerId?`.

### 4.3 Stripe gateway (new `billing/` package)
- **`PaymentGateway`** interface, expressed in our own types (no Stripe classes leak out):

  ```kotlin
  fun createCustomer(tenant: Tenant): String                                   // cus_...
  fun createSetupIntent(customerId: String): String                            // client_secret
  fun getCardPaymentMethod(paymentMethodId: String): GatewayCard                // customerId, brand, last4, exp
  fun chargeCard(req: ChargeRequest /* amountCents, customerId, pmId, paymentId */): GatewayPaymentResult  // piId, status, failureReason
  fun parseWebhook(payload: String, signature: String): GatewayEvent?          // null = ignored event type
  ```

- **`StripePaymentGateway`:** a `@Component` that wraps `stripe-java`.
  - Sets the `idempotencyKey` to `"payment-{paymentId}"` for `chargeCard`.
  - Puts `metadata.paymentId` on the PaymentIntent.
  - Maps a card-declined `CardException` to a `FAILED` result.
  - Verifies webhooks with `Webhook.constructEvent`.
- **`StripeConfig`:** sets `Stripe.apiKey` from config.

### 4.4 Cards (`billing/`)
- **`CardDataAccess`:** `findById`, `findByTenantIdCursor`, `save` (jOOQ, same style as the other data-access classes).
- **`TenantDataAccess`:** add `setStripeCustomerIdIfAbsent(tenantId, cusId)`.
  - Implemented as `UPDATE … WHERE stripe_customer_id IS NULL`, then re-read.
  - The re-read handles two concurrent first calls safely.
- **`CardModule`:**
  - **`createSetupIntent(tenantId)`:** ensures the Stripe customer exists, then returns the `clientSecret`.
  - **`registerCard(tenantId, pmId)`:**
    - Fetches the payment method from Stripe.
    - Calls `require(pm.customerId == tenant.stripeCustomerId)`, so a tenant can't register someone else's card.
    - Saves the card idempotently: if this `pm_...` already exists for the tenant, it returns the existing card.
  - **`listCards(tenantId, cursor)`.**
  - **`getOwnedCard(tenantId, cardId)`:** throws a 404 if the card is not the tenant's.
- **`CardApi`:** all endpoints are `@PreAuthorize("hasRole('TENANT')")`, and the tenant ID always comes from `UserPrincipal.current().tenantId`, never from the request.

### 4.5 Card payments (extend `ledger/`)
- **`LedgerDataAccess`:**
  - `findChargeByIdForUpdate` (`SELECT … FOR UPDATE`).
  - `existsActivePaymentForCharge(chargeId)`, where active means status in `INITIATED`/`SUCCEEDED`.
  - `findPaymentById`.
  - `findPaymentByStripePaymentIntentId`.
  - `setStripePaymentIntentId`.
  - `transitionPaymentStatus(id, from, to, failureReason)` → `Boolean` (the conditional update).
  - `savePayment` writes the new columns.
- **`LedgerModule.payWithCard(tenantId, chargeId, cardId)`.** Stripe is never called inside a database transaction.
  1. **Transaction A** (`TransactionHelper.executeWithRetry`):
     - Lock the charge row.
     - Check ownership: charge → lease → `tenantId`; otherwise 404.
     - `require(charge.status == PENDING)`.
     - `require(!existsActivePaymentForCharge)`, which prevents double payment.
     - Insert a `Payment(method=CARD, status=INITIATED, amount=charge.amount, recordedBy=principal.email)`.
  2. **Outside any transaction:** call `gateway.chargeCard(...)`. If it throws, transition the payment to `FAILED` and rethrow as a 502.
  3. **Transaction B:** store the `pi_...` ID, then call `applyPaymentStatus(paymentId, mappedStatus, reason)`.
- **`LedgerModule.applyPaymentStatus(paymentId, newStatus, reason)`.** This single function is shared by the synchronous path and the webhook.
  - Does the conditional transition. If no row changed, it's a duplicate or illegal transition: log it and do nothing.
  - On `SUCCEEDED`: set the charge to `PAID`.
  - On `REFUNDED`: set the charge to `PENDING`.
- **`recordPayment` (manual):** unchanged apart from writing `status=SUCCEEDED`.
- **`LedgerModule` dependencies:** `CardModule`, `LeaseModule`, `PaymentGateway`, `TransactionHelper`. There is no circular dependency.

### 4.6 Webhook (`billing/StripeWebhookApi`)
- **Endpoint:** `POST /api/stripe/webhook` with `@RequestBody payload: String` and the `Stripe-Signature` header.
- **Handling:**
  - Calls `gateway.parseWebhook`. An invalid signature returns 400.
  - Maps the event:
    - `payment_intent.succeeded` → `SUCCEEDED`
    - `payment_intent.payment_failed` → `FAILED`
    - `charge.refunded` (full refund) → `REFUNDED`
  - Looks up the payment by `pi_...` and calls `ledgerModule.applyPaymentStatus`.
  - Unknown events and unknown PaymentIntents are acknowledged with 200.
- **`SecurityConfig`:** add `.requestMatchers("/api/stripe/webhook").permitAll()`. The signature check is the authentication for this endpoint.
- **Processing mode:** synchronous in the minimum version. The handler is a single idempotent update, so it's fast. It can be moved to SQS later if needed.

### 4.7 DTOs and errors
- **`Requests.kt`:**
  - `RegisterCardRequest(@NotBlank stripePaymentMethodId)`
  - `CardPaymentRequest(@NotNull cardId)`
- **`Responses.kt`:**
  - `SetupIntentResponse(clientSecret)`
  - `PaymentCardResponse` (no Stripe secrets)
  - `PaymentResponse`: add `status`, `paymentCardId` and `failureReason`.
- **`GlobalExceptionHandler`:**
  - `IllegalStateException` → 409 Conflict (charge already paid, or a payment already in flight).
  - `PaymentGatewayException` → 502.

---

## 5. API surface (happy path)

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/cards/setup-intent` | TENANT | Returns `{ "clientSecret": "seti_..._secret_..." }` for Stripe.js `confirmCardSetup` |
| `POST /api/cards` `{ "stripePaymentMethodId": "pm_..." }` | TENANT | Registers the confirmed card. Returns `PaymentCardResponse`, 201. |
| `GET /api/cards?startAfterId=&limit=` | TENANT | Lists the tenant's own cards (`CursorPage`) |
| `POST /api/rent-charges/{id}/card-payments` `{ "cardId": 1 }` | TENANT | Pays the tenant's own charge. Returns `PaymentResponse` (status `INITIATED` or `SUCCEEDED`). 201. |
| `GET /api/payments?rentChargeId=` | PM (unchanged) | Now also shows `status` |
| `POST /api/stripe/webhook` | Stripe (signature) | Lifecycle updates |

### End-to-end happy path (manual, test mode)
1. Log in as Alice.
2. `POST /api/cards/setup-intent`.
3. Confirm the SetupIntent with test card `4242…` using Stripe.js. For a quick test without a frontend, `stripe setup_intents confirm seti_... --payment-method pm_card_visa` works.
4. `POST /api/cards` with the returned `pm_...`.
5. `POST /api/rent-charges/1/card-payments` with `{cardId}`. The payment is `SUCCEEDED` and charge 1 becomes `PAID`.
6. Run `stripe listen --forward-to localhost:8080/api/stripe/webhook`. The `payment_intent.succeeded` event that arrives is a no-op, because the transition is idempotent.

---

## 6. Tests (minimum)

### Unit tests (MockK, `LedgerModuleTest` style)
- **`PaymentStatusTest`:** the full allowed/forbidden transition matrix.
- **`LedgerModuleTest`:**
  - `payWithCard`:
    - happy path, where the charge becomes `PAID`;
    - the charge belongs to another tenant → 404;
    - the charge is not `PENDING` → 409;
    - a payment is already active → 409;
    - the card belongs to another tenant → 404;
    - the gateway returns `FAILED` → the payment is `FAILED` and the charge stays `PENDING`.
  - `applyPaymentStatus`:
    - a duplicate event is a no-op;
    - `REFUNDED` sets the charge back to `PENDING`.
- **`CardModuleTest`:**
  - the Stripe customer is created only once;
  - a payment method from another customer is rejected;
  - registering the same payment method twice is idempotent.

### Controller tests (`LeaseApiTest` style)
- **`CardApi`:** a property manager gets 403; a tenant gets 201.

### Integration test (`IntegrationTestBase` + H2)
- **Setup:** override `PaymentGateway` with a `FakePaymentGateway` via `@TestConfiguration @Primary`.
- **`CardPaymentIntegrationTest`:**
  - Main flow: log in as Alice → setup-intent → register card → pay charge 1 → the payment is `SUCCEEDED` and the charge is `PAID`.
  - Bob paying Alice's charge gets 404.
  - Paying twice gets 409.
  - A webhook `charge.refunded` event (the fake parses an unsigned payload) → the payment is `REFUNDED` and the charge is `PENDING`.

---

## 7. Suggested commit sequence

1. `Add Stripe SDK dependency and config properties`
2. `Add V4 migration: payment_cards, stripe customer, payment status lifecycle`
3. `Add PaymentStatus lifecycle and extend Payment/Tenant models` (+ `PaymentStatusTest`)
4. `Add PaymentGateway abstraction with Stripe implementation`
5. `Add card management: setup intent, register and list cards` (+ tests)
6. `Add pay-rent-charge-with-card flow` (+ `LedgerModule` tests)
7. `Add Stripe webhook endpoint for payment lifecycle updates` (+ tests)
8. `Add end-to-end card payment integration test`
9. `Update README and add design/AI artifacts`

---

## 8. Explicitly out of scope for the minimum version (noted for the README)

- **Authentication and refund handling:**
  - 3-D Secure / `requires_action` handoff to the client. The payment stays `INITIATED` until the webhook resolves it.
  - A refund API endpoint; partial refunds.
- **Card management:**
  - Card deletion.
  - Default card.
  - Card expiry handling.
- **Operational hardening:**
  - Asynchronous webhook processing via SQS.
  - A webhook event-ID dedup table.
  - Reconciliation job for stuck `INITIATED` payments.
  - DLQ for the worker.
- **Payment scope:**
  - Partial payments.
  - Multiple currencies.
  - Convenience fees for card payments.
- **Existing issues not fixed here:** the authorization holes on `GET /api/rent-charges` and `GET /api/leases/{id}`, and the status-filter bug. Recommended as small separate commits.

---
---

# Manual Testing Guide

Testing has three parts: automated tests, a manual run against Stripe test mode, and some negative cases. Run everything from the repo root on branch `feature/stripe-card-payments`.

## 0. Prerequisites (one-time)

```bash
git checkout feature/stripe-card-payments

# JDK 17: the project's Gradle setup requires it
brew install --cask temurin@17

# Docker, for MySQL and LocalStack (and for the integration tests)
brew install --cask docker        # or colima: brew install colima docker && colima start

# Stripe CLI, plus a free Stripe account in test mode
brew install stripe/stripe-cli/stripe
stripe login

# jq, used below to extract values from JSON responses
brew install jq
```

## 1. Automated tests

```bash
./gradlew test               # unit + controller tests (47 tests)
./gradlew integrationTest    # end-to-end with a fake Stripe; needs Docker running
```

Open `build/reports/tests/test/index.html` and `build/reports/tests/integrationTest/index.html` for the reports.

## 2. Manual end-to-end test against Stripe test mode

**Terminal 1: forward webhooks**
```bash
stripe listen --forward-to localhost:8080/api/stripe/webhook
# Copy the "whsec_..." secret it prints. Leave this running; it shows each event and our HTTP response.
```

**Terminal 2: start the app**
```bash
docker-compose up -d          # if you ran the app before, it will apply V4 on startup
export STRIPE_SECRET_KEY=sk_test_...      # Dashboard → Developers → API keys (test mode)
export STRIPE_WEBHOOK_SECRET=whsec_...    # from Terminal 1
./gradlew bootRun
```

**Terminal 3: run the flow as Alice, who owns rent charge 1 ($2500, PENDING)**
```bash
login() { curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"password\"}" | jq -r .token; }
ALICE=$(login alice.johnson@email.com)

# 1. Start saving a card
SECRET=$(curl -s -X POST localhost:8080/api/cards/setup-intent -H "Authorization: Bearer $ALICE" | jq -r .clientSecret)
SETI=${SECRET%%_secret_*}; echo $SETI

# 2. Do what Stripe.js would do in a browser: confirm the SetupIntent with Stripe's test card
PM=$(stripe setup_intents confirm $SETI --payment-method=pm_card_visa | jq -r .payment_method); echo $PM

# 3. Register the card
CARD=$(curl -s -X POST localhost:8080/api/cards -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"stripePaymentMethodId\":\"$PM\"}" | tee /dev/stderr | jq -r .id)
curl -s localhost:8080/api/cards -H "Authorization: Bearer $ALICE" | jq

# 4. Pay rent charge 1
curl -s -X POST localhost:8080/api/rent-charges/1/card-payments -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"cardId\":$CARD}" | jq
#   expect: 201, "status": "SUCCEEDED", "paymentMethod": "CARD"

curl -s localhost:8080/api/rent-charges/1 -H "Authorization: Bearer $ALICE" | jq .status   # "PAID"
```

**What to check:**
- **Terminal 1:** `payment_intent.succeeded` arrives with `[200]`. It changes nothing because the payment is already `SUCCEEDED`.
- **Stripe dashboard → Payments:** shows a $2,500.00 payment, with `payment_id` in its metadata.

## 3. Refund (webhook-driven lifecycle)

```bash
PI=$(docker-compose exec -T mysql mysql -uroot -ppassword takehome -N \
  -e "select stripe_payment_intent_id from payments where payment_method='CARD' order by id desc limit 1" 2>/dev/null)
stripe refunds create --payment-intent=$PI
```

**Expected:**
- **Terminal 1:** `charge.refunded` arrives with `[200]`.
- **Rent charge:** back to `"PENDING"`:
  ```bash
  curl -s localhost:8080/api/rent-charges/1 -H "Authorization: Bearer $ALICE" | jq .status
  ```
- **Payment:** now `REFUNDED`, as seen by the property manager:
  ```bash
  PM_TOKEN=$(login admin@greenfieldproperties.com)
  curl -s "localhost:8080/api/payments?rentChargeId=1" -H "Authorization: Bearer $PM_TOKEN" | jq '.content[] | {id, paymentMethod, status}'
  ```

## 4. Negative cases

```bash
# Pay twice → 409. Run this after section 2 and before section 3, or pay again after the refund and then retry.
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/rent-charges/1/card-payments \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d "{\"cardId\":$CARD}"

# Bob saves a card that is declined when charged (Stripe test card pm_card_chargeDeclined)
BOB=$(login bob.smith@email.com)
SECRET=$(curl -s -X POST localhost:8080/api/cards/setup-intent -H "Authorization: Bearer $BOB" | jq -r .clientSecret)
BPM=$(stripe setup_intents confirm ${SECRET%%_secret_*} --payment-method=pm_card_chargeDeclined | jq -r .payment_method)
BCARD=$(curl -s -X POST localhost:8080/api/cards -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' -d "{\"stripePaymentMethodId\":\"$BPM\"}" | jq -r .id)

# Bob pays Alice's charge → 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/rent-charges/1/card-payments \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' -d "{\"cardId\":$BCARD}"

# Bob pays his own charge 3 with the declining card → 201 with "status":"FAILED"; charge 3 stays PENDING
curl -s -X POST localhost:8080/api/rent-charges/3/card-payments -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' -d "{\"cardId\":$BCARD}" | jq '{status, failureReason}'

# Alice registers Bob's payment method → 400
curl -s -X POST localhost:8080/api/cards -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"stripePaymentMethodId\":\"$BPM\"}" | jq

# Alice uses Bob's card → 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/rent-charges/1/card-payments \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d "{\"cardId\":$BCARD}"

# A property manager calls a card endpoint → 403
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/cards -H "Authorization: Bearer $PM_TOKEN"

# Forged webhook → 400
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/stripe/webhook \
  -H 'Stripe-Signature: t=1,v1=bad' -d '{}'
```

## 5. Check the database directly (optional)

```bash
docker-compose exec mysql mysql -uroot -ppassword takehome -e "
  select id, rent_charge_id, payment_method, status, stripe_payment_intent_id, failure_reason from payments;
  select id, tenant_id, brand, last4 from payment_cards;
  select id, stripe_customer_id from tenants;
  select id, status from rent_charges;"
```

## Tips

- **Start over with a clean database:** `docker-compose down -v && docker-compose up -d`, then restart the app. The Stripe customers in your test account remain, which is harmless.
- **Webhooks return 400 "Invalid Stripe signature":** `STRIPE_WEBHOOK_SECRET` doesn't match the secret `stripe listen` printed. Export it and restart `bootRun`.
- **The app returns 502:** usually a missing or wrong `STRIPE_SECRET_KEY`. The app log has the Stripe error.

---
---

# Step-by-Step curl Walkthrough

This is a detailed version of the manual test, with what to expect at each step. You'll use three terminal windows: one for Stripe webhooks, one for the app, and one for the `curl` commands.

> **Note:** newer Docker installs only have `docker compose` (with a space). The older `docker-compose` with a hyphen may not exist. Wherever this file says `docker-compose`, type `docker compose` instead.

## Prerequisite: infrastructure is running

```bash
docker compose up -d
docker compose ps        # wait until mysql shows "(healthy)"
```

## Step 0: Make sure the app has your Stripe keys

The card endpoints call Stripe, so the app needs your **test-mode** keys **when it starts**. If you started `bootRun` without them, stop it (Ctrl+C) and follow this.

1. **Get your secret key:** in the Stripe dashboard, make sure **Test mode** is on, then go to **Developers → API keys** and copy the **Secret key** (`sk_test_...`).
2. **Terminal 1:** start webhook forwarding and leave it running.
   ```bash
   stripe login        # first time only; opens the browser
   stripe listen --forward-to localhost:8080/api/stripe/webhook
   ```
   It prints `Ready! Your webhook signing secret is whsec_...`. Copy that secret.
3. **Terminal 2:** start the app with both keys.
   ```bash
   export STRIPE_SECRET_KEY=sk_test_...
   export STRIPE_WEBHOOK_SECRET=whsec_...
   ./gradlew bootRun
   ```
   Wait for `Started TakeHomeApplicationKt`.

Everything below runs in **Terminal 3**. Variables like `$ALICE` only exist in the window where you set them, so keep using that same window.

## Step 1: Smoke test (no Stripe yet)

```bash
login() { curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"password\"}" | jq -r .token; }

ALICE=$(login alice.johnson@email.com)
echo $ALICE                                   # should print a long eyJ... token

curl -s localhost:8080/api/rent-charges/1 -H "Authorization: Bearer $ALICE" | jq
```

**Expected:** charge 1 for $2500 with `"status": "PENDING"`. This is Alice's seeded charge, and you'll pay it below.

## Step 2: Save a card

A real app collects the card in the browser with Stripe.js. Here the Stripe CLI plays that part.

```bash
# 2a. Ask our API to start a card setup
SECRET=$(curl -s -X POST localhost:8080/api/cards/setup-intent -H "Authorization: Bearer $ALICE" | jq -r .clientSecret)
echo $SECRET                                  # seti_..._secret_...

# 2b. The SetupIntent ID is the part of the client secret before "_secret_"
SETI=${SECRET%%_secret_*}
echo $SETI                                    # seti_...

# 2c. Act as the browser: confirm the setup with Stripe's test Visa card
PM=$(stripe setup_intents confirm $SETI --payment-method=pm_card_visa | jq -r .payment_method)
echo $PM                                      # pm_... (a real saved card on Alice's Stripe customer)

# 2d. Register the saved card with our API
curl -s -X POST localhost:8080/api/cards -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"stripePaymentMethodId\":\"$PM\"}" | jq
```

**Expected from 2d:** `{"id": 1, "brand": "visa", "last4": "4242", ...}`. Save the card's ID:

```bash
CARD=1                                        # use the "id" from the response above
curl -s localhost:8080/api/cards -H "Authorization: Bearer $ALICE" | jq    # lists Alice's cards
```

## Step 3: Pay rent with the card

```bash
curl -s -X POST localhost:8080/api/rent-charges/1/card-payments -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"cardId\":$CARD}" | jq
```

**Expected:** `"status": "SUCCEEDED"`, `"paymentMethod": "CARD"`, `"amount": 2500.00`.

**Verify:**
- **Charge status:**
  ```bash
  curl -s localhost:8080/api/rent-charges/1 -H "Authorization: Bearer $ALICE" | jq .status   # "PAID"
  ```
- **Terminal 1:** shows `payment_intent.succeeded` with `[200]`. That's the webhook arriving; it's a harmless duplicate because the payment already succeeded.
- **Stripe dashboard → Payments:** a $2,500.00 payment appears.

**Double payment is blocked:** running the same pay command again should now print `409`:
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/rent-charges/1/card-payments \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d "{\"cardId\":$CARD}"
```

## Step 4: Refund it (tests the webhook lifecycle)

```bash
# Look up the Stripe PaymentIntent ID in our database
PI=$(docker compose exec -T mysql mysql -uroot -ppassword takehome -N \
  -e "select stripe_payment_intent_id from payments where payment_method='CARD' order by id desc limit 1" 2>/dev/null)
echo $PI                                      # pi_...

stripe refunds create --payment-intent=$PI
```

**Expected:**
- **Terminal 1:** `charge.refunded` arrives with `[200]`.
- **Rent charge:** back to `"PENDING"`:
  ```bash
  curl -s localhost:8080/api/rent-charges/1 -H "Authorization: Bearer $ALICE" | jq .status
  ```
- **Payment:** now `REFUNDED`, as the property manager sees it:
  ```bash
  PM_TOKEN=$(login admin@greenfieldproperties.com)
  curl -s "localhost:8080/api/payments?rentChargeId=1" -H "Authorization: Bearer $PM_TOKEN" \
    | jq '.content[] | {id, paymentMethod, status}'
  ```

## Step 5: Negative cases (optional)

Run the commands in **Manual Testing Guide → 4. Negative cases** above: a declined card, Bob paying Alice's charge, forged webhooks, and so on. Use `docker compose` with a space wherever that section says `docker-compose`.

## If something goes wrong

| Symptom | Cause / fix |
|---|---|
| `zsh: command not found: docker-compose` | Use `docker compose` (with a space) |
| `bootRun` fails with "Communications link failure" / "Connection refused" | MySQL isn't running. Run `docker compose up -d`, wait for "(healthy)", then rerun. |
| `jq: command not found` | `brew install jq` |
| `stripe: command not found` | `brew install stripe/stripe-cli/stripe`, then `stripe login` |
| `$ALICE` is empty or `null` | The app isn't running, or the `login` function wasn't defined in this window |
| setup-intent returns **502** | `STRIPE_SECRET_KEY` is missing or wrong. The app log in Terminal 2 has the Stripe error. Export it and restart `bootRun`. |
| `stripe setup_intents confirm` says "No such setupintent" | The Stripe CLI is logged into a different Stripe account than your `sk_test_` key. Run `stripe login` again with the same account. |
| Registering the card returns **400** "does not belong to this tenant" | The `pm_...` came from a different SetupIntent or customer. Redo step 2 in order. |
| Terminal 1 shows `[400]` for events | `STRIPE_WEBHOOK_SECRET` doesn't match what `stripe listen` printed. Export it and restart `bootRun`. |
| Paying returns **409** on the first try | Charge 1 was already paid in an earlier run. Refund it (step 4), or reset with `docker compose down -v && docker compose up -d` and restart the app. |
| A command returns **401/403** | The token expired (24h) or is the wrong role. Run `login` again. |
