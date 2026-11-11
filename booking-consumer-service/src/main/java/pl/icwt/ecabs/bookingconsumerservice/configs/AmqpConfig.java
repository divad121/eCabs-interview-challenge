package pl.icwt.ecabs.bookingconsumerservice.configs;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.icwt.ecabs.bookingconsumerservice.listeners.BookingErrorHandler;
import pl.icwt.ecabs.consts.AmqpConsts;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class AmqpConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter("pl.icwt.ecabs.messages");
    }

    @Bean
    Declarables bindings() {

        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", AmqpConsts.DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", "deadLetter");

        FanoutExchange massageExchange =  new FanoutExchange(AmqpConsts.MESSAGE_EXCHANGE);
        TopicExchange bookingExchange = new TopicExchange(AmqpConsts.BOOKING_EXCHANGE);
        Queue messageAuditQueue = new Queue(AmqpConsts.AUDIT_QUEUE);
        Queue bookingAddQueue = new Queue(AmqpConsts.BOOKING_ADD_QUEUE,true,false,false, args);
        Queue bookingEditQueue = new Queue(AmqpConsts.BOOKING_EDIT_QUEUE,true,false,false, args);
        Queue bookingDelQueue = new Queue(AmqpConsts.BOOKING_DEL_QUEUE,true,false,false, args);

        DirectExchange deadLetterExchange = new DirectExchange(AmqpConsts.DEAD_LETTER_EXCHANGE);
        Queue deadLetterQueue = new Queue(AmqpConsts.DEAD_LETTER_QUEUE, true);

        return new Declarables(massageExchange,
                massageExchange,
                bookingExchange,
                messageAuditQueue,
                bookingAddQueue,
                bookingEditQueue,
                bookingDelQueue,
                deadLetterExchange,
                deadLetterQueue,
                BindingBuilder.bind(messageAuditQueue).to(massageExchange),
                BindingBuilder.bind(bookingExchange).to(massageExchange),
                BindingBuilder.bind(bookingAddQueue).to(bookingExchange).with(AmqpConsts.BOOKING_ADD_TOPIC),
                BindingBuilder.bind(bookingEditQueue).to(bookingExchange).with(AmqpConsts.BOOKING_EDIT_TOPIC),
                BindingBuilder.bind(bookingDelQueue).to(bookingExchange).with(AmqpConsts.BOOKING_DEL_TOPIC),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("deadLetter")
        );
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(final SimpleRabbitListenerContainerFactoryConfigurer configurer,
                                                                        final ConnectionFactory connectionFactory,
                                                                        final BookingErrorHandler errorHandler) {
        // The configurer applies spring.rabbitmq.listener.simple.* (concurrency, prefetch, ...) and the MessageConverter bean.
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setErrorHandler(errorHandler);

        return factory;
    }
}
