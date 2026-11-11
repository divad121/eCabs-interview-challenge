package pl.icwt.ecabs.bookingproducerservice.services;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.icwt.ecabs.consts.AmqpConsts;
import pl.icwt.ecabs.dtos.BookingDTO;
import pl.icwt.ecabs.messages.AddBookingMessage;
import pl.icwt.ecabs.messages.DelBookingMessage;
import pl.icwt.ecabs.messages.EditBookingMessage;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class BookingsServiceImpl implements BookingsService {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public BookingsServiceImpl(RabbitTemplate rabbitTemplate,
                               @Value("${booking.publish.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public BookingDTO addBooking(final BookingDTO bookingDTO) {

        bookingDTO.setBookingId(UUID.randomUUID());
        bookingDTO.getTripWayPoints().forEach(tripWaypointDTO -> tripWaypointDTO.setTripWayPointId(UUID.randomUUID()));
        AddBookingMessage message = new AddBookingMessage();
        message.setBooking(bookingDTO);

        publish(AmqpConsts.BOOKING_ADD_TOPIC, message);

        return bookingDTO;
    }

    @Override
    public BookingDTO editBooking(UUID bookingId, BookingDTO bookingDTO) {
        bookingDTO.setBookingId(bookingId);
        EditBookingMessage message = new EditBookingMessage();
        message.setBookingId(bookingId);
        message.setBooking(bookingDTO);

        bookingDTO.getTripWayPoints().forEach(tripWaypointDTO -> {
            if(Objects.isNull(tripWaypointDTO.getTripWayPointId())) {
                tripWaypointDTO.setTripWayPointId(UUID.randomUUID());
            }
        });

        publish(AmqpConsts.BOOKING_EDIT_TOPIC, message);

        return bookingDTO;
    }

    @Override
    public void delBooking(UUID bookingId) {
        DelBookingMessage message = new DelBookingMessage();
        message.setBookingId(bookingId);

        publish(AmqpConsts.BOOKING_DEL_TOPIC, message);
    }

    // Returns only once the broker has confirmed the message is stored (replicated, for quorum queues).
    // Without this, a broker failure or a full queue would silently drop a booking we already answered 202 for.
    private void publish(String routingKey, Object message) {
        CorrelationData correlation = new CorrelationData();
        rabbitTemplate.convertAndSend(AmqpConsts.MESSAGE_EXCHANGE, routingKey, message, correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BookingPublishException("Timed out waiting for broker confirm", e);
        } catch (ExecutionException e) {
            throw new BookingPublishException("Broker confirm failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BookingPublishException("Interrupted while waiting for broker confirm", e);
        }

        if (!confirm.ack()) {
            throw new BookingPublishException("Broker rejected message: " + confirm.reason());
        }
        if (correlation.getReturned() != null) {
            throw new BookingPublishException("Message was not routed to any queue");
        }
    }
}
