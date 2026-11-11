package pl.icwt.ecabs.bookingconsumerservice.services;

import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import pl.icwt.ecabs.bookingconsumerservice.entities.Booking;
import pl.icwt.ecabs.bookingconsumerservice.entities.DeletedBooking;
import pl.icwt.ecabs.bookingconsumerservice.repositories.BookingsRepository;
import pl.icwt.ecabs.bookingconsumerservice.repositories.DeletedBookingsRepository;
import pl.icwt.ecabs.dtos.BookingDTO;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class BookingsServiceImpl implements BookingsService {

    private final BookingsRepository bookingsRepository;
    private final DeletedBookingsRepository deletedBookingsRepository;
    private final ModelMapper mapper;

    public BookingsServiceImpl(BookingsRepository bookingsRepository,
                               DeletedBookingsRepository deletedBookingsRepository,
                               ModelMapper mapper) {
        this.bookingsRepository = bookingsRepository;
        this.deletedBookingsRepository = deletedBookingsRepository;
        this.mapper = mapper;
    }

    @Override
    public void addBooking(final BookingDTO bookingDTO) {

        Booking booking = mapper.map(bookingDTO, Booking.class);
        UUID bookingId = booking.getBookingId();

        try {
            bookingsRepository.save(booking);
            log.info("Booking {} added", bookingId);
        } catch (DuplicateKeyException e) {
            log.info("Booking {} already exists, ignoring redelivered add", bookingId);
        }

        // Checked after the insert, not before: delete writes its tombstone before removing the booking, so whichever
        // order concurrent consumers interleave in, either the delete removes this booking or this check does.
        if (deletedBookingsRepository.existsById(bookingId)) {
            bookingsRepository.deleteByBookingId(bookingId);
            log.info("Booking {} was already deleted, discarded the late add", bookingId);
        }
    }

    @Override
    public void editBooking(final UUID bookingId, final BookingDTO bookingDTO) {

        Optional<Booking> stored = bookingsRepository.findById(bookingId);
        if (stored.isEmpty() && deletedBookingsRepository.existsById(bookingId)) {
            log.info("Booking {} was already deleted, ignoring the late edit", bookingId);
            return;
        }
        // Not stored and not deleted: the add has not been processed yet, so fail and let the retry wait for it.
        Booking booking = stored.orElseThrow();

        mapper.map(bookingDTO, booking);
        // The message's booking ID is the one addressed by the URL; an ID inside the body must not redirect the write.
        booking.setBookingId(bookingId);

        // A delete processed meanwhile makes this versioned save fail; the retry then finds the tombstone.
        bookingsRepository.save(booking);
        log.info("Booking {} updated", bookingId);
    }

    @Override
    public void delBooking(final UUID bookingId) {

        deletedBookingsRepository.save(new DeletedBooking(bookingId, Instant.now()));
        bookingsRepository.deleteByBookingId(bookingId);
        log.info("Booking {} deleted", bookingId);
    }
}
