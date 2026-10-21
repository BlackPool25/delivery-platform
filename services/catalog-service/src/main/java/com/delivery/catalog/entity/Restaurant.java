package com.delivery.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "restaurants")
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "location", columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point location;

    @Column(name = "delivery_zone", columnDefinition = "geography(Polygon, 4326)", nullable = false)
    private Polygon deliveryZone;

    @Column(name = "h3_res8", nullable = false)
    private Long h3Res8;

    @Column(name = "city_id", nullable = false, length = 64)
    private String cityId;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "is_accepting_orders", nullable = false)
    private boolean isAcceptingOrders;

    @Column(name = "average_prep_time_minutes", nullable = false)
    private int averagePrepTimeMinutes;

    @Column(name = "rating", nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Restaurant() {
        this.isActive = true;
        this.isAcceptingOrders = true;
        this.averagePrepTimeMinutes = 20;
        this.rating = new BigDecimal("5.0");
        this.cityId = "default-city";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Restaurant(String name, String description, String phone, Point location,
                      Polygon deliveryZone, Long h3Res8, String cityId) {
        this();
        this.name = name;
        this.description = description;
        this.phone = phone;
        this.location = location;
        this.deliveryZone = deliveryZone;
        this.h3Res8 = h3Res8;
        if (cityId != null) {
            this.cityId = cityId;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Point getLocation() {
        return location;
    }

    public void setLocation(Point location) {
        this.location = location;
    }

    public Polygon getDeliveryZone() {
        return deliveryZone;
    }

    public void setDeliveryZone(Polygon deliveryZone) {
        this.deliveryZone = deliveryZone;
    }

    public Long getH3Res8() {
        return h3Res8;
    }

    public void setH3Res8(Long h3Res8) {
        this.h3Res8 = h3Res8;
    }

    public String getCityId() {
        return cityId;
    }

    public void setCityId(String cityId) {
        this.cityId = cityId;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public boolean isAcceptingOrders() {
        return isAcceptingOrders;
    }

    public void setAcceptingOrders(boolean acceptingOrders) {
        isAcceptingOrders = acceptingOrders;
    }

    public int getAveragePrepTimeMinutes() {
        return averagePrepTimeMinutes;
    }

    public void setAveragePrepTimeMinutes(int averagePrepTimeMinutes) {
        this.averagePrepTimeMinutes = averagePrepTimeMinutes;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
