package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    @Mock private RoutingService routingService;
    @Mock private AuthorizationClient authorizationClient;
    @Mock private AcquiringFeeClient acquiringFeeClient;
    @Mock private LoggerClient loggerClient;

    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldRouteAndPublishWhenAuthorizationSucceeds() {
        // Arrange
        AuthorizationRequest original = SwitchFixtures.request(SwitchFixtures.PAN, "T", "M");
        AuthorizationResponse approved = SwitchFixtures.approved();
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(SwitchFixtures.TIME, SwitchFixtures.STAN, SwitchFixtures.PAN,
                "T0000000", SwitchFixtures.AMOUNT)).thenReturn(new BigDecimal("15"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse result = service.route(original);

        // Assert
        assertThat(result).isSameAs(approved);
        var message = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(message.capture());
        assertThat(message.getValue()).satisfies(transaction -> {
            assertThat(transaction.id()).isNotNull();
            assertThat(transaction.stan()).isEqualTo(SwitchFixtures.STAN);
            assertThat(transaction.rrn()).isEqualTo(SwitchFixtures.RRN);
            assertThat(transaction.pan()).isEqualTo(SwitchFixtures.PAN);
            assertThat(transaction.issuerId()).isEqualTo(SwitchFixtures.ISSUER);
            assertThat(transaction.terminalId()).isEqualTo("T0000000");
            assertThat(transaction.merchantId()).isEqualTo("M00000000000000");
            assertThat(transaction.amount()).isEqualByComparingTo(SwitchFixtures.AMOUNT);
            assertThat(transaction.acquiringFee()).isEqualByComparingTo("15");
            assertThat(transaction.status()).isEqualTo(TransactionStatus.APPROVED);
            assertThat(transaction.authCode()).isEqualTo(approved.authCode());
            assertThat(transaction.processingTimeMs()).isEqualTo(17);
            assertThat(transaction.transmissionDateTime()).isEqualTo(SwitchFixtures.TIME);
        });
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"4", "40000", "9999991234567890"})
    void shouldDeclineWhenBinCannotBeRouted(String pan) {
        // Arrange
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(SwitchFixtures.request(pan, "TERM0001", "M"));

        // Assert
        assertThat(response).isEqualTo(AuthorizationResponse.unknownBin(SwitchFixtures.STAN));
        verify(authorizationClient, never()).authorize(any());
        verify(authorizationClient, never()).rollback(any(), any());
        verify(loggerClient).log(any(TransactionRequest.class));
    }

    @Test
    void shouldDeclineWhenAuthorizationIsUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER);
        when(authorizationClient.authorize(any())).thenThrow(new AuthorizationException(SwitchFixtures.STAN, 3, "down"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(SwitchFixtures.request());

        // Assert
        assertThat(response).isEqualTo(AuthorizationResponse.authUnavailable(SwitchFixtures.STAN));
        verify(loggerClient).log(any(TransactionRequest.class));
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest(name = "rollback={0}")
    @MethodSource("rollbackResponses")
    void shouldRollbackAndReturnSystemErrorWhenApprovedTransactionCannotBePublished(RollbackResponse rollback) {
        // Arrange
        AuthorizationRequest routed = AuthorizationRequestNormalizer.normalize(SwitchFixtures.request())
                .withIssuerId(SwitchFixtures.ISSUER);
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER);
        when(authorizationClient.authorize(routed)).thenReturn(SwitchFixtures.approved());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(routed, SwitchFixtures.RRN)).thenReturn(rollback);

        // Act
        AuthorizationResponse response = service.route(SwitchFixtures.request());

        // Assert
        assertThat(response).isEqualTo(AuthorizationResponse.systemError(SwitchFixtures.STAN));
        verify(authorizationClient).rollback(routed, SwitchFixtures.RRN);
    }

    @Test
    void shouldPreserveDeclineWithoutRollbackWhenPublicationFails() {
        // Arrange
        var declined = new AuthorizationResponse("0110", SwitchFixtures.STAN, null, null,
                "51", "DECLINED", "INSUFFICIENT_FUNDS", null);
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER);
        when(authorizationClient.authorize(any())).thenReturn(declined);
        when(loggerClient.log(any())).thenReturn(false);

        // Act
        AuthorizationResponse response = service.route(SwitchFixtures.request());

        // Assert
        assertThat(response).isSameAs(declined);
        var message = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(message.capture());
        assertThat(message.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(message.getValue().processingTimeMs()).isZero();
        assertThat(message.getValue().declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(message.getValue().acquiringFee()).isNull();
        verify(authorizationClient, never()).rollback(any(), any());
    }

    private static Stream<Arguments> rollbackResponses() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new RollbackResponse(SwitchFixtures.RRN, "00", "APPROVED", null, 1)),
                Arguments.of(new RollbackResponse(SwitchFixtures.RRN, "96", "DECLINED", "unavailable", 1))
        );
    }
}
