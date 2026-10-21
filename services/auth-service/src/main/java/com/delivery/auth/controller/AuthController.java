package com.delivery.auth.controller;

import com.delivery.auth.crypto.Ed25519KeyProvider;
import com.delivery.auth.dto.AuthResponse;
import com.delivery.auth.dto.RefreshTokenRequest;
import com.delivery.auth.dto.RequestOtpRequest;
import com.delivery.auth.dto.RequestOtpResponse;
import com.delivery.auth.dto.UserResponse;
import com.delivery.auth.dto.VerifyOtpRequest;
import com.delivery.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final Ed25519KeyProvider keyProvider;

    public AuthController(AuthService authService, Ed25519KeyProvider keyProvider) {
        this.authService = authService;
        this.keyProvider = keyProvider;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<RequestOtpResponse> requestOtp(@Valid @RequestBody RequestOtpRequest request) {
        RequestOtpResponse response = authService.requestOtp(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        AuthResponse response = authService.verifyOtp(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        UserResponse response = authService.getCurrentUserFromToken(authHeader);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> getJwks() {
        return ResponseEntity.ok(keyProvider.getJwksJsonObject());
    }
}
