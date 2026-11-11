package com.atlasna.catalog.config;

import com.atlasna.catalog.product.domain.Product;
import com.atlasna.catalog.product.domain.ProductImage;
import com.atlasna.catalog.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Demo photos for the dev catalog until real uploads arrive (Phase 5).
 *
 * <p>The files live in {@code src/main/resources/static/media/products/<slug>.webp} and are served at
 * {@code /media/products/...}; only the storage key is stored, the public URL is derived from
 * {@code atlasna.media.base-url}. Free photos from Unsplash (Unsplash License) — see docs/media-credits.md.
 * They show the kind of product, not the shop's actual items: replace them with real photos before launch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoProductImages {

    /** Product slug -> {French alt, Arabic alt}. The file is {@code products/<slug>.webp}. */
    public static final Map<String, String[]> PHOTOS = Map.ofEntries(
            Map.entry("tomates", alt("Tomates rouges bien mûres", "طماطم حمراء ناضجة")),
            Map.entry("oranges", alt("Oranges fraîches", "برتقال طازج")),
            Map.entry("pommes-de-terre", alt("Pommes de terre", "بطاطا")),
            Map.entry("couscous-fin-1-kg", alt("Grains de couscous fin", "حبات الكسكس الرفيع")),
            Map.entry("semoule-moyenne-1-kg", alt("Semoule et farine", "سميد ودقيق")),
            Map.entry("lait-demi-ecreme-1-l", alt("Verre de lait", "كأس حليب")),
            Map.entry("oeufs-6", alt("Œufs dans leur boîte", "بيض في علبته")),
            Map.entry("dattes-deglet-nour-500-g", alt("Dattes", "تمر")),
            Map.entry("huile-d-olive-extra-vierge-500-ml", alt("Bouteille d'huile d'olive", "قارورة زيت الزيتون")),
            Map.entry("harissa-380-g", alt("Pâte de piment rouge et piments", "معجون الفلفل الأحمر والفلفل الحار")),
            Map.entry("cafe-moulu-250-g", alt("Café moulu et grains de café", "قهوة مطحونة وحبوب البن")),
            Map.entry("the-vert-100-g", alt("Thé aux feuilles fraîches", "شاي بأوراق طازجة")),
            Map.entry("huile-d-argan-pure-50-ml", alt("Flacon d'huile", "قارورة زيت")),
            Map.entry("eau-de-rose-200-ml", alt("Roses roses avec gouttes d'eau", "ورود وردية بقطرات الماء")),
            Map.entry("henne-naturel-100-g", alt("Main décorée au henné", "يد مزينة بالحناء")),
            Map.entry("savon-noir-beldi-200-g", alt("Savons artisanaux", "صابون تقليدي")),
            Map.entry("creme-hydratante-visage-50-ml", alt("Pot de crème hydratante", "علبة كريم مرطب")),
            Map.entry("gel-douche-250-ml", alt("Flacon de gel douche", "قارورة جل الاستحمام")),
            Map.entry("shampooing-au-karite-250-ml", alt("Flacon de shampooing", "قارورة شامبو")),
            Map.entry("rouge-a-levres-mat", alt("Rouge à lèvres rouge", "أحمر شفاه")));

    private final ProductRepository productRepository;

    /** Gives each demo product its photo if it has none yet. Idempotent; products the admin changed are left alone. */
    @Transactional
    public int attachMissing() {
        int attached = 0;
        for (var e : PHOTOS.entrySet()) {
            Product product = productRepository.findBySlug(e.getKey()).orElse(null);
            if (product == null || !product.getImages().isEmpty()) continue;
            product.getImages().add(ProductImage.builder()
                    .product(product)
                    .storageKey("products/" + e.getKey() + ".webp")
                    .altFr(e.getValue()[0])
                    .altAr(e.getValue()[1])
                    .sortOrder(0)
                    .build());
            attached++;
        }
        if (attached > 0) log.info("Attached {} demo product photos", attached);
        return attached;
    }

    private static String[] alt(String fr, String ar) {
        return new String[] {fr, ar};
    }
}
