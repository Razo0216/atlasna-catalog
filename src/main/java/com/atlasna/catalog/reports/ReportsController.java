package com.atlasna.catalog.reports;

import com.atlasna.catalog.inventory.InventoryService;
import com.atlasna.catalog.inventory.LotView;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The owner's dashboard and reports (ADMIN only: revenue and per-driver cash are the owner's business). Dates are
 * delivery days in Annaba; a range is inclusive and defaults to the last 30 days.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ReportsController {

    private final ReportsService reports;
    private final InventoryService inventory;

    @GetMapping("/dashboard")
    public ReportsService.Dashboard dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.dashboard(date == null ? reports.today() : date);
    }

    @GetMapping("/reports/sales")
    public List<ReportsService.DaySales> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.salesByDay(from(from, to), to(to));
    }

    @GetMapping("/reports/top-products")
    public List<ReportsService.ProductSales> topProducts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return reports.topProducts(from(from, to), to(to), limit);
    }

    @GetMapping("/reports/by-category")
    public List<ReportsService.CategorySales> byCategory(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.byCategory(from(from, to), to(to));
    }

    @GetMapping("/reports/by-zone")
    public List<ReportsService.ZoneSales> byZone(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.byZone(from(from, to), to(to));
    }

    @GetMapping("/reports/cash-by-driver")
    public List<ReportsService.DriverCash> cashByDriver(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.cashByDriver(from(from, to), to(to));
    }

    /** Same as /inventory/low-stock, under the reports namespace of the API contract. */
    @GetMapping("/reports/low-stock")
    public List<Map<String, Object>> lowStock() {
        return inventory.lowStock();
    }

    /** Same as /inventory/expiring, under the reports namespace of the API contract. */
    @GetMapping("/reports/expiring")
    public List<LotView> expiring(@RequestParam(defaultValue = "7") int days) {
        if (days < 0 || days > 60) throw com.atlasna.catalog.common.exception.ApiException.invalidField("days", "Days must be between 0 and 60");
        return inventory.expiringSoon(days);
    }

    private LocalDate to(LocalDate to) {
        return to == null ? reports.today() : to;
    }

    private LocalDate from(LocalDate from, LocalDate to) {
        return from == null ? to(to).minusDays(29) : from;
    }
}
