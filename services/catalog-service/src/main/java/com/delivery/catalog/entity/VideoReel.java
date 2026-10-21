package com.delivery.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "video_reels")
public class VideoReel {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dish_id")
    private Dish dish;

    @Column(name = "master_m3u8_url", length = 512)
    private String masterM3u8Url;

    @Column(name = "thumbnail_url", length = 512)
    private String thumbnailUrl;

    @Column(name = "h3_res8", nullable = false)
    private Long h3Res8;

    @Column(name = "duration_sec", nullable = false)
    private int durationSec;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public VideoReel() {
        this.createdAt = Instant.now();
    }

    public VideoReel(Restaurant restaurant, Dish dish, String masterM3u8Url,
                     String thumbnailUrl, Long h3Res8, int durationSec) {
        this();
        this.restaurant = restaurant;
        this.dish = dish;
        this.masterM3u8Url = masterM3u8Url;
        this.thumbnailUrl = thumbnailUrl;
        this.h3Res8 = h3Res8;
        this.durationSec = durationSec;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Restaurant getRestaurant() {
        return restaurant;
    }

    public void setRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }

    public Dish getDish() {
        return dish;
    }

    public void setDish(Dish dish) {
        this.dish = dish;
    }

    public String getMasterM3u8Url() {
        return masterM3u8Url;
    }

    public void setMasterM3u8Url(String masterM3u8Url) {
        this.masterM3u8Url = masterM3u8Url;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public Long getH3Res8() {
        return h3Res8;
    }

    public void setH3Res8(Long h3Res8) {
        this.h3Res8 = h3Res8;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(int durationSec) {
        this.durationSec = durationSec;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
