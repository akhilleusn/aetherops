ALTER TABLE tenants ADD COLUMN stripe_customer_id     VARCHAR(255);
ALTER TABLE tenants ADD COLUMN stripe_subscription_id VARCHAR(255);
ALTER TABLE tenants ADD COLUMN subscription_status     VARCHAR(50);
ALTER TABLE tenants ADD COLUMN current_period_end      TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_tenants_stripe_customer_id ON tenants (stripe_customer_id) WHERE stripe_customer_id IS NOT NULL;
CREATE UNIQUE INDEX idx_tenants_stripe_subscription_id ON tenants (stripe_subscription_id) WHERE stripe_subscription_id IS NOT NULL;
