package com.delivery.auth.repository;

import com.delivery.auth.entity.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, UUID> {

    Optional<OtpVerification> findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(String phone);

    @Modifying
    @Query("UPDATE OtpVerification o SET o.consumed = true WHERE o.phone = :phone AND o.consumed = false")
    int invalidateExistingOtps(@Param("phone") String phone);
}
