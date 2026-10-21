-- Flyway Migration: V1__init_auth.sql
-- Database: auth_db / delivery_db (Auth Service)
-- Architecture Constraint: Passwordless phone OTP auth backed by local-sms-gateway,
-- issuing asymmetric Ed25519 JWTs and publishing public keys via in-memory JWKS.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone VARCHAR(32) NOT NULL UNIQUE,
    role VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER',
    building VARCHAR(128),
    floor VARCHAR(32),
    room VARCHAR(32),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_phone ON users (phone);
CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);

-- 2. OTP Verifications Table
CREATE TABLE IF NOT EXISTS otp_verifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone VARCHAR(32) NOT NULL,
    code VARCHAR(8) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT false,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otp_verifications_phone_active 
    ON otp_verifications (phone, consumed, expires_at);

-- 3. Refresh Tokens Table (stores cryptographic SHA-256 hash of refresh token)
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash 
    ON refresh_tokens (token_hash) WHERE revoked = false;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user 
    ON refresh_tokens (user_id);
