-- Start with three delivery rounds a day for a small team of drivers (owner's decision, 2026-11-13):
-- morning 10:00-12:00, afternoon 14:00-16:00, evening 18:00-20:00, 8 orders each.
-- The 12:00-14:00 and 16:00-18:00 slots are switched off, not deleted: the admin can turn them back on
-- (PUT /api/admin/delivery/slots/{id}) when more drivers join.
UPDATE delivery_slots SET active = FALSE
WHERE zone_id IS NULL AND day_of_week IS NULL
  AND (start_time, end_time) IN (('12:00', '14:00'), ('16:00', '18:00'));
