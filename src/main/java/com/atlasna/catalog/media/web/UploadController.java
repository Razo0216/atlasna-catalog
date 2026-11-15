package com.atlasna.catalog.media.web;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.media.ImageInspector;
import com.atlasna.catalog.media.LocalFsObjectStorage;
import com.atlasna.catalog.media.MediaProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * The target of a signed upload link (local storage only; in the cloud the browser PUTs to the bucket instead).
 * Public like a bucket URL — the signature IS the permission: it binds key, content type, size cap and expiry.
 * The body is checked by content (magic bytes, dimensions) before anything is stored.
 */
@RestController
@RequestMapping("/api/media/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final LocalFsObjectStorage storage;
    private final MediaProperties properties;

    @PutMapping("/**")
    public Map<String, String> upload(HttpServletRequest request,
                                      @RequestHeader(value = "Content-Type", required = false) String contentType,
                                      @RequestParam("ct") String signedType, @RequestParam("max") long maxBytes,
                                      @RequestParam("exp") long expires, @RequestParam("sig") String signature) throws IOException {
        String key = request.getRequestURI().substring(request.getContextPath().length() + "/api/media/uploads/".length());
        if (!LocalFsObjectStorage.isValidKey(key) || !storage.verify(key, signedType, maxBytes, expires, signature)) {
            throw ApiException.forbidden("UPLOAD_URL_INVALID", "This upload link is invalid or has expired");
        }
        if (contentType == null || !contentType.split(";")[0].trim().equalsIgnoreCase(signedType)) {
            throw ApiException.badRequest("UPLOAD_TYPE_MISMATCH", "Content-Type must be " + signedType);
        }
        byte[] body = readAtMost(request.getInputStream(), maxBytes);
        if (body == null) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "The file is larger than allowed");
        ImageInspector.Info info = ImageInspector.inspect(body).orElseThrow(() ->
                ApiException.businessRule("IMAGE_INVALID", "The file is not a JPEG, PNG or WebP image"));
        if (!info.type().contentType.equalsIgnoreCase(signedType)) {
            throw ApiException.businessRule("IMAGE_INVALID", "The file content doesn't match its declared type");
        }
        if (info.width() < 1 || info.height() < 1 || info.width() > properties.maxDimension() || info.height() > properties.maxDimension()) {
            throw ApiException.businessRule("IMAGE_INVALID", "Image dimensions must be at most " + properties.maxDimension() + " px");
        }
        storage.write(key, body);
        return Map.of("key", key);
    }

    /** Reads the body, or returns null as soon as it exceeds {@code max} (never buffers more than max + 1 bytes). */
    private static byte[] readAtMost(InputStream in, long max) throws IOException {
        byte[] data = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
        return data.length > max ? null : data;
    }
}
