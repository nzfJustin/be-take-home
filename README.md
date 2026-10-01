# Property Management Platform — Backend Take-Home

A simplified property management API built with Kotlin + Spring Boot. This is a starter repository for the backend engineering take-home exercise.

## What's Included

This repo contains a working property management platform with:

- **Entities**: Property managers, properties, units, tenants, leases, rent charges, and manual (offline) payments
- **REST API**: Full CRUD for all entities with pagination and role-based access control
- **Authentication**: JWT-based auth with login endpoint, two roles (TENANT, PROPERTY_MANAGER)
- **Database**: MySQL 8 with Flyway migrations and seed data
- **S3 Integration**: File storage service wired to LocalStack S3
- **SQS Background Worker**: Polling-based job processor with an example job (rent charge generation)
- **Tests**: Unit tests with MockK, controller tests with MockMvc + JWT auth

## Prerequisites

- JDK 17+
- Docker and Docker Compose

## Quick Start

```bash
# Start infrastructure (MySQL + LocalStack)
docker-compose up -d

# Run the API server
./gradlew bootRun

# Run the background worker (in a separate terminal)
./gradlew bootRun --args='--worker.enabled=true'

# Run unit tests
./gradlew test

# Run integration tests (Docker required — spins up ElasticMQ via Testcontainers)
./gradlew integrationTest

# Run both
./gradlew test integrationTest
```

The API starts on `http://localhost:8080`.

## Authentication

All API endpoints (except `/api/auth/**`) require a valid JWT token in the `Authorization` header.

### Login

```bash
# Login as property manager
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "admin@greenfieldproperties.com", "password": "password"}'

# Login as tenant
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "alice.johnson@email.com", "password": "password"}'
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": 1,
  "email": "admin@greenfieldproperties.com",
  "role": "PROPERTY_MANAGER"
}
```

### Using the Token

```bash
curl http://localhost:8080/api/leases \
  -H 'Authorization: Bearer <token>'
```

### Seeded Users

| Email | Password | Role |
|-------|----------|------|
| admin@greenfieldproperties.com | password | PROPERTY_MANAGER |
| alice.johnson@email.com | password | TENANT |
| bob.smith@email.com | password | TENANT |
| carol.williams@email.com | password | TENANT |

### Role-Based Access

- **Property managers** can manage properties, units, tenants, leases, and record manual payments
- **Tenants** can view their own leases and rent charges, save credit cards, and pay their own rent charges by card
- All authenticated users can access lease and rent charge read endpoints

## Pagination

All list endpoints use cursor-based pagination with `startAfterId` and `limit` query parameters. This avoids the performance and consistency problems of offset-based pagination.

```bash
# First page (default limit: 20)
curl http://localhost:8080/api/tenants -H 'Authorization: Bearer <token>'

# Next page — pass the last item's ID as startAfterId
curl 'http://localhost:8080/api/tenants?startAfterId=20&limit=10' \
  -H 'Authorization: Bearer <token>'
```

Response format:
```json
{
  "content": [...],
  "hasMore": true
}
```

## API Endpoints

### Authentication
- `POST   /api/auth/login` — Authenticate and receive JWT token

### Tenants (PM only for list/create/update)
- `GET    /api/tenants` — List all tenants (paginated)
- `GET    /api/tenants/{id}` — Get tenant by ID
- `POST   /api/tenants` — Create tenant
- `PUT    /api/tenants/{id}` — Update tenant

### Properties & Units (PM only)
- `GET    /api/properties` — List all properties (paginated)
- `GET    /api/properties/{id}` — Get property by ID
- `POST   /api/properties` — Create property
- `GET    /api/properties/{id}/units` — List units for a property (paginated)
- `POST   /api/properties/{id}/units` — Create unit

### Leases
- `GET    /api/leases` — List leases (tenants see only their own, PMs see all; paginated)
- `GET    /api/leases/{id}` — Get lease by ID
- `GET    /api/leases?tenantId={id}` — Get leases by tenant (PM only; paginated)
- `POST   /api/leases` — Create lease (PM only)

