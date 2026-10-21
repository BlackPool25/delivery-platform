package com.delivery.auth.dto;

import com.delivery.auth.entity.UserRole;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String phone,
        UserRole role,
        String building,
        String floor,
        String room,
        boolean isActive,
        Instant createdAt
) {}
