package com.atlasna.catalog.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The client's IP for per-IP limits. {@code X-Forwarded-For} is a header ANYONE can send: trusting it blindly lets an
 * attacker pick a new "IP" for every request and walk straight past per-IP limits. So:
 * <ul>
 *   <li>No trusted proxies configured (the default, and local dev): the TCP peer address, header ignored.</li>
 *   <li>Request arrives from a configured trusted proxy (our load balancer): walk {@code X-Forwarded-For} from the
 *       right (the entries our own proxies appended) and take the first address that is not a trusted proxy.
 *       Whatever the client wrote at the left end is never used.</li>
 * </ul>
 * Configure with {@code atlasna.http.trusted-proxies} (IPs or CIDR ranges, e.g. the load balancer's range).
 */
@Component
@EnableConfigurationProperties(ClientIpResolver.Properties.class)
public class ClientIpResolver {

    @ConfigurationProperties(prefix = "atlasna.http")
    public record Properties(List<String> trustedProxies) {
        public Properties {
            trustedProxies = trustedProxies == null ? List.of()
                    : trustedProxies.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).toList();
        }
    }

    private final List<Cidr> trusted;

    public ClientIpResolver(Properties properties) {
        this.trusted = properties.trustedProxies().stream().map(Cidr::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trusted.isEmpty() || !isTrusted(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return peer;
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) continue;
            if (!isTrusted(hop)) return isIpLiteral(hop) ? hop : peer; // garbage in the header → fall back to the peer
        }
        return peer; // every hop was one of our proxies
    }

    private boolean isTrusted(String ip) {
        byte[] address = toBytes(ip);
        return address != null && trusted.stream().anyMatch(c -> c.contains(address));
    }

    private static boolean isIpLiteral(String s) {
        return toBytes(s) != null;
    }

    /** Parses only IP literals (never does a DNS lookup on header content). */
    private static byte[] toBytes(String ip) {
        if (ip == null || ip.isBlank() || !ip.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String value) {
            String[] parts = value.trim().split("/");
            byte[] network = toBytes(parts[0]);
            if (network == null) throw new IllegalArgumentException("Not an IP or CIDR: " + value);
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            return new Cidr(network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int full = prefix / 8, rest = prefix % 8;
            for (int i = 0; i < full; i++) if (address[i] != network[i]) return false;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }
}
