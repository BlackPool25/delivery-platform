package com.delivery.media.service;

import com.delivery.media.model.UploadStatusResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DirectUploadService {
    UploadStatusResponse uploadMultipart(MultipartFile file);
}
