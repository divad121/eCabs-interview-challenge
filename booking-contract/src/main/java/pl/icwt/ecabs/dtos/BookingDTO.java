package pl.icwt.ecabs.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class BookingDTO {

    private UUID bookingId;

    @NotBlank
    private String passengerName;

    @Pattern( regexp = "^\\+(?:[0-9] ?){6,14}[0-9]$", message = "Invalid phone number")
    private String passengerContactNumber;

    private OffsetDateTime pickupTime;

    private Boolean asap = true;

    private Integer waitingTime;

    @Positive(message = "Number of passengers must be positive")
    private Integer noOfPassengers;

    private BigDecimal price;

    @Min(value = 1, message = "Rating must be a number between 1 and 5")
    @Max(value = 5, message = "Rating must be a number between 1 and 5")
    private Integer rating;

    private List<TripWaypointDTO> tripWayPoints = new ArrayList<>();
}
