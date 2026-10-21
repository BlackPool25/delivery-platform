package com.delivery.tracking.calculator;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class KinematicCalculator {

    // WGS-84 Mean Earth Radius in meters
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    /**
     * Computes the geodesic great-circle distance between two points in EPSG:4326 using the Haversine formula.
     *
     * @param lat1 Latitude of point 1 in degrees
     * @param lon1 Longitude of point 1 in degrees
     * @param lat2 Latitude of point 2 in degrees
     * @param lon2 Longitude of point 2 in degrees
     * @return Distance in meters
     */
    public double calculateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        if (lat1 == lat2 && lon1 == lon2) {
            return 0.0;
        }

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);

        double sinHalfDLat = Math.sin(dLat / 2.0);
        double sinHalfDLon = Math.sin(dLon / 2.0);

        double a = (sinHalfDLat * sinHalfDLat) +
                Math.cos(phi1) * Math.cos(phi2) * (sinHalfDLon * sinHalfDLon);

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Calculates the kinematic velocity between two temporal spatial points in kilometers per hour (km/h).
     *
     * @param lat1 Latitude of previous point
     * @param lon1 Longitude of previous point
     * @param time1 Timestamp of previous point
     * @param lat2 Latitude of current point
     * @param lon2 Longitude of current point
     * @param time2 Timestamp of current point
     * @return Velocity in km/h. Returns Double.MAX_VALUE if time delta is non-positive with non-zero distance.
     */
    public double calculateVelocityKmh(double lat1, double lon1, Instant time1,
                                       double lat2, double lon2, Instant time2) {
        double distanceMeters = calculateDistanceMeters(lat1, lon1, lat2, lon2);
        long millis = Duration.between(time1, time2).toMillis();

        if (millis <= 0) {
            return distanceMeters == 0.0 ? 0.0 : Double.MAX_VALUE;
        }

        double seconds = millis / 1000.0;
        double speedMetersPerSecond = distanceMeters / seconds;

        // Convert m/s to km/h (1 m/s = 3.6 km/h)
        return speedMetersPerSecond * 3.6;
    }
}