### Rent Charges
- `GET    /api/rent-charges/{id}` — Get rent charge by ID
- `GET    /api/rent-charges?leaseId={id}` — Get charges by lease (paginated)
- `GET    /api/rent-charges?leaseId={id}&status=PENDING` — Filter by status (paginated)
- `POST   /api/rent-charges/{id}/card-payments` — Pay a rent charge with a saved card (tenant only)

### Payments (PM only)
- `GET    /api/payments?rentChargeId={id}` — Get payments for a charge (paginated)
- `POST   /api/payments` — Record a manual payment

### Cards (tenant only)
- `POST   /api/cards/setup-intent` — Start saving a card (returns a Stripe SetupIntent client secret)
- `POST   /api/cards` — Register a confirmed card
- `GET    /api/cards` — List the caller's saved cards (paginated)

### Stripe
- `POST   /api/stripe/webhook` — Stripe webhook receiver (authenticated by `Stripe-Signature`, not JWT)

See [Stripe Card Payments](#stripe-card-payments) for request/response details.

## Architecture

Code is grouped by business area. Each area has an `*Api` (REST controller), a `*Module`
(business logic, transactions) and a `*DataAccess` (jOOQ queries, generated from the Flyway migrations).

```
src/main/kotlin/com/ender/takehome/
├── auth/            # Login
├── billing/         # Saved cards, Stripe gateway, Stripe webhook
├── config/          # Security, JWT, AWS, Jackson, jOOQ, Stripe configuration
├── dto/             # Request/response DTOs
├── exception/       # Error handling
├── leasing/         # Leases
├── ledger/          # Rent charges and payments (manual and card)
├── model/           # Domain data classes
├── property/        # Properties and units
├── tenant/          # Tenants
└── worker/          # SQS background job processor
```

### Infrastructure

| Service    | Local               | Purpose                        |
|------------|---------------------|--------------------------------|
| MySQL 8    | Docker (port 3306)  | Primary database               |
| LocalStack | Docker (port 4566)  | S3 file storage + SQS queues   |

### Seed Data

The migration creates sample data: 1 property manager, 2 properties, 4 units, 3 tenants, 2 active leases, rent charges with manual payments, and 4 user accounts.

## Stripe Card Payments

Tenants can save credit cards through Stripe and use them to pay their own rent charges. Card numbers never reach this server: cards are collected by Stripe.js using a SetupIntent, and we store only Stripe IDs plus display metadata.

### Setup

1. Get test-mode keys from the Stripe dashboard, then export them before starting the app:

   ```bash
   export STRIPE_SECRET_KEY=sk_test_...
   export STRIPE_WEBHOOK_SECRET=whsec_...   # printed by `stripe listen` below
   ./gradlew bootRun
   ```

   Without these variables the app still starts, using placeholder keys. Only calls to Stripe will fail, with a 502.
2. Forward Stripe webhooks to the local server with the [Stripe CLI](https://stripe.com/docs/stripe-cli):

   ```bash
   stripe listen --forward-to localhost:8080/api/stripe/webhook
   ```

3. Run the tests:

   ```bash
   ./gradlew test               # unit + controller tests, no Docker needed
   ./gradlew integrationTest    # needs Docker; Stripe is replaced by FakePaymentGateway
   ```

### End-to-end walkthrough (test mode)

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice.johnson@email.com","password":"password"}' | jq -r .token)

# 1. Start saving a card
curl -s -X POST localhost:8080/api/cards/setup-intent -H "Authorization: Bearer $TOKEN"
# {"clientSecret":"seti_1Q..._secret_..."}

# 2. The frontend confirms the SetupIntent with Stripe.js (stripe.confirmCardSetup(clientSecret, ...)).
#    Without a frontend, use the Stripe CLI and test card pm_card_visa
#    (the SetupIntent ID is the part of the client secret before "_secret_"):
stripe setup_intents confirm seti_1Q... --payment-method=pm_card_visa
# The response contains the attached payment method, e.g. "payment_method": "pm_1Q..."

# 3. Register the confirmed card
curl -s -X POST localhost:8080/api/cards -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"stripePaymentMethodId":"pm_1Q..."}'
# 201 {"id":1,"brand":"visa","last4":"4242","expMonth":12,"expYear":2030,"createdAt":"..."}

# 4. Pay rent charge 1 with that card
curl -s -X POST localhost:8080/api/rent-charges/1/card-payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"cardId":1}'
# 201 {"id":3,"rentChargeId":1,"amount":2500.00,"paymentMethod":"CARD","status":"SUCCEEDED",
#      "paymentCardId":1,"failureReason":null,"recordedBy":"alice.johnson@email.com",...}

