package com.delivery.catalog.repository;

import com.delivery.catalog.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {

    List<Restaurant> findByH3Res8AndIsActiveTrue(Long h3Res8);

    @Query(value = """
        SELECT r.* FROM restaurants r
        WHERE r.id = :restaurantId
          AND r.is_active = true
          AND ST_Covers(r.delivery_zone, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
        """, nativeQuery = true)
    Optional<Restaurant> findByIdAndCoversLocation(@Param("restaurantId") UUID restaurantId,
                                                   @Param("latitude") double latitude,
                                                   @Param("longitude") double longitude);

    @Query(value = """
        SELECT r.* FROM restaurants r
        WHERE r.is_active = true
          AND r.is_accepting_orders = true
          AND ST_Covers(r.delivery_zone, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
        ORDER BY ST_Distance(r.location, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) ASC
        """, nativeQuery = true)
    List<Restaurant> findActiveRestaurantsCoveringLocation(@Param("latitude") double latitude,
                                                           @Param("longitude") double longitude);

    @Query(value = """
        SELECT ST_Covers(r.delivery_zone, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
        FROM restaurants r
        WHERE r.id = :restaurantId
        """, nativeQuery = true)
    Boolean isLocationCovered(@Param("restaurantId") UUID restaurantId,
                              @Param("latitude") double latitude,
                              @Param("longitude") double longitude);

    @Query(value = """
        SELECT ST_Distance(r.location, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)
        FROM restaurants r
        WHERE r.id = :restaurantId
        """, nativeQuery = true)
    Double calculateDistanceMeters(@Param("restaurantId") UUID restaurantId,
                                   @Param("latitude") double latitude,
                                   @Param("longitude") double longitude);
}
