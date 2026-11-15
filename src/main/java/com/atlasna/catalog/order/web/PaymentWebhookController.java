package com.atlasna.catalog.order.web;

import com.atlasna.catalog.order.service.OrderPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The payment provider calls this (public, no login). The body is read as RAW BYTES: the signature is computed over the
 * exact bytes received, and re-serialising parsed JSON would change them. 200 for processed/duplicate/ignored events
 * (so the provider stops retrying), 401 WEBHOOK_SIGNATURE_INVALID, 400 WEBHOOK_INVALID / WEBHOOK_STALE.
 */
@RestController
@RequestMapping("/api/webhooks/payments")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final OrderPaymentService payments;

    @PostMapping
    public Map<String, String> receive(@RequestBody byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) headers.put(name, request.getHeader(name));
        return Map.of("outcome", payments.handleWebhook(headers, body).name());
    }
}
