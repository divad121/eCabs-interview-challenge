package pl.icwt.ecabs.bookingconsumerservice.listeners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

@Slf4j
@Component
public class BookingErrorHandler implements ErrorHandler {

    // Reached only after retries are exhausted. Logs the queue and full cause (never the payload: it contains
    // passenger data) and rejects without requeue, so the broker dead-letters the message to the DLQ.
    @Override
    public void handleError(Throwable t) {
        String queue = "unknown";
        if (t instanceof ListenerExecutionFailedException failure && failure.getFailedMessage() != null) {
            Message failed = failure.getFailedMessage();
            queue = failed.getMessageProperties().getConsumerQueue();
        }

        log.error("Message from queue {} could not be processed and was dead-lettered", queue, t);
        throw new AmqpRejectAndDontRequeueException("Unable to process message", t);
    }
}
