# Debugging Guide: Stripe Card Payments (be-take-home)

These are the problems hit while testing the `feature/stripe-card-payments` branch locally. Each entry gives the symptom, the cause and the fix. It ends with a quick table for smaller issues.

---

## 1. Integration tests fail: "Could not find a valid Docker environment"

**Symptom**

```
CardPaymentIntegrationTest > initializationError FAILED
    java.lang.IllegalStateException at DockerClientProviderStrategy.java:277
RentChargeGenerationIntegrationTest > initializationError FAILED
```

The stack trace contains `Could not find a valid Docker environment`.

**Cause**

The integration tests start a throwaway SQS container (ElasticMQ) through Testcontainers, which needs a running Docker engine. `initializationError` means the tests never ran: setup failed before the first test.

**Fix**

```bash
open -a Docker          # start Docker Desktop
docker info             # wait until this succeeds (prints server info, no error)
./gradlew integrationTest
```

Docker Desktop doesn't start automatically after a reboot, so run `open -a Docker` again when needed.

**If it still fails with Docker running**

Look in the test output for `DockerDesktopClientProviderStrategy: failed with exception BadRequestException (Status 400 ...`.

- **Cause:** that means the Testcontainers version is too old for the Docker Engine. Docker Engine 29 rejects API versions older than 1.40.
- **Already fixed:** the branch sets this in `build.gradle.kts`:
  ```kotlin
  extra["testcontainers.version"] = "1.21.4"
  ```
- **Why it's a property:** Spring Boot's dependency management pins Testcontainers (1.19.8 for Boot 3.3.5) and silently overrides the `testcontainers-bom` version. Setting the `testcontainers.version` property is what actually changes it.
- **Check the version in use:**
  ```bash
  ./gradlew dependencyInsight --dependency org.testcontainers:testcontainers --configuration integrationTestRuntimeClasspath
  ```

**If the second test class fails with "Connection refused" or "queue does not exist"**

- **Cause:** the SQS container was being stopped after the first test class, while the cached Spring context still pointed at it.
- **Already fixed:** `IntegrationTestBase` now starts one shared (singleton) container for the whole test run.

---

## 2. `zsh: command not found: docker-compose`

**Cause**

Newer Docker Desktop versions no longer ship the old standalone `docker-compose` (with a hyphen). Compose is built into the Docker CLI as `docker compose` (with a space).

**Fix**

Use the space form everywhere. It reads the same `docker-compose.yml`:

```bash
docker compose up -d
docker compose ps
docker compose exec mysql mysql -uroot -ppassword takehome
```

To keep typing the old form, add an alias to `~/.zshrc`:

```bash
alias docker-compose='docker compose'
```

---

## 3. `bootRun` fails: "Communications link failure" / "Connection refused"

**Symptom**

```
HikariPool-1 - Starting...        (repeated ~5 times)
Error creating bean with name 'flywayInitializer' ... Unable to obtain connection from database: Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago. The driver has not received any packets from the server.
...
Caused by: java.net.ConnectException: Connection refused
```

**Cause**

The app connects to MySQL at `localhost:3306`, which `docker-compose.yml` provides, and nothing was listening there.

- **First failure:** Flyway runs its migrations on startup, so it's the first thing that needs the database.
- **The rest is fallout:** the `authDataAccess` / `authApi` bean errors happen only because Flyway failed.
- **What the driver message means:** "0 milliseconds ago" means the driver never reached a server. It's not a password, schema or migration problem.

Unit tests and integration tests don't need MySQL (they use an in-memory H2 database), so `bootRun` is the first thing that needs it.

**Fix**

```bash
docker compose up -d
docker compose ps        # wait until mysql shows "(healthy)"; ~10–20s on first start
./gradlew bootRun
```

**Check what's running:**

```bash
docker compose ps -a                        # mysql and localstack should be "running" / "healthy"
lsof -nP -iTCP:3306 -sTCP:LISTEN            # something should be listening on 3306
docker compose logs mysql | tail -30        # look for startup errors
```

If you start `bootRun` before MySQL reports "(healthy)", you get this same error. Wait and retry.

---

## 4. Card endpoints return 502: "Invalid API Key provided: sk_test_*******lder"

**Symptom**

`POST /api/cards/setup-intent` returns **502**, and the app log shows:

