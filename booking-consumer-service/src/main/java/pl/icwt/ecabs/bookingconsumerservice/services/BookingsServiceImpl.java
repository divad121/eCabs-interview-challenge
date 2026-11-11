package pl.icwt.ecabs.bookingconsumerservice.services;

import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import pl.icwt.ecabs.bookingconsumerservice.entities.Booking;
import pl.icwt.ecabs.bookingconsumerservice.repositories.BookingsRepository;
import pl.icwt.ecabs.dtos.BookingDTO;

import java.util.UUID;

@Slf4j
@Service
public class BookingsServiceImpl implements BookingsService {

    private final BookingsRepository bookingsRepository;
    private final ModelMapper mapper;

    public BookingsServiceImpl(BookingsRepository bookingsRepository, ModelMapper mapper) {
        this.bookingsRepository = bookingsRepository;
        this.mapper = mapper;
    }

    @Override
    public void addBooking(final BookingDTO bookingDTO) {

        Booking booking = mapper.map(bookingDTO, Booking.class);

        try {
            bookingsRepository.save(booking);
            log.info("Booking {} added", booking.getBookingId());
        } catch (DuplicateKeyException e) {
            log.info("Booking {} already exists, ignoring redelivered add", booking.getBookingId());
        }
    }

    @Override
    public void editBooking(final UUID bookingId, final BookingDTO bookingDTO) {

        Booking booking = bookingsRepository.findById(bookingId).orElseThrow();

        mapper.map(bookingDTO, booking);
        // The message's booking ID is the one addressed by the URL; an ID inside the body must not redirect the write.
        booking.setBookingId(bookingId);

        bookingsRepository.save(booking);
        log.info("Booking {} updated", bookingId);
    }

    @Override
    public void delBooking(final UUID bookingId) {

        bookingsRepository.deleteByBookingId(bookingId);
        log.info("Booking {} deleted", bookingId);
    }
}
