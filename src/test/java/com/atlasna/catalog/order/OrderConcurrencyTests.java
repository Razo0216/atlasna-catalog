package com.atlasna.catalog.order;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.order.service.OrderPlacementService.Placement;
import com.atlasna.catalog.user.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real threads, real Postgres: these prove the database-level guarantees (no overselling, no overbooking,
 * no deadlocks, one order per idempotency key). Each must be able to fail — remove the WHERE clause of the stock
 * update or the capacity condition of the slot upsert and watch them go red.
 */
class OrderConcurrencyTests extends OrderTestSupport {

    /** Starts every task at the same instant and returns each outcome: the order number or the error code. */
    private List<String> race(List<Callable<Placement>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            for (Callable<Placement> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call().order().orderNumber();
                    } catch (ApiException e) {
                        return e.getCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : futures) outcomes.add(f.get(60, TimeUnit.SECONDS));
            return outcomes;
        }
    }

    private static Map<String, Long> count(List<String> outcomes) {
        return outcomes.stream().collect(Collectors.groupingBy(o -> o.startsWith("ATL-") ? "ORDER" : o, Collectors.counting()));
    }

    @Test
    void twentyBuyersForTheLastUnitExactlyOneWins() throws Exception {
        long last = product("Dernier", "2500.00", "1");
        List<Callable<Placement>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User u = customer();
            long addr = address(u);
            inCart(u, last, "1");
            tasks.add(() -> placement.place(u.getId(), UUID.randomUUID().toString(), request(addr)));
        }
        Map<String, Long> result = count(race(tasks));
        assertThat(result).containsEntry("ORDER", 1L).containsEntry("INSUFFICIENT_STOCK", 19L);
        assertThat(stock(last)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT booked FROM slot_usage", Integer.class)).isEqualTo(1); // losers' slot claims rolled back
        assertLedgerHolds();
    }

    @Test
    void thirtyBookingsForASlotOfEightExactlyEightSucceed() throws Exception {
        long plenty = product("Stock", "2500.00", "100");
        List<Callable<Placement>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            User u = customer();
            long addr = address(u);
            inCart(u, plenty, "1");
            tasks.add(() -> placement.place(u.getId(), UUID.randomUUID().toString(), request(addr)));
        }
        Map<String, Long> result = count(race(tasks));
        assertThat(result).containsEntry("ORDER", 8L).containsEntry("SLOT_FULL", 22L);
        assertThat(jdbc.queryForObject("SELECT booked FROM slot_usage", Integer.class)).isEqualTo(8);
        assertThat(stock(plenty)).isEqualByComparingTo("92");
        assertLedgerHolds();
    }

    @Test
    void ordersLockingTheSameProductsInOppositeOrderDoNotDeadlock() throws Exception {
        long a = product("A", "1500.00", "100"), b = product("B", "1500.00", "100");
        jdbc.update("UPDATE delivery_slots SET capacity = 100");
        List<Callable<Placement>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            User u = customer();
            long addr = address(u);
            // half the carts add A then B, half B then A
            inCart(u, i % 2 == 0 ? a : b, "1");
            inCart(u, i % 2 == 0 ? b : a, "1");
            tasks.add(() -> placement.place(u.getId(), UUID.randomUUID().toString(), request(addr)));
        }
        assertThat(count(race(tasks))).containsOnlyKeys("ORDER").containsEntry("ORDER", 16L);
        assertThat(stock(a)).isEqualByComparingTo("84");
        assertThat(stock(b)).isEqualByComparingTo("84");
        assertLedgerHolds();
    }

    @Test
    void tenParallelRetriesWithTheSameKeyCreateOneOrder() throws Exception {
        long p = product("Article", "2500.00", "10");
        User u = customer();
        long addr = address(u);
        inCart(u, p, "1");
        String key = UUID.randomUUID().toString();
        List<Callable<Placement>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> placement.place(u.getId(), key, request(addr)));
        List<String> outcomes = race(tasks);
        assertThat(outcomes).allMatch(o -> o.startsWith("ATL-"));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(stock(p)).isEqualByComparingTo("9");
    }
}
