package pl.icwt.ecabs.bookingproducerservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import pl.icwt.ecabs.consts.AmqpConsts;
import pl.icwt.ecabs.messages.AddBookingMessage;
import pl.icwt.ecabs.messages.DelBookingMessage;
import pl.icwt.ecabs.messages.EditBookingMessage;

import java.net.ConnectException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class BookingIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RabbitTemplate rabbitTemplate;

	private static final String MINIMAL_BOOKING = "{\"passengerName\": \"TestName\", \"passengerContactNumber\": \"+35699740158\", \"tripWayPoints\": []}";

	@BeforeEach
	void brokerConfirmsEveryMessage() {
		brokerConfirms(new CorrelationData.Confirm(true, null));
	}

	private void brokerConfirms(CorrelationData.Confirm confirm) {
		Mockito.doAnswer(invocation -> {
			invocation.<CorrelationData>getArgument(3).getFuture().complete(confirm);
			return null;
		}).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
	}

	@Test
	void testAddBookingSuccess() throws Exception {

		final String payload = "{\n" +
				"    \"passengerName\": \"TestName\",\n" +
				"    \"passengerContactNumber\": \"+35699740158\",\n" +
				"    \"pickupTime\": \"2020-07-10T19:19:30.4325019+02:00\",\n" +
				"    \"asap\": true,\n" +
				"    \"waitingTime\": 0,\n" +
				"    \"noOfPassengers\": 1,\n" +
				"    \"price\": 25,\n" +
				"    \"rating\": 5,\n" +
				"    \"tripWayPoints\": [\n" +
				"        {\n" +
				"            \"lastStop\": false,\n" +
				"            \"locality\": \"Naxxar\",\n" +
				"            \"lat\": 14.0,\n" +
				"            \"lng\": 54.0\n" +
				"        },\n" +
				"                {\n" +
				"            \"lastStop\": false,\n" +
				"            \"locality\": \"Gharghur\",\n" +
				"            \"lat\": 14.0,\n" +
				"            \"lng\": 54.0\n" +
				"        },\n" +
				"                {\n" +
				"            \"lastStop\": false,\n" +
				"            \"locality\": \"Gzira\",\n" +
				"            \"lat\": 14.0,\n" +
				"            \"lng\": 54.0\n" +
				"        },\n" +
				"                {\n" +
				"            \"lastStop\": false,\n" +
				"            \"locality\": \"Mosta\",\n" +
				"            \"lat\": 14.0,\n" +
				"            \"lng\": 54.0\n" +
				"        }\n" +
				"    ]\n" +
				"}";


		mockMvc.perform(post("/bookings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(MockMvcResultMatchers.status().isAccepted())
				.andReturn();

		ArgumentCaptor<AddBookingMessage> captor = ArgumentCaptor.forClass(AddBookingMessage.class);
		Mockito.verify(rabbitTemplate).convertAndSend(eq(AmqpConsts.MESSAGE_EXCHANGE), eq(AmqpConsts.BOOKING_ADD_TOPIC), captor.capture(), any(CorrelationData.class));

		AddBookingMessage message = captor.getValue();
		Assertions.assertEquals("TestName", message.getBooking().getPassengerName());
		Assertions.assertEquals("+35699740158", message.getBooking().getPassengerContactNumber());
		Assertions.assertEquals(4, message.getBooking().getTripWayPoints().size());

	}

	@Test
	void testAddBookingInvalidPhoneNumber() throws Exception {

		final String payload = "{\n" +
				"    \"passengerName\": \"TestName\",\n" +
				"    \"passengerContactNumber\": \"35699\",\n" +
				"    \"pickupTime\": \"2020-07-10T19:19:30.4325019+02:00\",\n" +
				"    \"asap\": true,\n" +
				"    \"waitingTime\": 0,\n" +
				"    \"noOfPassengers\": 1,\n" +
				"    \"price\": 25,\n" +
				"    \"rating\": 5,\n" +
				"    \"tripWayPoints\": []\n" +
				"}";


		mockMvc.perform(post("/bookings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(MockMvcResultMatchers.status().isBadRequest())
				.andReturn();
	}

	@Test
	void testEditBookingSuccess() throws Exception {

		final String payload = "{\n" +
				"    \"bookingId\": \"6c53a2fa-776a-4f42-81a6-69b1da0fc120\",\n" +
				"    \"passengerName\": \"TestName\",\n" +
				"    \"passengerContactNumber\": \"+35699740158\",\n" +
				"    \"pickupTime\": \"2020-07-10T17:19:30.4325019Z\",\n" +
				"    \"asap\": true,\n" +
				"    \"waitingTime\": 0,\n" +
				"    \"noOfPassengers\": 1,\n" +
				"    \"price\": 25,\n" +
				"    \"rating\": 5,\n" +
				"    \"tripWayPoints\": [\n" +
				"        {\n" +
				"            \"tripWayPointId\": \"9560c0a4-730b-4924-ad47-b7663576f43e\",\n" +
				"            \"lastStop\": false,\n" +
				"            \"locality\": \"Naxxar\",\n" +
				"            \"lat\": 14.0,\n" +
				"            \"lng\": 54.0\n" +
				"        }\n" +
				"    ]\n" +
				"}";


		mockMvc.perform(put("/bookings/6c53a2fa-776a-4f42-81a6-69b1da0fc120")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(MockMvcResultMatchers.status().isAccepted())
				.andReturn();

		ArgumentCaptor<EditBookingMessage> captor = ArgumentCaptor.forClass(EditBookingMessage.class);
		Mockito.verify(rabbitTemplate).convertAndSend(eq(AmqpConsts.MESSAGE_EXCHANGE), eq(AmqpConsts.BOOKING_EDIT_TOPIC), captor.capture(), any(CorrelationData.class));

		EditBookingMessage message = captor.getValue();
		Assertions.assertEquals("6c53a2fa-776a-4f42-81a6-69b1da0fc120", message.getBookingId().toString());
		Assertions.assertEquals("TestName", message.getBooking().getPassengerName());
		Assertions.assertEquals("+35699740158", message.getBooking().getPassengerContactNumber());
		Assertions.assertEquals(1, message.getBooking().getTripWayPoints().size());
	}

	@Test
	void testDelBookingSuccess() throws Exception {

		mockMvc.perform(delete("/bookings/6c53a2fa-776a-4f42-81a6-69b1da0fc120"))
				.andExpect(MockMvcResultMatchers.status().isAccepted())
				.andReturn();

		ArgumentCaptor<DelBookingMessage> captor = ArgumentCaptor.forClass(DelBookingMessage.class);
		Mockito.verify(rabbitTemplate).convertAndSend(eq(AmqpConsts.MESSAGE_EXCHANGE), eq(AmqpConsts.BOOKING_DEL_TOPIC), captor.capture(), any(CorrelationData.class));

		DelBookingMessage message = captor.getValue();
		Assertions.assertEquals("6c53a2fa-776a-4f42-81a6-69b1da0fc120", message.getBookingId().toString());
	}

	@Test
	void testAddBookingRejectedByBrokerReturns503() throws Exception {
		brokerConfirms(new CorrelationData.Confirm(false, "queue full"));

		mockMvc.perform(post("/bookings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(MINIMAL_BOOKING))
				.andExpect(MockMvcResultMatchers.status().isServiceUnavailable());
	}

	@Test
	void testAddBookingBrokerUnreachableReturns503() throws Exception {
		Mockito.doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
				.when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

		mockMvc.perform(post("/bookings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(MINIMAL_BOOKING))
				.andExpect(MockMvcResultMatchers.status().isServiceUnavailable());
	}
}
