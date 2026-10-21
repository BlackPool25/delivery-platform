package com.delivery.auth.crypto;

import com.delivery.auth.entity.User;
import com.delivery.auth.exception.InvalidTokenException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String ISSUER = "delivery-auth-service";

    private final Ed25519KeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    @Value("${auth.jwt.refresh-token-validity-seconds:604800}") // 7 days
    private long refreshTokenValiditySeconds;

    public JwtTokenService(Ed25519KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenValiditySeconds);

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                .keyID(keyProvider.getKeyId())
                .type(JOSEObjectType.JWT)
                .build();

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString())
                .claim("userId", user.getId().toString())
                .claim("phone", user.getPhone())
                .claim("role", user.getRole().name())
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        try {
            signedJWT.sign(keyProvider.getSigner());
            return signedJWT.serialize();
        } catch (JOSEException e) {
            log.error("Failed to sign JWT with Ed25519 key", e);
            throw new IllegalStateException("JWT signing failure", e);
        }
    }

    public JWTClaimsSet validateAccessToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);

            if (!signedJWT.verify(keyProvider.getVerifier())) {
                throw new InvalidTokenException("Invalid JWT signature");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            Date expirationTime = claims.getExpirationTime();
            if (expirationTime == null || expirationTime.before(new Date())) {
                throw new InvalidTokenException("JWT token has expired");
            }

            if (!ISSUER.equals(claims.getIssuer())) {
                throw new InvalidTokenException("Invalid token issuer: " + claims.getIssuer());
            }

            return claims;
        } catch (ParseException | JOSEException e) {
            log.warn("Failed to parse or verify JWT token: {}", e.getMessage());
            throw new InvalidTokenException("Malformed or unparseable token: " + e.getMessage());
        }
    }

    public String generateRefreshTokenString() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing in JVM", e);
        }
    }

    public long getAccessTokenValiditySeconds() {
        return accessTokenValiditySeconds;
    }

    public long getRefreshTokenValiditySeconds() {
        return refreshTokenValiditySeconds;
    }
}
