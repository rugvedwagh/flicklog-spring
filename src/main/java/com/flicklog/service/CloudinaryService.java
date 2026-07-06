package com.flicklog.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import com.flicklog.model.ImageData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Mirrors the cloudinary.uploader.upload(...) / .destroy(...) calls
 * inline in createPost/updatePost/deletePost in post.controllers.js.
 */
@Slf4j
@Service
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public CloudinaryService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    @SuppressWarnings("unchecked")
    public ImageData upload(MultipartFile file) throws IOException {

        Map<String, Object> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "folder", "posts",
                        "resource_type", "image",
                        "transformation",
                        new Transformation()
                                .width(1000)
                                .height(1000)
                                .crop("limit")
                                .quality("auto:good")
                )
        );

        return new ImageData(
                (String) result.get("secure_url"),
                (String) result.get("public_id"),
                file.getOriginalFilename()
        );
    }

    public void destroy(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            log.info("Image deleted from Cloudinary: {}", publicId);
        } catch (IOException e) {
            log.warn("Failed to delete Cloudinary image {}: {}", publicId, e.getMessage());
        }
    }
}