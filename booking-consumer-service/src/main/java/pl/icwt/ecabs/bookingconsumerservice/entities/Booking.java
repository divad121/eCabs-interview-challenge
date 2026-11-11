package pl.icwt.ecabs.bookingconsumerservice.entities;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@Document("bookings")
public class Booking {

    @Id
    private UUID bookingId;
    private String passengerName;
    private String passengerContactNumber;
    private OffsetDateTime pickupTime;
    private Boolean asap = true;
    private Integer waitingTime;
    private Integer noOfPassengers;
    private BigDecimal price;
    private Integer rating;

    // Optimistic locking between consumer replicas; a null version also marks the document as new,
    // which is what lets @CreatedDate work with client-assigned ids.
    @Version
    private Long version;

    @CreatedDate
    private Instant createdOn;

    @LastModifiedDate
    private Instant lastModifiedOn;

    private List<TripWaypoint> tripWayPoints = new ArrayList<>();
}
