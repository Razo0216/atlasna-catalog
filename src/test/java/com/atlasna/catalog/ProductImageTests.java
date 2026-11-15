package com.atlasna.catalog;

import com.atlasna.catalog.media.ImageInspector;
import com.atlasna.catalog.media.LocalFsObjectStorage;
import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.product.service.ProductImageService;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product photo upload (ADR-0014): signed link → direct PUT → attach, with every check by CONTENT (magic bytes,
 * dimensions, size), keys the client can't choose or escape, links that can't be tampered with or reused late,
 * the role rules, and clean-up of abandoned uploads.
 */
class ProductImageTests extends OrderTestSupport {

    @Autowired LocalFsObjectStorage storage;
    @Autowired ProductImageService images;

    @Test
    void realJpeg_uploadedThroughTheSignedLink_appearsOnTheProduct_andIsServed() throws Exception {
        long id = product("Huile d'argan", "1800.00", "10");
        User staff = user(Role.STAFF);
        byte[] jpeg = image("jpg", 600, 400);

        String link = requestUpload(staff, id, "image/jpeg", jpeg.length).andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.headers.Content-Type").value("image/jpeg"))
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(link, "$.key");
        assertThat(key).matches("products/" + id + "/[0-9a-f-]{36}\\.jpg");
        upload(JsonPath.read(link, "$.url"), "image/jpeg", jpeg).andExpect(status().isOk());

        attach(staff, id, "[{\"key\":\"%s\",\"alt\":{\"fr\":\"Flacon\",\"ar\":\"قارورة\"}}]".formatted(key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images[0].key").value(key));
        mockMvc.perform(get("/api/products/" + id))
                .andExpect(jsonPath("$.images[0].url").value("/media/" + key))
                .andExpect(jsonPath("$.images[0].alt.ar").value("قارورة"));
        mockMvc.perform(get("/media/" + key)).andExpect(status().isOk()).andExpect(content().bytes(jpeg));
    }

    @Test
    void anHtmlFileDisguisedAsAJpegIsRejected_atUploadAndAtAttach() throws Exception {
        long id = product("Henné", "200.00", "10");
        User admin = user(Role.ADMIN);
        byte[] html = "<html><script>alert(document.cookie)</script></html>".repeat(3).getBytes(StandardCharsets.UTF_8);
        String link = requestUpload(admin, id, "image/jpeg", html.length).andReturn().getResponse().getContentAsString();
        upload(JsonPath.read(link, "$.url"), "image/jpeg", html)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGE_INVALID"));

        // In the cloud the browser writes straight to the bucket, so attach must check again: simulate that.
        String key = JsonPath.read(link, "$.key");
        storage.write(key, html);
        attach(admin, id, "[{\"key\":\"%s\"}]".formatted(key)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMAGE_INVALID"));
        // A real PNG renamed .jpg is refused too: the extension must match the content.
        storage.write(key, image("png", 50, 50));
        attach(admin, id, "[{\"key\":\"%s\"}]".formatted(key)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void theSignedLinkCannotBeTamperedWith_orUsedForAnotherTypeOrSize() throws Exception {
        long id = product("Savon", "450.00", "10");
        User admin = user(Role.ADMIN);
        byte[] png = image("png", 100, 100);
        String url = JsonPath.read(requestUpload(admin, id, "image/png", png.length).andReturn().getResponse().getContentAsString(), "$.url");

        upload(url.replace("/products/" + id + "/", "/products/" + (id + 1) + "/"), "image/png", png).andExpect(status().isForbidden());
        upload(url.replaceAll("exp=\\d+", "exp=9999999999"), "image/png", png).andExpect(status().isForbidden());
        upload(url.replace("max=5242880", "max=99999999"), "image/png", png).andExpect(status().isForbidden());
        upload(url, "image/jpeg", png).andExpect(status().isBadRequest()); // the signed type is png
        upload(url, "image/png", png).andExpect(status().isOk());
    }

    @Test
    void expiredLinksAreRefused() throws Exception {
        long id = product("Thé", "250.00", "10");
        var target = storage.createUploadUrl("products/" + id + "/old.png", "image/png", 1000);
        assertThat(storage.verify(target.key(), "image/png", 1000, Instant.now().minusSeconds(1).getEpochSecond(), "x")).isFalse();
    }

    @Test
    void sizeTypeAndDimensionLimits() throws Exception {
        long id = product("Café", "420.00", "10");
        User admin = user(Role.ADMIN);
        requestUpload(admin, id, "image/gif", 1000).andExpect(status().isBadRequest());
        requestUpload(admin, id, "text/html", 1000).andExpect(status().isBadRequest());
        requestUpload(admin, id, "image/png", 6L * 1024 * 1024).andExpect(status().isBadRequest());

        String url = JsonPath.read(requestUpload(admin, id, "image/png", 100).andReturn().getResponse().getContentAsString(), "$.url");
        upload(url, "image/png", image("png", 4500, 10)).andExpect(status().isUnprocessableContent()); // wider than 4000 px
        byte[] tooBig = new byte[5 * 1024 * 1024 + 10];
        System.arraycopy(image("png", 10, 10), 0, tooBig, 0, image("png", 10, 10).length);
        upload(url, "image/png", tooBig).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void keysCantEscapeTheProductOrTheStorageRoot() throws Exception {
        long a = product("A", "100.00", "10"), b = product("B", "100.00", "10");
        User admin = user(Role.ADMIN);
        String keyOfB = "products/" + b + "/" + java.util.UUID.randomUUID() + ".png";
        storage.write(keyOfB, image("png", 20, 20));
        attach(admin, a, "[{\"key\":\"%s\"}]".formatted(keyOfB)).andExpect(status().isBadRequest());
        attach(admin, a, "[{\"key\":\"products/%d/../../../etc/passwd\"}]".formatted(a)).andExpect(status().isBadRequest());
        assertThat(LocalFsObjectStorage.isValidKey("products/1/../../x.png")).isFalse();
        assertThat(LocalFsObjectStorage.isValidKey("/etc/passwd")).isFalse();
        assertThat(LocalFsObjectStorage.isValidKey("products/1/Photo Plage.JPG")).isFalse();
    }

    @Test
    void onlyAdminAndStaffMayUpload() throws Exception {
        long id = product("Dattes", "450.00", "10");
        requestUpload(customer(), id, "image/png", 100).andExpect(status().isForbidden());
        requestUpload(user(Role.DRIVER), id, "image/png", 100).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/uploads/product-image").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":%d,\"contentType\":\"image/png\",\"sizeBytes\":100}".formatted(id))).andExpect(status().isUnauthorized());
        attach(customer(), id, "[]").andExpect(status().isForbidden());
    }

    @Test
    void abandonedUploadsAreDeleted_attachedOnesKept() throws Exception {
        long id = product("Lait", "120.00", "10");
        String kept = "products/" + id + "/" + java.util.UUID.randomUUID() + ".png";
        String abandoned = "products/" + id + "/" + java.util.UUID.randomUUID() + ".png";
        String fresh = "products/" + id + "/" + java.util.UUID.randomUUID() + ".png";
        for (String k : new String[] {kept, abandoned, fresh}) storage.write(k, image("png", 30, 30));
        attach(user(Role.ADMIN), id, "[{\"key\":\"%s\"}]".formatted(kept)).andExpect(status().isOk());
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(25)));
        Files.setLastModifiedTime(storage.root().resolve(kept), old);
        Files.setLastModifiedTime(storage.root().resolve(abandoned), old);

        assertThat(images.deleteOrphans(Duration.ofHours(24))).isEqualTo(1);
        assertThat(storage.read(kept)).isPresent();
        assertThat(storage.read(abandoned)).isEmpty();
        assertThat(storage.read(fresh)).isPresent(); // probably still being attached
    }

    @Test
    void theInspectorReadsRealHeaders() throws Exception {
        assertThat(ImageInspector.inspect(image("jpg", 321, 123))).get()
                .isEqualTo(new ImageInspector.Info(ImageInspector.Type.JPEG, 321, 123));
        assertThat(ImageInspector.inspect(image("png", 77, 66))).get()
                .isEqualTo(new ImageInspector.Info(ImageInspector.Type.PNG, 77, 66));
        byte[] demoWebp = Files.readAllBytes(java.nio.file.Path.of("src/main/resources/static/media/products/tomates.webp"));
        assertThat(ImageInspector.inspect(demoWebp)).get().extracting(ImageInspector.Info::type).isEqualTo(ImageInspector.Type.WEBP);
        assertThat(ImageInspector.inspect("GIF89a-not-allowed-here".getBytes())).isEmpty();
    }

    // ---------- helpers ----------

    private ResultActions requestUpload(User u, long productId, String type, long size) throws Exception {
        return mockMvc.perform(post("/api/admin/uploads/product-image").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":%d,\"contentType\":\"%s\",\"sizeBytes\":%d}".formatted(productId, type, size)));
    }

    private ResultActions upload(String url, String type, byte[] body) throws Exception {
        URI uri = URI.create(url);
        return mockMvc.perform(put(URI.create(uri.getRawPath() + "?" + uri.getRawQuery())).contentType(type).content(body));
    }

    private ResultActions attach(User u, long productId, String imagesJson) throws Exception {
        return mockMvc.perform(put("/api/admin/products/" + productId + "/images").header("Authorization", bearer(u))
                .contentType(MediaType.APPLICATION_JSON).content("{\"images\":" + imagesJson + "}"));
    }

    private static byte[] image(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(4, 120, 87));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format.equals("jpg") ? "jpeg" : format, out);
        return out.toByteArray();
    }
}
