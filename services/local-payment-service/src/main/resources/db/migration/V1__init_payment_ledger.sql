-- Flyway Migration: V1__init_payment_ledger.sql
-- Database: delivery_db (local-payment-service)

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Double-Entry Ledger Accounts
CREATE TABLE IF NOT EXISTS ledger_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    balance NUMERIC(14, 2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0),
    held_balance NUMERIC(14, 2) NOT NULL DEFAULT 0.00 CHECK (held_balance >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_held_lte_balance CHECK (held_balance <= balance)
);

CREATE INDEX IF NOT EXISTS idx_ledger_accounts_user_id ON ledger_accounts(user_id);

-- 2. Journal Entries (Immutable Financial Audit Log)
CREATE TABLE IF NOT EXISTS journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    source_account_id UUID NOT NULL REFERENCES ledger_accounts(id),
    destination_account_id UUID REFERENCES ledger_accounts(id),
    amount NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    entry_type VARCHAR(32) NOT NULL, -- 'HOLD', 'CAPTURE', 'REFUND'
    status VARCHAR(32) NOT NULL,     -- 'SUCCESS', 'FAILED'
    failure_reason VARCHAR(255),
    idempotency_key UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_journal_entries_order_id ON journal_entries(order_id);
CREATE INDEX IF NOT EXISTS idx_journal_entries_source_acc ON journal_entries(source_account_id);
CREATE INDEX IF NOT EXISTS idx_journal_entries_dest_acc ON journal_entries(destination_account_id);
CREATE INDEX IF NOT EXISTS idx_journal_entries_idempotency ON journal_entries(idempotency_key);
