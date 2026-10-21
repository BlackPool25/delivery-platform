package com.delivery.catalog.service;

import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class H3SpatialService {

    private static final Logger log = LoggerFactory.getLogger(H3SpatialService.class);
    public static final int DEFAULT_RESOLUTION = 8;

    private final H3Core h3;

    public H3SpatialService() {
        try {
            this.h3 = H3Core.newInstance();
            log.info("Uber H3 spatial core engine initialized successfully (resolution 8 candidate indexing active)");
        } catch (IOException e) {
            log.error("Failed to initialize Uber H3 native core engine", e);
            throw new IllegalStateException("Uber H3 spatial engine initialization failure", e);
        }
    }

    public long latLngToCell(double latitude, double longitude, int resolution) {
        return h3.latLngToCell(latitude, longitude, resolution);
    }

    public long latLngToRes8Cell(double latitude, double longitude) {
        return h3.latLngToCell(latitude, longitude, DEFAULT_RESOLUTION);
    }

    public List<Long> getGridDisk(long centerCell, int kRing) {
        return h3.gridDisk(centerCell, Math.max(0, kRing));
    }

    public String cellToHex(long cell) {
        return h3.h3ToString(cell);
    }

    public long hexToCell(String hexAddress) {
        return h3.stringToH3(hexAddress);
    }

    public H3Core getH3Core() {
        return h3;
    }
}
