package com.delivery.auth.service;

import com.delivery.auth.client.SmsGatewayClient;
import com.delivery.auth.crypto.JwtTokenService;
import com.delivery.auth.dto.AuthResponse;
import com.delivery.auth.dto.RefreshTokenRequest;
import com.delivery.auth.dto.RequestOtpRequest;
import com.delivery.auth.dto.RequestOtpResponse;
import com.delivery.auth.dto.UserResponse;
import com.delivery.auth.dto.VerifyOtpRequest;
import com.delivery.auth.entity.OtpVerification;
import com.delivery.auth.entity.RefreshToken;
import com.delivery.auth.entity.User;
import com.delivery.auth.entity.UserRole;
import com.delivery.auth.exception.InvalidOtpException;
import com.delivery.auth.exception.InvalidTokenException;
import com.delivery.auth.exception.OtpExpiredException;
import com.delivery.auth.exception.OtpMaxAttemptsExceededException;
import com.delivery.auth.exception.UserNotFoundException;
import com.delivery.auth.repository.OtpVerificationRepository;
import com.delivery.auth.repository.RefreshTokenRepository;
import com.delivery.auth.repository.UserRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_OTP_ATTEMPTS = 3;

    private final UserRepository userRepository;
    private final OtpVerificationRepository otpRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final SmsGatewayClient smsGatewayClient;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.otp.validity-seconds:300}")
    private long otpValiditySeconds;

    public AuthService(UserRepository userRepository,
                       OtpVerificationRepository otpRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenService jwtTokenService,
                       SmsGatewayClient smsGatewayClient) {
        this.userRepository = userRepository;
        this.otpRepository = otpRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenService = jwtTokenService;
        this.smsGatewayClient = smsGatewayClient;
    }

    @Transactional
    public RequestOtpResponse requestOtp(RequestOtpRequest request) {
        String phone = request.phone().trim();
        log.info("Requesting OTP for phone: {}", phone);

        // Invalidate prior unconsumed OTPs for this phone number
        otpRepository.invalidateExistingOtps(phone);

        // Generate 6-digit numeric OTP code
        int codeInt = 100000 + secureRandom.nextInt(900000);
        String code = String.valueOf(codeInt);

        Instant expiresAt = Instant.now().plusSeconds(otpValiditySeconds);
        OtpVerification otp = new OtpVerification(phone, code, expiresAt);
        otpRepository.save(otp);

        // Dispatch SMS via local-sms-gateway
        smsGatewayClient.sendOtpSms(phone, code);

        return new RequestOtpResponse(
                phone,
                "Verification code dispatched via SMS",
                otpValiditySeconds
        );
    }

    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        String phone = request.phone().trim();
        String code = request.code().trim();

        log.info("Verifying OTP for phone: {}", phone);

        OtpVerification otp = otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(phone)
                .orElseThrow(() -> new InvalidOtpException("No active OTP request found for phone: " + phone));

        if (Instant.now().isAfter(otp.getExpiresAt())) {
            otp.setConsumed(true);
            otpRepository.save(otp);
            throw new OtpExpiredException("Verification code has expired. Please request a new one.");
        }

        if (otp.getAttempts() >= MAX_OTP_ATTEMPTS) {
            otp.setConsumed(true);
            otpRepository.save(otp);
            throw new OtpMaxAttemptsExceededException("Maximum verification attempts exceeded. Please request a new OTP.");
        }

        if (!otp.getCode().equals(code)) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepository.save(otp);
            throw new InvalidOtpException("Invalid verification code. Attempts remaining: " + (MAX_OTP_ATTEMPTS - otp.getAttempts()));
        }

        // Mark OTP as successfully consumed
        otp.setConsumed(true);
        otpRepository.save(otp);

        // Look up or provision new user account
        UserRole assignedRole = request.role() != null ? request.role() : UserRole.CUSTOMER;
        User user = userRepository.findByPhone(phone)
                .orElseGet(() -> {
                    log.info("Provisioning new user account for phone: {} with role: {}", phone, assignedRole);
                    User newUser = new User(phone, assignedRole);
                    return userRepository.save(newUser);
                });

        // Issue asymmetric Ed25519 JWT access token
        String accessToken = jwtTokenService.generateAccessToken(user);

        // Issue persistent refresh token
        String rawRefreshToken = jwtTokenService.generateRefreshTokenString();
        String tokenHash = jwtTokenService.hashToken(rawRefreshToken);
        Instant refreshExpiry = Instant.now().plusSeconds(jwtTokenService.getRefreshTokenValiditySeconds());

        RefreshToken refreshTokenEntity = new RefreshToken(user, tokenHash, refreshExpiry);
        refreshTokenRepository.save(refreshTokenEntity);

        log.info("Authentication successful for user: {} ({})", user.getId(), user.getPhone());

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                "Bearer",
                jwtTokenService.getAccessTokenValiditySeconds(),
                mapToUserResponse(user)
        );
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String rawToken = request.refreshToken().trim();
        String tokenHash = jwtTokenService.hashToken(rawToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or revoked refresh token"));

        if (Instant.now().isAfter(refreshToken.getExpiresAt())) {
            refreshToken.setRevoked(true);
            refreshTokenRepository.save(refreshToken);
            throw new InvalidTokenException("Refresh token has expired");
        }

        User user = refreshToken.getUser();
        if (!user.isActive()) {
            throw new InvalidTokenException("User account is inactive or disabled");
        }

        // Rotate refresh token: revoke current, issue new
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        String newRawRefreshToken = jwtTokenService.generateRefreshTokenString();
        String newTokenHash = jwtTokenService.hashToken(newRawRefreshToken);
        Instant newExpiry = Instant.now().plusSeconds(jwtTokenService.getRefreshTokenValiditySeconds());

        RefreshToken newRefreshTokenEntity = new RefreshToken(user, newTokenHash, newExpiry);
        refreshTokenRepository.save(newRefreshTokenEntity);

        // Issue fresh access token
        String newAccessToken = jwtTokenService.generateAccessToken(user);

        log.info("Successfully refreshed session for user: {}", user.getId());

        return new AuthResponse(
                newAccessToken,
                newRawRefreshToken,
                "Bearer",
                jwtTokenService.getAccessTokenValiditySeconds(),
                mapToUserResponse(user)
        );
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUserFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new InvalidTokenException("Missing or malformed Authorization header");
        }
        String token = authHeader.substring(7).trim();
        JWTClaimsSet claims = jwtTokenService.validateAccessToken(token);

        String userIdStr = claims.getSubject();
        UUID userId = UUID.fromString(userIdStr);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        return mapToUserResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return mapToUserResponse(user);
    }

    private UserResponse mapToUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getPhone(),
                user.getRole(),
                user.getBuilding(),
                user.getFloor(),
                user.getRoom(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}
