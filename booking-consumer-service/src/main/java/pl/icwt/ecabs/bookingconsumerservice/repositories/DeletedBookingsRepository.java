package pl.icwt.ecabs.bookingconsumerservice.repositories;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import pl.icwt.ecabs.bookingconsumerservice.entities.DeletedBooking;

import java.util.UUID;

@Repository
public interface DeletedBookingsRepository extends MongoRepository<DeletedBooking, UUID> {
}
