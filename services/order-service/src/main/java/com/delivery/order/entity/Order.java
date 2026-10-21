package com.delivery.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "courier_id")
    private UUID courierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    @Column(name = "tax", nullable = false, precision = 10, scale = 2)
    private BigDecimal tax;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "dropoff_location", columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point dropoffLocation;

    @Column(name = "dropoff_building", nullable = false, length = 128)
    private String dropoffBuilding;

    @Column(name = "dropoff_floor", length = 32)
    private String dropoffFloor;

    @Column(name = "dropoff_room", length = 32)
    private String dropoffRoom;

    @Column(name = "dropoff_gate_code", length = 32)
    private String dropoffGateCode;

    @Column(name = "dropoff_notes", columnDefinition = "TEXT")
    private String dropoffNotes;

    @Column(name = "verification_pin", nullable = false, length = 6)
    private String verificationPin;

    @Column(name = "dispatch_lease_expires_at")
    private Instant dispatchLeaseExpiresAt;

    @Column(name = "city_id", nullable = false, length = 64)
    private String cityId;

    @Column(name = "idempotency_key", unique = true)
    private UUID idempotencyKey;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Order() {
        this.status = OrderStatus.PLACED;
        this.subtotal = BigDecimal.ZERO;
        this.deliveryFee = BigDecimal.ZERO;
        this.tax = BigDecimal.ZERO;
        this.totalAmount = BigDecimal.ZERO;
        this.cityId = "default-city";
        this.version = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Order(UUID userId, UUID restaurantId, Point dropoffLocation,
                 String dropoffBuilding, String dropoffFloor, String dropoffRoom,
                 String dropoffGateCode, String dropoffNotes, String verificationPin) {
        this();
        this.userId = userId;
        this.restaurantId = restaurantId;
        this.dropoffLocation = dropoffLocation;
        this.dropoffBuilding = dropoffBuilding;
        this.dropoffFloor = dropoffFloor;
        this.dropoffRoom = dropoffRoom;
        this.dropoffGateCode = dropoffGateCode;
        this.dropoffNotes = dropoffNotes;
        this.verificationPin = verificationPin;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
        item.setOrder(null);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getRestaurantId() {
        return restaurantId;
    }

    public void setRestaurantId(UUID restaurantId) {
        this.restaurantId = restaurantId;
    }

    public UUID getCourierId() {
        return courierId;
    }

    public void setCourierId(UUID courierId) {
        this.courierId = courierId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public void setTax(BigDecimal tax) {
        this.tax = tax;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Point getDropoffLocation() {
        return dropoffLocation;
    }

    public void setDropoffLocation(Point dropoffLocation) {
        this.dropoffLocation = dropoffLocation;
    }

    public String getDropoffBuilding() {
        return dropoffBuilding;
    }

    public void setDropoffBuilding(String dropoffBuilding) {
        this.dropoffBuilding = dropoffBuilding;
    }

    public String getDropoffFloor() {
        return dropoffFloor;
    }

    public void setDropoffFloor(String dropoffFloor) {
        this.dropoffFloor = dropoffFloor;
    }

    public String getDropoffRoom() {
        return dropoffRoom;
    }

    public void setDropoffRoom(String dropoffRoom) {
        this.dropoffRoom = dropoffRoom;
    }

    public String getDropoffGateCode() {
        return dropoffGateCode;
    }

    public void setDropoffGateCode(String dropoffGateCode) {
        this.dropoffGateCode = dropoffGateCode;
    }

    public String getDropoffNotes() {
        return dropoffNotes;
    }

    public void setDropoffNotes(String dropoffNotes) {
        this.dropoffNotes = dropoffNotes;
    }

    public String getVerificationPin() {
        return verificationPin;
    }

    public void setVerificationPin(String verificationPin) {
        this.verificationPin = verificationPin;
    }

    public Instant getDispatchLeaseExpiresAt() {
        return dispatchLeaseExpiresAt;
    }

    public void setDispatchLeaseExpiresAt(Instant dispatchLeaseExpiresAt) {
        this.dispatchLeaseExpiresAt = dispatchLeaseExpiresAt;
    }

    public String getCityId() {
        return cityId;
    }

    public void setCityId(String cityId) {
        this.cityId = cityId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
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
