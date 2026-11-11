package pl.icwt.ecabs.bookingconsumerservice.integrations;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import pl.icwt.ecabs.bookingconsumerservice.configs.ModelMapperConfig;
import pl.icwt.ecabs.bookingconsumerservice.configs.MongoConfig;
import pl.icwt.ecabs.bookingconsumerservice.entities.Booking;
import pl.icwt.ecabs.bookingconsumerservice.repositories.BookingsRepository;
import pl.icwt.ecabs.bookingconsumerservice.repositories.DeletedBookingsRepository;
import pl.icwt.ecabs.bookingconsumerservice.services.BookingsService;
import pl.icwt.ecabs.bookingconsumerservice.services.BookingsServiceImpl;
import pl.icwt.ecabs.dtos.BookingDTO;
import pl.icwt.ecabs.dtos.TripWaypointDTO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.UUID;

@DataMongoTest
@Testcontainers
@Import({MongoConfig.class, ModelMapperConfig.class, BookingsServiceImpl.class})
class BookingsPersistenceTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0");

    @Autowired
    private BookingsService bookingsService;

    @Autowired
    private BookingsRepository repository;

    @Autowired
    private DeletedBookingsRepository deletedBookingsRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
        deletedBookingsRepository.deleteAll();
    }

    @Test
    void addPersistsBookingWithEmbeddedWaypoints() {
        BookingDTO bookingDTO = getSimpleBookingDTO();

        bookingsService.addBooking(bookingDTO);

        Booking saved = repository.findById(bookingDTO.getBookingId()).orElseThrow();
        Assertions.assertEquals(bookingDTO.getPassengerName(), saved.getPassengerName());
        Assertions.assertEquals(bookingDTO.getPickupTime().toInstant(), saved.getPickupTime().toInstant());
        Assertions.assertEquals(0, bookingDTO.getPrice().compareTo(saved.getPrice()));
        Assertions.assertEquals(1, saved.getTripWayPoints().size());
        Assertions.assertEquals(bookingDTO.getTripWayPoints().get(0).getTripWayPointId(), saved.getTripWayPoints().get(0).getTripWayPointId());
        Assertions.assertEquals(0L, saved.getVersion());
        Assertions.assertNotNull(saved.getCreatedOn());
    }

    @Test
    void storesNativeBsonTypes() {
        BookingDTO bookingDTO = getSimpleBookingDTO();

        bookingsService.addBooking(bookingDTO);

        Document raw = mongoTemplate.getCollection("bookings").find().first();
        Assertions.assertNotNull(raw);
        Assertions.assertEquals(bookingDTO.getBookingId(), raw.get("_id"));
        Assertions.assertInstanceOf(Decimal128.class, raw.get("price"));
        Assertions.assertInstanceOf(Date.class, raw.get("pickupTime"));
    }

    @Test
    void redeliveredAddIsIgnored() {
        BookingDTO bookingDTO = getSimpleBookingDTO();
        bookingsService.addBooking(bookingDTO);

        bookingDTO.setPassengerName("Changed By Redelivery");
        bookingsService.addBooking(bookingDTO);

        Assertions.assertEquals(1, repository.count());
        Assertions.assertEquals("John Doe", repository.findById(bookingDTO.getBookingId()).orElseThrow().getPassengerName());
    }

    @Test
    void editUpdatesFieldsAndRemovesDroppedWaypoints() {
        BookingDTO bookingDTO = getSimpleBookingDTO();
        bookingsService.addBooking(bookingDTO);

        bookingDTO.setPassengerName("Other Passenger Name");
        bookingDTO.getTripWayPoints().clear();
        bookingsService.editBooking(bookingDTO.getBookingId(), bookingDTO);

        Booking saved = repository.findById(bookingDTO.getBookingId()).orElseThrow();
        Assertions.assertEquals("Other Passenger Name", saved.getPassengerName());
        Assertions.assertEquals(0, saved.getTripWayPoints().size());
        Assertions.assertEquals(1L, saved.getVersion());
    }

    @Test
    void editUpdatesExistingWaypointInPlaceAndFollowsRequestOrder() {
        BookingDTO bookingDTO = getSimpleBookingDTO();
        bookingsService.addBooking(bookingDTO);
        TripWaypointDTO existing = bookingDTO.getTripWayPoints().get(0);
        Instant storedTimestamp = existing.getTripWayPointTimestamp();

        TripWaypointDTO pickup = new TripWaypointDTO();
        pickup.setTripWayPointId(UUID.randomUUID());
        pickup.setLastStop(false);
        pickup.setLocality("Sliema");
        pickup.setLat(35.912);
        pickup.setLng(14.504);
        existing.setLocality("Mosta");
        existing.setTripWayPointTimestamp(null);
        bookingDTO.getTripWayPoints().addFirst(pickup);

        bookingsService.editBooking(bookingDTO.getBookingId(), bookingDTO);

        Booking saved = repository.findById(bookingDTO.getBookingId()).orElseThrow();
        Assertions.assertEquals(2, saved.getTripWayPoints().size());
        Assertions.assertEquals(pickup.getTripWayPointId(), saved.getTripWayPoints().get(0).getTripWayPointId());
        Assertions.assertEquals(existing.getTripWayPointId(), saved.getTripWayPoints().get(1).getTripWayPointId());
        Assertions.assertEquals("Mosta", saved.getTripWayPoints().get(1).getLocality());
        Assertions.assertEquals(storedTimestamp, saved.getTripWayPoints().get(1).getTripWayPointTimestamp());
    }

    @Test
    void editIgnoresBookingIdInBody() {
        BookingDTO bookingDTO = getSimpleBookingDTO();
        bookingsService.addBooking(bookingDTO);
        UUID addressedId = bookingDTO.getBookingId();

        bookingDTO.setBookingId(UUID.randomUUID());
        bookingDTO.setPassengerName("Other Passenger Name");
        bookingsService.editBooking(addressedId, bookingDTO);

        Assertions.assertEquals(1, repository.count());
        Assertions.assertEquals("Other Passenger Name", repository.findById(addressedId).orElseThrow().getPassengerName());
    }

    @Test
    void deleteRemovesBooking() {
        BookingDTO bookingDTO = getSimpleBookingDTO();
        bookingsService.addBooking(bookingDTO);

        bookingsService.delBooking(bookingDTO.getBookingId());

        Assertions.assertEquals(0, repository.count());
    }

    @Test
    void addArrivingAfterDeleteDoesNotResurrectBooking() {
        BookingDTO bookingDTO = getSimpleBookingDTO();

        bookingsService.delBooking(bookingDTO.getBookingId());
        bookingsService.addBooking(bookingDTO);

        Assertions.assertEquals(0, repository.count());
    }

    @Test
    void editArrivingAfterDeleteIsIgnored() {
        BookingDTO bookingDTO = getSimpleBookingDTO();
        bookingsService.addBooking(bookingDTO);
        bookingsService.delBooking(bookingDTO.getBookingId());

        bookingDTO.setPassengerName("Other Passenger Name");
        Assertions.assertDoesNotThrow(() -> bookingsService.editBooking(bookingDTO.getBookingId(), bookingDTO));

        Assertions.assertEquals(0, repository.count());
    }

    @Test
    void editForUnknownBookingStillFailsSoItIsRetried() {
        BookingDTO bookingDTO = getSimpleBookingDTO();

        Assertions.assertThrows(NoSuchElementException.class,
                () -> bookingsService.editBooking(bookingDTO.getBookingId(), bookingDTO));
    }

    @Test
    void deletedBookingTombstonesExpireAfterAWeek() {
        Document expiryIndex = mongoTemplate.getCollection("deleted_bookings").listIndexes().into(new ArrayList<>())
                .stream()
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .findFirst()
                .orElseThrow();

        Assertions.assertEquals(new Document("deletedOn", 1), expiryIndex.get("key", Document.class));
        Assertions.assertEquals(Duration.ofDays(7).toSeconds(), expiryIndex.get("expireAfterSeconds", Number.class).longValue());
    }

    private BookingDTO getSimpleBookingDTO() {
        TripWaypointDTO waypointDTO = new TripWaypointDTO();
        waypointDTO.setTripWayPointId(UUID.randomUUID());
        waypointDTO.setLastStop(true);
        waypointDTO.setLocality("Naxxar");
        waypointDTO.setLat(35.914982);
        waypointDTO.setLng(14.445219);
        waypointDTO.setTripWayPointTimestamp(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        BookingDTO bookingDTO = new BookingDTO();
        bookingDTO.setBookingId(UUID.randomUUID());
        bookingDTO.setPassengerName("John Doe");
        bookingDTO.setPassengerContactNumber("+35699740158");
        bookingDTO.setPickupTime(OffsetDateTime.now(ZoneOffset.ofHours(2)).truncatedTo(ChronoUnit.MILLIS));
        bookingDTO.setAsap(true);
        bookingDTO.setWaitingTime(1);
        bookingDTO.setNoOfPassengers(2);
        bookingDTO.setPrice(new BigDecimal("25.10"));
        bookingDTO.setRating(5);
        bookingDTO.getTripWayPoints().add(waypointDTO);

        return bookingDTO;
    }
}
