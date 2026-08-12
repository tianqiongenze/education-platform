package com.ep.business.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
class SensitiveDataConverterTest {
    private final SensitiveDataConverter converter = new SensitiveDataConverter();

    @Test
    void masksPassword() {
        ILoggingEvent event = mockEvent("password=secret123 login success");
        assertEquals("password=**** login success", converter.convert(event));
    }

    @Test
    void masksTokenInJson() {
        ILoggingEvent event = mockEvent("{\"token\":\"abc-xyz-123\",\"user\":\"john\"}");
        String result = converter.convert(event);
        assertTrue(result.contains("****"));
        assertTrue(result.contains("john"));
    }

    @Test
    void masksPhoneNumber() {
        ILoggingEvent event = mockEvent("User phone: 13812345678 called");
        assertEquals("User phone: 138****5678 called", converter.convert(event));
    }

    @Test
    void masksIdCard() {
        ILoggingEvent event = mockEvent("ID: 110101199001011234 verified");
        assertTrue(converter.convert(event).contains("110101********1234"));
    }

    // --- D-101: email + bank card masking ---

    @Test
    void masksEmail() {
        ILoggingEvent event = mockEvent("User email: johndoe@example.com registered");
        String result = converter.convert(event);
        assertTrue(result.contains("***@example.com"), "local part should be masked");
        assertFalse(result.contains("johndoe@"), "full local part must not survive");
    }

    @Test
    void masksEmailKeepsFirstTwoChars() {
        ILoggingEvent event = mockEvent("Contact: ab+test@sub.example.org");
        String result = converter.convert(event);
        assertTrue(result.contains("ab***@"), "first 2 chars of local part kept");
        assertTrue(result.contains("sub.example.org"), "domain preserved");
    }

    @Test
    void masksBankCard() {
        ILoggingEvent event = mockEvent("Payment with card 6222021234567890123 succeeded");
        String result = converter.convert(event);
        assertTrue(result.contains("6222********0123"), "first 4 + last 4 kept, middle masked");
        assertFalse(result.contains("6222021234567890123"), "full card number must not survive");
    }

    @Test
    void masksBankCardSixteenDigits() {
        ILoggingEvent event = mockEvent("Card 4111111111111111 charged");
        String result = converter.convert(event);
        assertTrue(result.contains("4111********1111"), "16-digit card masked");
    }

    @Test
    void combinedPiiInOneMessage() {
        ILoggingEvent event = mockEvent(
            "user=alice email=alice@test.com phone=13912345678 card=6225751234567890 pwd=s3cr3t");
        String result = converter.convert(event);
        assertFalse(result.contains("s3cr3t"), "password masked");
        assertFalse(result.contains("alice@"), "email local part masked");
        assertFalse(result.contains("13912345678"), "phone masked");
        assertFalse(result.contains("6225751234567890"), "card masked");
    }

    @Test
    void doesNotMaskNormalText() {
        ILoggingEvent event = mockEvent("Document uploaded successfully");
        assertEquals("Document uploaded successfully", converter.convert(event));
    }

    @Test
    void returnsEmptyForNullMessage() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(null);
        assertEquals("", converter.convert(event));
    }

    private ILoggingEvent mockEvent(String msg) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(msg);
        return event;
    }
}