curl -s localhost:8080/api/rent-charges/1 -H "Authorization: Bearer $TOKEN"   # "status":"PAID"
```

### API

All card endpoints are **tenant-only**. The tenant always comes from the JWT and is never taken from the request.

| Endpoint | Request | Response | Errors |
|---|---|---|---|
| `POST /api/cards/setup-intent` | none | `200 {"clientSecret"}` | 502 if Stripe is unavailable |
| `POST /api/cards` | `{"stripePaymentMethodId"}` | `201 PaymentCard` | 400 if the payment method isn't attached to the caller's Stripe customer; 400 if blank |
| `GET /api/cards?startAfterId=&limit=` | none | `200 CursorPage<PaymentCard>` | |
| `POST /api/rent-charges/{id}/card-payments` | `{"cardId"}` | `201 Payment` | 404 if the charge or card isn't the caller's; 409 if the charge is already paid or a payment is in progress; 502 if the Stripe outcome is unknown |
| `POST /api/stripe/webhook` | raw Stripe event, `Stripe-Signature` header | `200` | 400 for an invalid signature |

**Card payment responses:**
- A **declined card** still returns `201` with `"status":"FAILED"` and a `failureReason`. The payment attempt is recorded, and the charge stays unpaid, so the tenant can retry.
- A payment Stripe is still processing returns `"status":"INITIATED"`. A webhook moves it on later.

Registering the same payment method twice is idempotent: the second call returns the existing card.

### Data model additions (`V4__add_stripe_card_payments.sql`)

- **`tenants.stripe_customer_id`:** one Stripe Customer per tenant, created on the tenant's first setup-intent call.
- **`payment_cards`:** `tenant_id`, `stripe_payment_method_id` (unique), `brand`, `last4`, `exp_month`, `exp_year`.
- **`payments`:** gains `status`, `payment_card_id`, `stripe_payment_intent_id` (unique), `failure_reason` and `updated_at`. `payment_method` now also allows `CARD`. Existing manual payments are backfilled to `SUCCEEDED`.

### Payment status lifecycle

```
              ┌──► SUCCEEDED ──► REFUNDED
INITIATED ────┤
              └──► FAILED
