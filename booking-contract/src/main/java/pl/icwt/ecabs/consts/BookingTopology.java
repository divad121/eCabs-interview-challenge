package pl.icwt.ecabs.consts;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * The RabbitMQ topology shared by producer and consumer. Both services declare it on startup, so whichever starts
 * first creates it and no message is published into a missing exchange or queue. Declarations are idempotent but
 * must stay identical in both services: RabbitMQ rejects a redeclaration with different arguments.
 * <p>
 * In Kubernetes the services do not declare anything ({@code spring.rabbitmq.dynamic=false}); the same topology is
 * created by the platform from {@code k8s/base/rabbitmq-topology.yaml}, which must be kept identical to this class.
 */
public final class BookingTopology {

    private BookingTopology() {
    }

    public static Declarables declarables() {
        FanoutExchange messageExchange = ExchangeBuilder.fanoutExchange(AmqpConsts.MESSAGE_EXCHANGE).durable(true).build();
        TopicExchange bookingExchange = ExchangeBuilder.topicExchange(AmqpConsts.BOOKING_EXCHANGE).durable(true).build();
        DirectExchange deadLetterExchange = ExchangeBuilder.directExchange(AmqpConsts.DEAD_LETTER_EXCHANGE).durable(true).build();

        // Every event is also appended to a stream: a replayable audit log with bounded retention, instead of a
        // classic queue that nothing consumes and that grows until the broker blocks all publishers.
        Queue auditStream = QueueBuilder.durable(AmqpConsts.AUDIT_QUEUE)
                .stream()
                .withArgument("x-max-age", "7D")
                .withArgument("x-max-length-bytes", 5_000_000_000L)
                .build();

        Queue bookingAddQueue = bookingQueue(AmqpConsts.BOOKING_ADD_QUEUE);
        Queue bookingEditQueue = bookingQueue(AmqpConsts.BOOKING_EDIT_QUEUE);
        Queue bookingDelQueue = bookingQueue(AmqpConsts.BOOKING_DEL_QUEUE);
        Queue deadLetterQueue = QueueBuilder.durable(AmqpConsts.DEAD_LETTER_QUEUE).quorum().build();

        return new Declarables(
                messageExchange,
                bookingExchange,
                deadLetterExchange,
                auditStream,
                bookingAddQueue,
                bookingEditQueue,
                bookingDelQueue,
                deadLetterQueue,
                BindingBuilder.bind(auditStream).to(messageExchange),
                BindingBuilder.bind(bookingExchange).to(messageExchange),
                BindingBuilder.bind(bookingAddQueue).to(bookingExchange).with(AmqpConsts.BOOKING_ADD_TOPIC),
                BindingBuilder.bind(bookingEditQueue).to(bookingExchange).with(AmqpConsts.BOOKING_EDIT_TOPIC),
                BindingBuilder.bind(bookingDelQueue).to(bookingExchange).with(AmqpConsts.BOOKING_DEL_TOPIC),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(AmqpConsts.DEAD_LETTER_ROUTING_KEY)
        );
    }

    // Quorum queues replicate across broker nodes (Raft), so losing a node loses no accepted booking.
    // At-least-once dead-lettering guarantees failed messages reach the DLQ; RabbitMQ requires reject-publish
    // overflow for it, which also turns a length limit (set by policy) into explicit back-pressure on publishers.
    private static Queue bookingQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deadLetterExchange(AmqpConsts.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(AmqpConsts.DEAD_LETTER_ROUTING_KEY)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }
}
