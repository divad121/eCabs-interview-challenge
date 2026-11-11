package pl.icwt.ecabs.bookingconsumerservice.integrations;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import pl.icwt.ecabs.bookingconsumerservice.repositories.BookingsRepository;
import pl.icwt.ecabs.consts.AmqpConsts;
import pl.icwt.ecabs.dtos.BookingDTO;
import pl.icwt.ecabs.dtos.TripWaypointDTO;
import pl.icwt.ecabs.messages.AddBookingMessage;
import pl.icwt.ecabs.messages.EditBookingMessage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

// Extension order matters: Testcontainers is registered first so its afterAll runs last, and @DirtiesContext
// closes the listeners before the broker is stopped instead of leaving a cached context reconnecting to nothing.
@Testcontainers
@SpringBootTest
@DirtiesContext
class BookingsMessagingTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.4-management");

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private BookingsRepository repository;

    @Test
    void topologyIsDeclaredWithQuorumQueuesAndAuditStream() throws Exception {
        ExecResult result = rabbit.execInContainer("rabbitmqctl", "list_queues", "--quiet", "name", "type");

        Assertions.assertTrue(result.getStdout().contains(AmqpConsts.BOOKING_ADD_QUEUE + "\tquorum"), result.getStdout());
        Assertions.assertTrue(result.getStdout().contains(AmqpConsts.DEAD_LETTER_QUEUE + "\tquorum"), result.getStdout());
        Assertions.assertTrue(result.getStdout().contains(AmqpConsts.AUDIT_QUEUE + "\tstream"), result.getStdout());
    }

    @Test
    void addMessageIsConsumedAndPersisted() {
        BookingDTO booking = getSimpleBookingDTO("John Doe");

        AddBookingMessage message = new AddBookingMessage();
        message.setBooking(booking);
        rabbitTemplate.convertAndSend(AmqpConsts.MESSAGE_EXCHANGE, AmqpConsts.BOOKING_ADD_TOPIC, message);

        await().atMost(Duration.ofSeconds(10)).until(() -> repository.existsById(booking.getBookingId()));
    }

    @Test
    void editArrivingBeforeAddIsRetriedInsteadOfDeadLettered() throws Exception {
        BookingDTO original = getSimpleBookingDTO("Original Name");
        BookingDTO edited = getSimpleBookingDTO("Edited Name");
        edited.setBookingId(original.getBookingId());

        EditBookingMessage edit = new EditBookingMessage();
        edit.setBookingId(original.getBookingId());
        edit.setBooking(edited);
        rabbitTemplate.convertAndSend(AmqpConsts.MESSAGE_EXCHANGE, AmqpConsts.BOOKING_EDIT_TOPIC, edit);

        Thread.sleep(300);
        AddBookingMessage add = new AddBookingMessage();
        add.setBooking(original);
        rabbitTemplate.convertAndSend(AmqpConsts.MESSAGE_EXCHANGE, AmqpConsts.BOOKING_ADD_TOPIC, add);

        await().atMost(Duration.ofSeconds(15)).until(() -> repository.findById(original.getBookingId())
                .map(b -> "Edited Name".equals(b.getPassengerName()))
                .orElse(false));
    }

    private BookingDTO getSimpleBookingDTO(String passengerName) {
        TripWaypointDTO waypointDTO = new TripWaypointDTO();
        waypointDTO.setTripWayPointId(UUID.randomUUID());
        waypointDTO.setLastStop(true);
        waypointDTO.setLocality("Naxxar");
        waypointDTO.setLat(35.914982);
        waypointDTO.setLng(14.445219);

        BookingDTO bookingDTO = new BookingDTO();
        bookingDTO.setBookingId(UUID.randomUUID());
        bookingDTO.setPassengerName(passengerName);
        bookingDTO.setPassengerContactNumber("+35699740158");
        bookingDTO.setPickupTime(OffsetDateTime.now());
        bookingDTO.setNoOfPassengers(2);
        bookingDTO.setPrice(new BigDecimal("25.10"));
        bookingDTO.getTripWayPoints().add(waypointDTO);
        return bookingDTO;
    }
}
