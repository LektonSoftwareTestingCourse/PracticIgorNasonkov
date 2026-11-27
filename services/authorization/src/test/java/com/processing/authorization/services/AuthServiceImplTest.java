package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-01-31T23:59:59Z");

    @Mock private LimitUsageRepository limitUsageRepository;
    @Mock private AuthorizationEventNotifier eventNotifier;
    @Mock private BinLookupClient binLookupClient;
    @Mock private CardManagementClient cardManagementClient;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limitUsageRepository, eventNotifier, binLookupClient, cardManagementClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000", "1000.00", "1001"})
    void shouldApproveWhenBalanceCoversAmountIncludingEquality(String balance) {
        // Arrange
        availableCard(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 1), balance));
        limitsUpdated();

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).matches("[0-9]{12}");
        assertThat(response.authCode()).matches("[A-Z0-9]{6}");
        assertThat(response.declineReason()).isNull();
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldDeclineWhenCardIsNotActive(CardModelStatus status) {
        // Arrange
        availableCard(card(status, YearMonth.of(2026, 1), "1000"));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("CARD_" + status.name());
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenCardStatusIsUnknown() {
        // Arrange
        availableCard(card(null, YearMonth.of(2026, 1), "1000"));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, "UNKNOWN_REASON", "05");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenTransmissionDateIsAfterExpiryMonth() {
        // Arrange
        availableCard(card(CardModelStatus.ACTIVE, YearMonth.of(2025, 12), "1000"));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, "CARD_EXPIRED", "54");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenBalanceIsBelowAmount() {
        // Arrange
        availableCard(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 1), "999.99"));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, "INSUFFICIENT_FUNDS", "51");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("lookupFailures")
    void shouldDeclineWhenCardLookupFails(RuntimeException failure, String reason, String code) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(failure);

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, reason, code);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldApproveWhenExternalBinLookupHasNoIssuer() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        limitsUpdated();

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenLimitsWouldBeExceeded() {
        // Arrange
        availableCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(eq(PAN), any(), eq(AMOUNT), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, "EXCEEDS_AMOUNT_LIMIT", "61");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldApproveWhenDuplicateLimitInsertSucceedsOnRetry() {
        // Arrange
        availableCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(eq(PAN), any(), eq(AMOUNT), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenDuplicateLimitInsertRetryFails() {
        // Arrange
        availableCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(eq(PAN), any(), eq(AMOUNT), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert"))
                .thenThrow(new IllegalStateException("database down"));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, "DB_UNAVAILABLE", "96");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenLimitStorageIsUnavailable() {
        // Arrange
        availableCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(eq(PAN), any(), eq(AMOUNT), any(), any()))
                .thenThrow(new IllegalStateException("database down"));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, "DB_UNAVAILABLE", "96");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("reservationFailures")
    void shouldDeclineWhenReservationFails(RuntimeException failure, String reason, String code) {
        // Arrange
        availableCard(activeCard());
        limitsUpdated();
        doThrow(failure).when(cardManagementClient).reserve(eq(AMOUNT), anyString(), eq(PAN));

        // Act
        AuthorizationResponse response = service.authorize(validRequest(), Instant.now());

        // Assert
        assertDeclined(response, reason, code);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldApproveRollbackWhenCardManagementSucceeds() {
        // Arrange
        RollbackRequest request = rollbackRequest();

        // Act
        RollbackResponse response = service.rollback(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(any(AuthServiceRollbackEvent.class));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWhenCardManagementFails(RuntimeException failure, String reason, String code) {
        // Arrange
        RollbackRequest request = rollbackRequest();
        doThrow(failure).when(cardManagementClient).rollback(request);

        // Act
        RollbackResponse response = service.rollback(request, Instant.now());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    private void availableCard(CardModel card) {
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of("ISS001"));
    }

    private void limitsUpdated() {
        when(limitUsageRepository.upsertLimitUsage(PAN, LocalDate.of(2026, 1, 31), AMOUNT,
                new BigDecimal("10000"), new BigDecimal("50000"))).thenReturn(1);
    }

    private static void assertDeclined(AuthorizationResponse response, String reason, String code) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.authCode()).isNull();
    }

    private static CardModel activeCard() {
        return card(CardModelStatus.ACTIVE, YearMonth.of(2026, 1), "1000");
    }

    private static CardModel card(CardModelStatus status, YearMonth expiry, String balance) {
        return new CardModel(new UUID(0, 1), PAN, "400000", "IVAN IVANOV", expiry, status, "643",
                new BigDecimal("10000"), new BigDecimal("50000"), new BigDecimal(balance), "ISS001", TRANSMISSION_TIME);
    }

    private static AuthorizationRequest validRequest() {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", AMOUNT, "643", TRANSMISSION_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }

    private static RollbackRequest rollbackRequest() {
        return new RollbackRequest("600100001000", PAN, AMOUNT);
    }

    private static Stream<Arguments> lookupFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("down"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new PaymentRequiredException("blocked"), "CARD_BLOCKED", "05"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05")
        );
    }

    private static Stream<Arguments> reservationFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("down"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InsufficientFundsException("insufficient"), "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05")
        );
    }

    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("down"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new RollbackConflictException("duplicate"), "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05")
        );
    }
}
