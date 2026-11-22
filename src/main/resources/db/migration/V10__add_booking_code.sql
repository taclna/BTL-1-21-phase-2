ALTER TABLE bookings
    ADD COLUMN booking_code VARCHAR(20);

UPDATE bookings
SET booking_code = 'LEGACY-' || UPPER(substr(replace(id::text, '-', ''), 1, 12));

ALTER TABLE bookings
    ALTER COLUMN booking_code SET NOT NULL;

CREATE UNIQUE INDEX uq_bookings_booking_code
    ON bookings (booking_code);
