package com.delivery.auth.controller;

import com.delivery.auth.crypto.Ed25519KeyProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/.well-known")
public class WellKnownController {

    private final Ed25519KeyProvider keyProvider;

    public WellKnownController(Ed25519KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @GetMapping("/jwks.json")
    public ResponseEntity<Map<String, Object>> getJwks() {
        return ResponseEntity.ok(keyProvider.getJwksJsonObject());
    }
}