```

| Status | Set when | Rent charge |
|---|---|---|
| `INITIATED` | The payment row is created, before Stripe is called. Also covers Stripe `processing` / `requires_action`. | unchanged |
| `SUCCEEDED` | The PaymentIntent succeeded (synchronous response or `payment_intent.succeeded`). Manual payments start here. | `PAID` |
| `FAILED` | Card declined (synchronous response or `payment_intent.payment_failed`). Terminal. | unchanged, can retry |
| `REFUNDED` | `charge.refunded` with a full refund. Terminal. | back to `PENDING` |

**How transitions are enforced:**
- Allowed transitions live in `PaymentStatus.canTransitionTo`.
- Every change is a compare-and-set (`UPDATE … WHERE status = <expected>`), done by `LedgerModule.applyPaymentStatus`.
- The synchronous Stripe response and webhooks share that code path. Duplicate, late or out-of-order events are therefore ignored: for example, a late `payment_failed` can't undo a success.

### How a card payment runs

1. **Transaction 1:**
   - Lock the rent charge row (`SELECT … FOR UPDATE`).
   - Check ownership: charge → lease → tenant.
   - Reject the request if the charge is `PAID` or already has an `INITIATED`/`SUCCEEDED` payment.
   - Insert an `INITIATED` payment.
2. **No transaction:** create and confirm a PaymentIntent (`off_session`, saved card).
   - The idempotency key is `payment-{id}`.
   - `metadata.payment_id` is set to our payment ID.
3. **Transaction 2:** store the PaymentIntent ID and apply its status.

Stripe is never called while database locks are held. If the Stripe call fails in a way where the outcome is unknown (network error, Stripe 5xx):
- The payment stays `INITIATED` and the API returns 502.
- The webhook later resolves it. Webhooks match payments by PaymentIntent ID, or by `metadata.payment_id` if we never stored the ID.

### Assumptions and tradeoffs

- **Charge scope:**
  - Card payments are for the **full charge amount**, in **USD**.
  - `OVERDUE` charges can be paid as well as `PENDING` ones.
- **One card payment table.** Card payments reuse the `payments` table rather than a separate table, so a charge has a single payment history whatever the method.
- **Webhooks are the source of truth.** The synchronous PaymentIntent result is applied immediately for a better experience, through the same idempotent transition.
- **Refunds:**
  - Refunds are issued in the Stripe dashboard and reach us through `charge.refunded`. There is no refund API.
  - Partial refunds are ignored.
- **Webhook processing:** webhooks are processed synchronously. Each one is a single small, idempotent DB transaction, so there's no need to queue them yet.
- **Overlap with manual payments:** manual (cash/check) payments keep their existing behavior. They don't take the charge lock or check for an in-progress card payment.
- **Out of scope:**
  - 3-D Secure / `requires_action` hand-off to the client (such payments stay `INITIATED` until a webhook resolves them).
  - Deleting cards, default cards, partial payments, and fees.

### What I'd do for production / scale

- **Webhook pipeline:**
  - Enqueue verified webhook events to SQS and process them asynchronously.
  - Store processed event IDs to deduplicate before doing any work.
  - Add a dead-letter queue to `SqsWorker`; today a failing message is retried forever.
- **Reconciliation:** run a job that queries Stripe for payments stuck in `INITIATED` (lost responses, missed webhooks). Allow a stuck attempt to be retried or cancelled.
- **Client-side authentication:** support 3-D Secure by returning the PaymentIntent `client_secret` when `requires_action`, and add an on-session confirm flow.
- **Payments product:**
  - A refund endpoint for property managers, with partial refunds.
  - Card deletion (detach in Stripe).
  - Default card; autopay.
- **Ledger correctness:** apply the same charge lock and active-payment check to manual payments.
- **Access control:** fix the existing reads that skip ownership checks (`GET /api/rent-charges/{id}`, `GET /api/leases/{id}`).
- **Observability:** metrics and alerts on payment failure rate, webhook signature failures, and how long payments stay `INITIATED`.
- **Scale:**
  - The design has no global locks; the only contention is per rent charge.
  - Stripe calls happen outside DB transactions, so connection-pool usage stays short.
  - At high volume, watch Stripe rate limits (add retries with backoff, reusing the idempotency keys).

### How AI was used

This feature was built with **Claude Code** (an AI coding agent) in three prompted stages, each reviewed before moving on:

1. **Investigation:** analyze the codebase against `TASK.md` and identify reusable APIs, constraints and existing issues.
2. **Plan:** propose a minimal end-to-end design (data model, lifecycle, endpoints, tests, commit sequence).
3. **Implementation:** implement the plan commit by commit, running the tests at each step.
4. **Verification:** run the app locally against MySQL and Stripe test mode, and debug the environment issues that came up.

The intermediate artifacts are in [`docs/`](docs/), kept as they were produced:

- [`docs/stripe-card-payments-investigation-and-plan.md`](docs/stripe-card-payments-investigation-and-plan.md)
  - the codebase investigation
  - the implementation plan
  - a manual testing guide
  - a step-by-step `curl` walkthrough
- [`docs/debugging.md`](docs/debugging.md)
  - the problems hit while running and testing locally (Docker, Testcontainers, MySQL, Stripe keys, ports), with their causes and fixes

The testing guide in the investigation doc uses the older `docker-compose` command in places. On newer Docker installs, type `docker compose` instead.

**Where the implementation differs from that plan:**
- `OVERDUE` charges are payable too.
- Conflicts use a dedicated `ConflictException` (409).
- An unknown Stripe outcome leaves the payment `INITIATED` instead of marking it `FAILED`, because the card may actually have been charged.
- Webhooks can match payments through `metadata.payment_id`.

**How things were checked:**
- The Stripe SDK (v34) API was checked against the actual JAR rather than from memory; for example, `payment_method_types` has become `allowed_payment_method_types`.
- Webhook parsing is unit-tested with real HMAC signatures.

## Your Task

See **[TASK.md](./TASK.md)** for the assignment.
