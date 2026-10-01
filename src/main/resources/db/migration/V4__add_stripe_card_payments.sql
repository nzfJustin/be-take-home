-- ============================================================================
-- Stripe card payments
--
-- * tenants.stripe_customer_id: one Stripe Customer per tenant, created lazily.
-- * payment_cards: cards saved via Stripe SetupIntent. Only Stripe IDs and
--   display metadata are stored, never card numbers.
-- * payments: gains a lifecycle status and Stripe references. Existing
--   (manual) payments are already settled, so status defaults to SUCCEEDED.
-- ============================================================================

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
ALTER TABLE payments ADD CONSTRAINT chk_payments_method CHECK (payment_method IN ('CASH', 'CHECK', 'OTHER', 'CARD'));
ALTER TABLE payments ADD CONSTRAINT chk_payments_status CHECK (status IN ('INITIATED', 'SUCCEEDED', 'FAILED', 'REFUNDED'));

-- Supports the "is there already an active payment for this charge?" check.
CREATE INDEX idx_payments_charge_status ON payments (rent_charge_id, status);
