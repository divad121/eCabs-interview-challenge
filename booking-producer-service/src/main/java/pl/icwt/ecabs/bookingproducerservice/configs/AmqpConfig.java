package pl.icwt.ecabs.bookingproducerservice.configs;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.icwt.ecabs.consts.BookingTopology;

@Configuration
public class AmqpConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    Declarables bookingTopology() {
        return BookingTopology.declarables();
    }

}
