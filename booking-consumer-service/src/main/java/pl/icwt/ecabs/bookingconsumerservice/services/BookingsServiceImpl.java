package pl.icwt.ecabs.bookingconsumerservice.services;

import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import pl.icwt.ecabs.bookingconsumerservice.entities.Booking;
import pl.icwt.ecabs.bookingconsumerservice.repositories.BookingsRepository;
import pl.icwt.ecabs.dtos.BookingDTO;
import pl.icwt.ecabs.dtos.TripWaypointDTO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

        log.debug("Saving {}", booking.getBookingId());

        try {
            bookingsRepository.save(booking);
        } catch (DuplicateKeyException e) {
            log.info("Booking {} already exists, ignoring redelivered add", booking.getBookingId());
        }
    }

    @Override
    public void editBooking(final UUID bookingId, final BookingDTO bookingDTO) {

        Booking booking = bookingsRepository.findById(bookingId).orElseThrow();
        List<UUID> activeWaypointsIds = bookingDTO.getTripWayPoints().stream().map(TripWaypointDTO::getTripWayPointId).collect(Collectors.toList());

        mapper.map(bookingDTO, booking);
        booking.getTripWayPoints().removeIf(tripWaypoint -> !activeWaypointsIds.contains(tripWaypoint.getTripWayPointId()));

        log.debug("Updating booking {}", booking.getBookingId());

        bookingsRepository.save(booking);
    }

    @Override
    public void delBooking(final UUID bookingId) {

        log.debug("Removing booking {}", bookingId);
        bookingsRepository.deleteByBookingId(bookingId);
    }
}
