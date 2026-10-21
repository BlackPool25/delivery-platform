package com.delivery.transcode.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transcode")
public class TranscodeHealthController {

    @Value("${transcode.vaapi-device:/dev/dri/renderD128}")
    private String vaapiDevice;

    @Value("${transcode.libva-driver:radeonsi}")
    private String libvaDriver;

    @Value("${transcode.hsa-override-gfx:11.0.0}")
    private String hsaOverrideGfx;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        File deviceFile = new File(vaapiDevice);
        boolean deviceExists = deviceFile.exists();
        boolean deviceReadable = deviceFile.canRead();

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "transcode-worker",
                "hardwareGpu", "AMD Radeon RX 7900 GRE",
                "vaapiDevice", vaapiDevice,
                "vaapiDeviceExists", deviceExists,
                "vaapiDeviceAccessible", deviceReadable,
                "libvaDriver", libvaDriver,
                "hsaOverrideGfx", hsaOverrideGfx,
                "prefetchCount", 4
        ));
    }
}
