package com.atlasna.catalog.media;

import java.util.Optional;

/**
 * Identifies an image by its CONTENT (magic bytes), never by file name or the Content-Type a client sent, and reads its
 * dimensions from the header (no full decode, so a "decompression bomb" is caught by its declared size).
 * A {@code .jpg} that is really an HTML page — the classic stored-XSS upload — has no image signature and is refused.
 */
public final class ImageInspector {

    public enum Type {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

        public final String contentType;
        public final String extension;

        Type(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public static Optional<Type> ofContentType(String contentType) {
            for (Type t : values()) if (t.contentType.equalsIgnoreCase(contentType)) return Optional.of(t);
            return Optional.empty();
        }
    }

    public record Info(Type type, int width, int height) {}

    private ImageInspector() {}

    /** The image's real type and size, or empty if the bytes are not a JPEG, PNG or WebP we can read. */
    public static Optional<Info> inspect(byte[] b) {
        if (b == null || b.length < 16) return Optional.empty();
        try {
            if (u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) return jpeg(b);
            if (u8(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && u8(b, 4) == 0x0D && u8(b, 5) == 0x0A
                    && u8(b, 6) == 0x1A && u8(b, 7) == 0x0A) {
                return Optional.of(new Info(Type.PNG, be32(b, 16), be32(b, 20)));
            }
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return webp(b);
            }
        } catch (IndexOutOfBoundsException truncated) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<Info> jpeg(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if (u8(b, i) != 0xFF) return Optional.empty();
            int marker = u8(b, i + 1);
            int length = be16(b, i + 2);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) return Optional.of(new Info(Type.JPEG, be16(b, i + 7), be16(b, i + 5)));
            if (length < 2) return Optional.empty();
            i += 2 + length;
        }
        return Optional.empty();
    }

    private static Optional<Info> webp(byte[] b) {
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8 " -> Optional.of(new Info(Type.WEBP, le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF));
            case "VP8L" -> {
                int bits = le32(b, 21);
                yield Optional.of(new Info(Type.WEBP, (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1));
            }
            case "VP8X" -> Optional.of(new Info(Type.WEBP, le24(b, 24) + 1, le24(b, 27) + 1));
            default -> Optional.empty();
        };
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int be16(byte[] b, int i) {
        return (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static int be32(byte[] b, int i) {
        return (u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
    }

    private static int le16(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8);
    }

    private static int le24(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16);
    }

    private static int le32(byte[] b, int i) {
        return le24(b, i) | (u8(b, i + 3) << 24);
    }
}
