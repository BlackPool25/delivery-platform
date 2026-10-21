package com.delivery.auth;

import com.delivery.auth.crypto.Ed25519KeyProvider;
import com.delivery.auth.crypto.JwtTokenService;
import com.delivery.auth.dto.AuthResponse;
import com.delivery.auth.dto.RefreshTokenRequest;
import com.delivery.auth.dto.RequestOtpRequest;
import com.delivery.auth.dto.RequestOtpResponse;
import com.delivery.auth.dto.UserResponse;
import com.delivery.auth.dto.VerifyOtpRequest;
import com.delivery.auth.entity.OtpVerification;
import com.delivery.auth.entity.UserRole;
import com.delivery.auth.exception.InvalidOtpException;
import com.delivery.auth.exception.InvalidTokenException;
import com.delivery.auth.exception.OtpExpiredException;
import com.delivery.auth.repository.OtpVerificationRepository;
import com.delivery.auth.repository.UserRepository;
import com.delivery.auth.service.AuthService;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AuthVerificationGateTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine")
                    .asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("delivery_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private OtpVerificationRepository otpRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Ed25519KeyProvider keyProvider;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("Verification Gate: Request OTP -> Verify OTP -> Issue Ed25519 JWT -> Verify Signature via JWKS")
    void testEndToEndPhoneOtpAndEd25519JwtVerification() {
        String testPhone = "+15551234567";

        // 1. Request OTP
        RequestOtpResponse requestResponse = authService.requestOtp(new RequestOtpRequest(testPhone));
        assertThat(requestResponse).isNotNull();
        assertThat(requestResponse.phone()).isEqualTo(testPhone);

        // Fetch generated OTP from repository
        OtpVerification otp = otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(testPhone)
                .orElseThrow();
        assertThat(otp.getCode()).matches("^\\d{6}$");
        assertThat(otp.isConsumed()).isFalse();

        // 2. Verify OTP with correct code
        VerifyOtpRequest verifyRequest = new VerifyOtpRequest(testPhone, otp.getCode(), UserRole.CUSTOMER);
        AuthResponse authResponse = authService.verifyOtp(verifyRequest);

        assertThat(authResponse).isNotNull();
        assertThat(authResponse.accessToken()).isNotBlank();
        assertThat(authResponse.refreshToken()).isNotBlank();
        assertThat(authResponse.tokenType()).isEqualTo("Bearer");
        assertThat(authResponse.user().phone()).isEqualTo(testPhone);
        assertThat(authResponse.user().role()).isEqualTo(UserRole.CUSTOMER);

        // Verify OTP is now consumed
        OtpVerification consumedOtp = otpRepository.findById(otp.getId()).orElseThrow();
        assertThat(consumedOtp.isConsumed()).isTrue();

        // 3. Cryptographically verify Ed25519 JWT signature
        JWTClaimsSet claims = jwtTokenService.validateAccessToken(authResponse.accessToken());
        assertThat(claims.getSubject()).isEqualTo(authResponse.user().id().toString());
        assertThat(claims.getClaim("phone")).isEqualTo(testPhone);
        assertThat(claims.getClaim("role")).isEqualTo("CUSTOMER");
        assertThat(claims.getIssuer()).isEqualTo("delivery-auth-service");

        // 4. Verify in-memory JWKS public key structure
        Map<String, Object> jwksMap = keyProvider.getJwksJsonObject();
        assertThat(jwksMap).containsKey("keys");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwksMap.get("keys");
        assertThat(keys).hasSize(1);
        Map<String, Object> jwkKey = keys.getFirst();
        assertThat(jwkKey.get("kty")).isEqualTo("OKP");
        assertThat(jwkKey.get("crv")).isEqualTo("Ed25519");
        assertThat(jwkKey.get("alg")).isEqualTo("EdDSA");
        assertThat(jwkKey.get("use")).isEqualTo("sig");
        assertThat(jwkKey.get("kid")).isEqualTo(keyProvider.getKeyId());
        assertThat(jwkKey.get("x")).isNotNull(); // Ed25519 public coordinate

        // 5. Verify Current User from Bearer token
        UserResponse me = authService.getCurrentUserFromToken("Bearer " + authResponse.accessToken());
        assertThat(me.id()).isEqualTo(authResponse.user().id());
        assertThat(me.phone()).isEqualTo(testPhone);

        // 6. Refresh Token flow
        RefreshTokenRequest refreshReq = new RefreshTokenRequest(authResponse.refreshToken());
        AuthResponse refreshedResponse = authService.refreshToken(refreshReq);

        assertThat(refreshedResponse.accessToken()).isNotBlank();
        assertThat(refreshedResponse.refreshToken()).isNotEqualTo(authResponse.refreshToken()); // Rotated
        assertThat(refreshedResponse.user().id()).isEqualTo(authResponse.user().id());

        // Verifying old refresh token is now revoked
        assertThatThrownBy(() -> authService.refreshToken(refreshReq))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("Verification Gate: Invalid or Expired OTP Rejection")
    void testInvalidAndExpiredOtpRejections() {
        String testPhone = "+15559876543";

        // Request OTP
        authService.requestOtp(new RequestOtpRequest(testPhone));
        OtpVerification otp = otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(testPhone)
                .orElseThrow();

        // 1. Submit invalid OTP
        VerifyOtpRequest wrongOtpReq = new VerifyOtpRequest(testPhone, "000000", UserRole.CUSTOMER);
        assertThatThrownBy(() -> authService.verifyOtp(wrongOtpReq))
                .isInstanceOf(InvalidOtpException.class);

        // 2. Simulate expired OTP
        otp.setExpiresAt(Instant.now().minusSeconds(10));
        otpRepository.save(otp);

        VerifyOtpRequest expiredReq = new VerifyOtpRequest(testPhone, otp.getCode(), UserRole.CUSTOMER);
        assertThatThrownBy(() -> authService.verifyOtp(expiredReq))
                .isInstanceOf(OtpExpiredException.class);
    }

    @Test
    @DisplayName("Verification Gate: Tampered JWT signature fails validation")
    void testTamperedJwtSignatureRejection() {
        String testPhone = "+15557778888";
        authService.requestOtp(new RequestOtpRequest(testPhone));
        OtpVerification otp = otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(testPhone).orElseThrow();
        AuthResponse auth = authService.verifyOtp(new VerifyOtpRequest(testPhone, otp.getCode(), UserRole.CUSTOMER));

        // Tamper signature
        String validJwt = auth.accessToken();
        String tamperedJwt = validJwt.substring(0, validJwt.length() - 5) + "abcde";

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(tamperedJwt))
                .isInstanceOf(InvalidTokenException.class);
    }
}
