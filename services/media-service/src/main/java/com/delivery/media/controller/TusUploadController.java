package com.delivery.media.controller;

import com.delivery.media.model.UploadMetadata;
import com.delivery.media.model.UploadStatusResponse;
import com.delivery.media.service.TusUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media/uploads")
public class TusUploadController {

    private static final String TUS_RESUMABLE_VERSION = "1.0.0";

    private final TusUploadService tusUploadService;

    @Value("${media.tus.max-size-bytes:1073741824}")
    private long maxSizeBytes;

    public TusUploadController(TusUploadService tusUploadService) {
        this.tusUploadService = tusUploadService;
    }

    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> options() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Tus-Resumable", TUS_RESUMABLE_VERSION);
        headers.set("Tus-Version", TUS_RESUMABLE_VERSION);
        headers.set("Tus-Extension", "creation,termination");
        headers.set("Tus-Max-Size", String.valueOf(maxSizeBytes));
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }

    @PostMapping
    public ResponseEntity<Void> createUpload(
            @RequestHeader(value = "Upload-Length", required = false) Long uploadLength,
            @RequestHeader(value = "Upload-Metadata", required = false) String uploadMetadata,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {

        if (uploadLength == null || uploadLength <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (uploadLength > maxSizeBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        UploadMetadata metadata = tusUploadService.createUpload(uploadLength, uploadMetadata, contentType);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Location", "/api/v1/media/uploads/" + metadata.getUploadId());
        headers.set("Tus-Resumable", TUS_RESUMABLE_VERSION);
        return new ResponseEntity<>(headers, HttpStatus.CREATED);
    }

    @RequestMapping(value = "/{uploadId}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headUpload(@PathVariable UUID uploadId) {
        UploadMetadata metadata = tusUploadService.getUpload(uploadId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Upload-Offset", String.valueOf(metadata.getUploadOffset()));
        headers.set("Upload-Length", String.valueOf(metadata.getUploadLength()));
        headers.set("Tus-Resumable", TUS_RESUMABLE_VERSION);
        headers.set("Cache-Control", "no-store");
        return new ResponseEntity<>(headers, HttpStatus.OK);
    }

    @PatchMapping(value = "/{uploadId}")
    public ResponseEntity<Void> appendChunk(
            @PathVariable UUID uploadId,
            @RequestHeader(value = "Upload-Offset") Long offset,
            @RequestHeader(value = "Content-Length", required = false) Long contentLength,
            HttpServletRequest request) {

        if (offset == null || offset < 0) {
            return ResponseEntity.badRequest().build();
        }

        UploadMetadata metadata = tusUploadService.getUpload(uploadId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            InputStream is = request.getInputStream();
            long bytesToRead = contentLength != null ? contentLength : (metadata.getUploadLength() - offset);
            long newOffset = tusUploadService.appendChunk(uploadId, offset, is, bytesToRead);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Upload-Offset", String.valueOf(newOffset));
            headers.set("Tus-Resumable", TUS_RESUMABLE_VERSION);
            return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
        } catch (IllegalStateException e) {
            // Offset conflict
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> terminateUpload(@PathVariable UUID uploadId) {
        tusUploadService.terminateUpload(uploadId);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Tus-Resumable", TUS_RESUMABLE_VERSION);
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }

    @GetMapping("/{uploadId}/status")
    public ResponseEntity<UploadStatusResponse> getStatus(@PathVariable UUID uploadId) {
        UploadStatusResponse status = tusUploadService.getUploadStatus(uploadId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}
