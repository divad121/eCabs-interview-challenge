package pl.icwt.ecabs.bookingconsumerservice.repositories;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import pl.icwt.ecabs.bookingconsumerservice.entities.Booking;

import java.util.UUID;

@Repository
public interface BookingsRepository extends MongoRepository<Booking, UUID> {
    void deleteByBookingId(UUID id);
}
