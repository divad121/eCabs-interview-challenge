package pl.icwt.ecabs.bookingproducerservice.services;

/**
 * The broker did not confirm that it accepted a booking event, so the request must not be acknowledged to the client.
 */
public class BookingPublishException extends RuntimeException {

    public BookingPublishException(String message) {
        super(message);
    }

    public BookingPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
