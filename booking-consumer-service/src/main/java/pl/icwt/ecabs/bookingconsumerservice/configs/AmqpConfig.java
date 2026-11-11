package pl.icwt.ecabs.bookingconsumerservice.configs;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.icwt.ecabs.bookingconsumerservice.listeners.BookingErrorHandler;
import pl.icwt.ecabs.consts.BookingTopology;

@Configuration
public class AmqpConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter("pl.icwt.ecabs.messages");
    }

    @Bean
    Declarables bookingTopology() {
        return BookingTopology.declarables();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(final SimpleRabbitListenerContainerFactoryConfigurer configurer,
                                                                        final ConnectionFactory connectionFactory,
                                                                        final BookingErrorHandler errorHandler) {
        // The configurer applies spring.rabbitmq.listener.simple.* (concurrency, prefetch, retry, ...) and the MessageConverter bean.
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setErrorHandler(errorHandler);

        return factory;
    }
}
