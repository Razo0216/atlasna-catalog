package com.atlasna.catalog.delivery.web;

import com.atlasna.catalog.common.AlgerianMobile;
import com.atlasna.catalog.delivery.AddressService;
import com.atlasna.catalog.delivery.AddressService.AddressData;
import com.atlasna.catalog.delivery.AddressService.AddressView;
import com.atlasna.catalog.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The signed-in customer's address book (/api/me/addresses). */
@RestController
@RequestMapping("/api/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService service;

    @GetMapping
    public List<AddressView> list(@AuthenticationPrincipal User user) {
        return service.list(user.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView create(@AuthenticationPrincipal User user, @Valid @RequestBody AddressRequest r) {
        return service.create(user.getId(), r.toData());
    }

    @PutMapping("/{id}")
    public AddressView update(@AuthenticationPrincipal User user, @PathVariable long id, @Valid @RequestBody AddressRequest r) {
        return service.update(user.getId(), id, r.toData());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User user, @PathVariable long id) {
        service.delete(user.getId(), id);
    }

    @PostMapping("/{id}/default")
    public AddressView makeDefault(@AuthenticationPrincipal User user, @PathVariable long id) {
        return service.makeDefault(user.getId(), id);
    }

    public record AddressRequest(
            @Size(max = 60, message = "Label must be at most 60 characters") String label,
            @NotBlank(message = "Recipient name is required") @Size(max = 120) String recipientName,
            @NotBlank(message = "Phone is required") @AlgerianMobile String phone,
            @NotNull(message = "Zone is required") Long zoneId,
            @NotBlank(message = "Street is required") @Size(max = 255) String street,
            @NotBlank(message = "Landmark is required") @Size(max = 255) String landmark,
            @Size(max = 500) String buildingNotes,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal lng) {

        AddressData toData() {
            return new AddressData(label, recipientName, phone, zoneId, street, landmark, buildingNotes, lat, lng);
        }
    }
}
