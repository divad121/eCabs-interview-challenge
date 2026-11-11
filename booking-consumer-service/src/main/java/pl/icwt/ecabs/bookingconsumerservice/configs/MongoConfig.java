package pl.icwt.ecabs.bookingconsumerservice.configs;

import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    // Runs after Boot's property-based customizer (order 0), so this wins over spring.mongodb.representation.uuid.
    @Bean
    public MongoClientSettingsBuilderCustomizer uuidRepresentationCustomizer() {
        return settings -> settings.uuidRepresentation(UuidRepresentation.STANDARD);
    }

    // Replaces Boot's auto-configured conversions, so the BigDecimal representation is set here
    // rather than through spring.data.mongodb.representation.big-decimal.
    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return MongoCustomConversions.create(adapter -> adapter
                .bigDecimal(BigDecimalRepresentation.DECIMAL128)
                .registerConverter(OffsetDateTimeToDateConverter.INSTANCE)
                .registerConverter(DateToOffsetDateTimeConverter.INSTANCE));
    }

    // BSON dates are UTC instants: the instant is preserved, the client's original offset is not.
    @WritingConverter
    enum OffsetDateTimeToDateConverter implements Converter<OffsetDateTime, Date> {
        INSTANCE;

        @Override
        public Date convert(OffsetDateTime source) {
            return Date.from(source.toInstant());
        }
    }

    @ReadingConverter
    enum DateToOffsetDateTimeConverter implements Converter<Date, OffsetDateTime> {
        INSTANCE;

        @Override
        public OffsetDateTime convert(Date source) {
            return source.toInstant().atOffset(ZoneOffset.UTC);
        }
    }
}
