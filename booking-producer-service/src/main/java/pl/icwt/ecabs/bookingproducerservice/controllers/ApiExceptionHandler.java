package pl.icwt.ecabs.bookingproducerservice.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.icwt.ecabs.bookingproducerservice.services.BookingPublishException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    // 503 tells clients and load balancers the failure is temporary and safe to retry; the cause stays in our logs.
    @ExceptionHandler({BookingPublishException.class, AmqpException.class})
    public ProblemDetail handleBrokerUnavailable(RuntimeException e) {
        log.error("Booking event could not be published", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Booking could not be accepted right now, please retry");
    }
}
