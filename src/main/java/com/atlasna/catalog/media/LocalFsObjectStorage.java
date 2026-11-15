package com.atlasna.catalog.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Object storage on the local disk ({@code atlasna.media.storage-dir}), for development and tests. It imitates a cloud
 * bucket's signed URLs: {@link #createUploadUrl} returns a link to {@code PUT /api/media/uploads/{key}} carrying an
 * expiry and an HMAC signature over (key, content type, size cap, expiry), so the browser can upload directly and
 * nobody can reuse the link for another key, type, size or later time. The signing key is random per start (links
 * only need to live minutes; a restart simply invalidates outstanding ones).
 */
@Component
@EnableConfigurationProperties(MediaProperties.class)
public class LocalFsObjectStorage implements ObjectStorage {

    /** Lower-case path segments only: no "..", no leading slash, no client file names. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(/[a-z0-9][a-z0-9._-]*)+");

    private final MediaProperties properties;
    private final Path root;
    private final Clock clock;
    private final byte[] signingKey = new byte[32];

    public LocalFsObjectStorage(MediaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.root = Path.of(properties.storageDir()).toAbsolutePath().normalize();
        new SecureRandom().nextBytes(signingKey);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't create media directory " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public UploadTarget createUploadUrl(String key, String contentType, long maxBytes) {
        path(key); // validates
        long expires = clock.instant().plus(properties.uploadTtl()).getEpochSecond();
        String sig = sign(key, contentType, maxBytes, expires);
        String url = properties.uploadBaseUrl().replaceAll("/+$", "") + "/api/media/uploads/" + key
                + "?ct=" + URLEncoder.encode(contentType, StandardCharsets.UTF_8) + "&max=" + maxBytes + "&exp=" + expires + "&sig=" + sig;
        return new UploadTarget(key, url, "PUT", Map.of("Content-Type", contentType), Instant.ofEpochSecond(expires));
    }

    /** Checks a signed upload link: right key, type and size cap, not expired, signature intact (constant-time). */
    public boolean verify(String key, String contentType, long maxBytes, long expires, String signature) {
        if (signature == null || clock.instant().getEpochSecond() > expires) return false;
        return MessageDigest.isEqual(sign(key, contentType, maxBytes, expires).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** Stores bytes atomically (temp file + move): a reader never sees half a file. */
    public void write(String key, byte[] bytes) {
        Path target = path(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String publicUrl(String key) {
        return properties.baseUrl().replaceAll("/+$", "") + "/" + key;
    }

    @Override
    public Optional<byte[]> read(String key) {
        Path p = path(key);
        try {
            return Files.isRegularFile(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<StoredObject> list(String prefix) {
        Path dir = root.resolve(prefix).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().startsWith(".")).map(f -> {
                try {
                    return new StoredObject(root.relativize(f).toString().replace('\\', '/'), Files.size(f),
                            Files.getLastModifiedTime(f).toInstant());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rejects any key that isn't a plain relative path inside the storage root (path traversal). */
    public static boolean isValidKey(String key) {
        return key != null && key.length() <= 200 && KEY.matcher(key).matches() && !key.contains("..");
    }

    private Path path(String key) {
        if (!isValidKey(key)) throw new IllegalArgumentException("Invalid storage key");
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }

    private String sign(String key, String contentType, long maxBytes, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((key + "|" + contentType + "|" + maxBytes + "|" + expires)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
