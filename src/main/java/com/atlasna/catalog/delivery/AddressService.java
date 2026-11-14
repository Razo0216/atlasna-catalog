package com.atlasna.catalog.delivery;

import com.atlasna.catalog.common.PhoneNumbers;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.domain.Address;
import com.atlasna.catalog.delivery.domain.AddressRepository;
import com.atlasna.catalog.delivery.domain.DeliveryZone;
import com.atlasna.catalog.delivery.domain.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The customer's address book. Every method takes the authenticated user's id and every lookup is scoped to it,
 * so another user's address id answers 404 ADDRESS_NOT_FOUND (BOLA / IDOR protection).
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    private static final int MAX_ADDRESSES = 10;

    private final AddressRepository addresses;
    private final DeliveryZoneRepository zones;

    public record AddressData(String label, String recipientName, String phone, long zoneId, String street, String landmark,
                              String buildingNotes, BigDecimal lat, BigDecimal lng) {}

    public record AddressView(long id, String label, String recipientName, String phone, long zoneId, String street,
                              String landmark, String buildingNotes, BigDecimal lat, BigDecimal lng, boolean isDefault) {}

    @Transactional(readOnly = true)
    public List<AddressView> list(long userId) {
        return addresses.findByUserIdOrderByIsDefaultDescIdAsc(userId).stream().map(AddressService::view).toList();
    }

    @Transactional
    public AddressView create(long userId, AddressData d) {
        if (addresses.countByUserId(userId) >= MAX_ADDRESSES) {
            throw ApiException.businessRule("VALIDATION_FAILED", "At most " + MAX_ADDRESSES + " addresses per account");
        }
        Address a = new Address();
        a.setUserId(userId);
        apply(a, d);
        a.setDefault(addresses.countByUserId(userId) == 0); // the first address is the default
        return view(addresses.saveAndFlush(a));
    }

    @Transactional
    public AddressView update(long userId, long id, AddressData d) {
        Address a = own(userId, id);
        apply(a, d);
        return view(addresses.saveAndFlush(a));
    }

    @Transactional
    public void delete(long userId, long id) {
        Address a = own(userId, id);
        boolean wasDefault = a.isDefault();
        addresses.delete(a);
        addresses.flush();
        if (wasDefault) {
            addresses.findByUserIdOrderByIsDefaultDescIdAsc(userId).stream().findFirst().ifPresent(next -> next.setDefault(true));
        }
    }

    @Transactional
    public AddressView makeDefault(long userId, long id) {
        own(userId, id);
        addresses.clearDefault(userId);
        Address a = own(userId, id);
        a.setDefault(true);
        return view(addresses.saveAndFlush(a));
    }

    private Address own(long userId, long id) {
        return addresses.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address " + id + " not found"));
    }

    private void apply(Address a, AddressData d) {
        String phone = PhoneNumbers.normalize(d.phone())
                .orElseThrow(() -> ApiException.invalidField("phone", "Phone must be an Algerian mobile number (05, 06 or 07)"));
        DeliveryZone zone = zones.findById(d.zoneId()).filter(DeliveryZone::isActive)
                .orElseThrow(() -> ApiException.invalidField("zoneId", "We don't deliver to this zone"));
        a.setLabel(blankToNull(d.label()));
        a.setRecipientName(d.recipientName().trim());
        a.setPhone(phone);
        a.setZoneId(zone.getId());
        a.setStreet(d.street().trim());
        a.setLandmark(d.landmark().trim());
        a.setBuildingNotes(blankToNull(d.buildingNotes()));
        a.setLat(d.lat());
        a.setLng(d.lng());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AddressView view(Address a) {
        return new AddressView(a.getId(), a.getLabel(), a.getRecipientName(), a.getPhone(), a.getZoneId(), a.getStreet(),
                a.getLandmark(), a.getBuildingNotes(), a.getLat(), a.getLng(), a.isDefault());
    }
}
