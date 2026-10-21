package com.delivery.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "simulator")
public class SimulatorProperties {

    private String orderServiceUrl = "http://order-service:8080";
    private String trackingServiceUrl = "http://tracking-service:8080";
    private String valhallaServiceUrl = "http://valhalla-service:8002";
    private long tickRateMs = 3000L;
    private double speedKmh = 28.0;
    private boolean autoDispatchEnabled = true;
    private int courierCount = 20;

    public String getOrderServiceUrl() {
        return orderServiceUrl;
    }

    public void setOrderServiceUrl(String orderServiceUrl) {
        this.orderServiceUrl = orderServiceUrl;
    }

    public String getTrackingServiceUrl() {
        return trackingServiceUrl;
    }

    public void setTrackingServiceUrl(String trackingServiceUrl) {
        this.trackingServiceUrl = trackingServiceUrl;
    }

    public String getValhallaServiceUrl() {
        return valhallaServiceUrl;
    }

    public void setValhallaServiceUrl(String valhallaServiceUrl) {
        this.valhallaServiceUrl = valhallaServiceUrl;
    }

    public long getTickRateMs() {
        return tickRateMs;
    }

    public void setTickRateMs(long tickRateMs) {
        this.tickRateMs = tickRateMs;
    }

    public double getSpeedKmh() {
        return speedKmh;
    }

    public void setSpeedKmh(double speedKmh) {
        this.speedKmh = speedKmh;
    }

    public boolean isAutoDispatchEnabled() {
        return autoDispatchEnabled;
    }

    public void setAutoDispatchEnabled(boolean autoDispatchEnabled) {
        this.autoDispatchEnabled = autoDispatchEnabled;
    }

    public int getCourierCount() {
        return courierCount;
    }

    public void setCourierCount(int courierCount) {
        this.courierCount = courierCount;
    }
}
