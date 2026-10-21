package com.delivery.catalog.repository;

import com.delivery.catalog.entity.VideoReel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VideoReelRepository extends JpaRepository<VideoReel, UUID> {

    List<VideoReel> findByRestaurantId(UUID restaurantId);

    List<VideoReel> findByH3Res8(Long h3Res8);
}