```
PaymentGatewayException: Stripe failed to create customer: Invalid API Key provided: sk_test_*******lder
Caused by: com.stripe.exception.AuthenticationException: Invalid API Key provided: sk_test_*******lder
```

**Cause**

`...lder` is the end of `sk_test_placeholder`, the fallback in `src/main/resources/application.yml`:

```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:sk_test_placeholder}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_placeholder}
```

The app falls back to this placeholder when `STRIPE_SECRET_KEY` isn't set **in the terminal that started `bootRun`**. Stripe rejects the placeholder.

The app is behaving as designed: it logs the Stripe error and returns 502 rather than crashing.

**Fix**

In the terminal where you run the app:

1. **Stop the app** with Ctrl+C.
2. **Export the keys** (no spaces around `=`):
   ```bash
   export STRIPE_SECRET_KEY=sk_test_51...           # Stripe Dashboard → Test mode → Developers → API keys
   export STRIPE_WEBHOOK_SECRET=whsec_...           # printed by `stripe listen`
   ```
3. **Verify** before starting:
   ```bash
   echo ${STRIPE_SECRET_KEY:0:12}      # must print sk_test_..., not empty
   echo ${STRIPE_WEBHOOK_SECRET:0:8}   # must print whsec_...
   ```
4. **Start the app:** `./gradlew bootRun`.

**Common reasons the variable goes missing**

- **Different window.** You exported in a different terminal window or tab. `export` only affects the current window.
- **Spaces around `=`.** `export STRIPE_SECRET_KEY = sk_test_...` doesn't work in zsh.
- **Exported too late.** You exported after the app had already started. It only reads the key at startup.

**If it still shows `...lder`**

Pass the keys inline, or stop the Gradle daemon and retry:

```bash
STRIPE_SECRET_KEY=sk_test_... STRIPE_WEBHOOK_SECRET=whsec_... ./gradlew bootRun
# or
./gradlew --stop && ./gradlew bootRun
```

**Safety**

- Use the **test-mode** key (`sk_test_...`), never `sk_live_...`.
- Never commit the key or paste it into chats or tickets.

---

## 5. Webhooks show `[400]` in the `stripe listen` terminal

**Cause**

`STRIPE_WEBHOOK_SECRET` doesn't match the signing secret that `stripe listen` printed, or it's still `whsec_placeholder`. The app rejects the signature, as it should.

**Fix**

1. Copy the `whsec_...` value from the `stripe listen` output.
2. Export it in the app's terminal.
3. Restart `bootRun`.

Each new `stripe listen` session can print a different secret, so re-export it if you restart the listener.

---

## Quick reference

| Symptom | Cause / fix |
|---|---|
| `initializationError` in integration tests | Docker isn't running: `open -a Docker`, wait for `docker info` (section 1) |
| `Status 400` from `DockerDesktopClientProviderStrategy` | Testcontainers too old for Docker 29; fixed on the branch (section 1) |
| `command not found: docker-compose` | Use `docker compose` (section 2) |
| `bootRun`: Communications link failure / Connection refused | MySQL isn't running: `docker compose up -d`, wait for "(healthy)" (section 3) |
| 502 with `sk_test_*******lder` | `STRIPE_SECRET_KEY` not set when the app started (section 4) |
| `stripe listen` shows `[400]` | `STRIPE_WEBHOOK_SECRET` doesn't match (section 5) |
| `jq: command not found` | `brew install jq` |
| `stripe: command not found` | `brew install stripe/stripe-cli/stripe`, then `stripe login` |
| `$ALICE` empty or `null` | App not running, or the `login` shell function wasn't defined in this window |
| `stripe setup_intents confirm` says "No such setupintent" | The Stripe CLI is logged into a different account than your `sk_test_` key: run `stripe login` again |
| Registering a card returns 400 "does not belong to this tenant" | The `pm_...` came from another SetupIntent or customer: redo the card setup in order |
| Paying returns 409 on the first try | Charge already paid in an earlier run: refund it, or reset with `docker compose down -v && docker compose up -d` |
| 401 / 403 on a request | Token expired (24h) or wrong role: log in again |
| Gradle: "Cannot find a Java installation ... languageVersion=17" | Install JDK 17: `brew install --cask temurin@17` |
