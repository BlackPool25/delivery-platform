package com.delivery.auth.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class Ed25519KeyProvider {

    private static final Logger log = LoggerFactory.getLogger(Ed25519KeyProvider.class);

    private final OctetKeyPair keyPair;
    private final OctetKeyPair publicKey;
    private final JWKSet jwkSet;
    private final Ed25519Signer signer;
    private final Ed25519Verifier verifier;
    private final String keyId;

    public Ed25519KeyProvider() {
        try {
            this.keyId = "auth-ed25519-" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Generating asymmetric Ed25519 key pair with kid: {}", keyId);

            this.keyPair = new OctetKeyPairGenerator(Curve.Ed25519)
                    .keyID(this.keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.EdDSA)
                    .generate();

            this.publicKey = this.keyPair.toPublicJWK();
            this.jwkSet = new JWKSet(this.publicKey);
            this.signer = new Ed25519Signer(this.keyPair);
            this.verifier = new Ed25519Verifier(this.publicKey);

            log.info("Ed25519 key pair successfully initialized. JWKS public key: {}", this.publicKey.toJSONString());
        } catch (JOSEException e) {
            log.error("Failed to generate Ed25519 cryptographic key pair", e);
            throw new IllegalStateException("Crypto initialization failure for Ed25519", e);
        }
    }

    public OctetKeyPair getKeyPair() {
        return keyPair;
    }

    public OctetKeyPair getPublicKey() {
        return publicKey;
    }

    public JWKSet getJwkSet() {
        return jwkSet;
    }

    public Map<String, Object> getJwksJsonObject() {
        return jwkSet.toJSONObject();
    }

    public Ed25519Signer getSigner() {
        return signer;
    }

    public Ed25519Verifier getVerifier() {
        return verifier;
    }

    public String getKeyId() {
        return keyId;
    }
}
