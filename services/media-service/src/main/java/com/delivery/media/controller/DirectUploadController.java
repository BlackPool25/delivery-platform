package com.delivery.media.controller;

import com.delivery.media.model.UploadStatusResponse;
import com.delivery.media.service.DirectUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
public class DirectUploadController {

    private final DirectUploadService directUploadService;

    public DirectUploadController(DirectUploadService directUploadService) {
        this.directUploadService = directUploadService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadStatusResponse> uploadVideo(
            @RequestParam("file") MultipartFile file) {
        UploadStatusResponse response = directUploadService.uploadMultipart(file);
        return ResponseEntity.ok(response);
    }
}
