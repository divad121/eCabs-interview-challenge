package pl.icwt.ecabs.bookingconsumerservice.entities;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Tombstone for a deleted booking. Add, edit and delete travel on separate queues and can be processed out of order;
 * the tombstone stops an add or edit that arrives after the delete from bringing the booking back.
 */
@Setter
@Getter
@NoArgsConstructor
@Document("deleted_bookings")
public class DeletedBooking {

    @Id
    private UUID bookingId;
    private Instant deletedOn;

    public DeletedBooking(UUID bookingId, Instant deletedOn) {
        this.bookingId = bookingId;
        this.deletedOn = deletedOn;
    }
}
