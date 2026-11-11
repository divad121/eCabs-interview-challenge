package pl.icwt.ecabs.bookingconsumerservice.configs;

import org.modelmapper.Conditions;
import org.modelmapper.Converter;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.icwt.ecabs.bookingconsumerservice.entities.Booking;
import pl.icwt.ecabs.bookingconsumerservice.entities.TripWaypoint;
import pl.icwt.ecabs.dtos.BookingDTO;
import pl.icwt.ecabs.dtos.TripWaypointDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Configuration
public class ModelMapperConfig {

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper mapper = new ModelMapper();
        mapper.getConfiguration().setPropertyCondition(Conditions.isNotNull());

        TypeMap<BookingDTO, Booking> typeMap = mapper.createTypeMap(BookingDTO.class, Booking.class);

        typeMap.addMappings(mapping -> mapping.using(waypointConverter(mapper))
                .map(BookingDTO::getTripWayPoints, Booking::setTripWayPoints));

        return mapper;
    }

    // The incoming list is authoritative for order and membership: waypoints missing from it are dropped.
    // Waypoints that already exist are updated in place, so fields the client omitted keep their stored values.
    private Converter<List<TripWaypointDTO>, List<TripWaypoint>> waypointConverter(ModelMapper mapper) {
        return context -> {
            Map<UUID, TripWaypoint> existing = new HashMap<>();
            if (Objects.nonNull(context.getDestination())) {
                context.getDestination().forEach(waypoint -> existing.put(waypoint.getTripWayPointId(), waypoint));
            }

            List<TripWaypoint> result = new ArrayList<>(context.getSource().size());
            for (TripWaypointDTO waypointDTO : context.getSource()) {
                TripWaypoint current = existing.get(waypointDTO.getTripWayPointId());
                if (Objects.nonNull(current)) {
                    mapper.map(waypointDTO, current);
                    result.add(current);
                } else {
                    result.add(mapper.map(waypointDTO, TripWaypoint.class));
                }
            }
            return result;
        };
    }
}
