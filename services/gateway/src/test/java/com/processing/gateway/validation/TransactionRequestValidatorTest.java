package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {
    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1000000000"})
    void shouldAcceptWhenAmountIsPositive(String amount) {
        // Arrange
        AuthorizationRequest request = validRequest().amount(new BigDecimal(amount)).build();

        // Act / Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenBodyIsMissing() {
        // Arrange / Act / Assert
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class).hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("missingFields")
    void shouldRejectWhenRequiredFieldIsMissing(String field, AuthorizationRequest request) {
        // Arrange / Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + field + "' is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0200", "010", "abcd"})
    void shouldRejectWhenMtiIsNotAuthorization(String mti) {
        // Arrange
        AuthorizationRequest request = validRequest().mti(mti).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class).hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000123456789", "40000012345678901", "400000123456789x"})
    void shouldRejectWhenPanIsNotSixteenDigits(String pan) {
        // Arrange
        AuthorizationRequest request = validRequest().pan(pan).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01", "-100"})
    void shouldRejectWhenAmountIsNotPositive(String amount) {
        // Arrange
        AuthorizationRequest request = validRequest().amount(new BigDecimal(amount)).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class).hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"64", "6430"})
    void shouldRejectWhenCurrencyCodeHasWrongLength(String currency) {
        // Arrange
        AuthorizationRequest request = validRequest().currencyCode(currency).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12345", "12a4"})
    void shouldRejectWhenMccIsNotFourDigits(String mcc) {
        // Arrange
        AuthorizationRequest request = validRequest().mcc(mcc).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class).hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    @Test
    void shouldAcceptWhenIssuerAndTerminalTypeAreAbsent() {
        // Arrange
        AuthorizationRequest request = validRequest().issuerId(null).terminalType(null).build();

        // Act / Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    private static Stream<Arguments> missingFields() {
        return Stream.concat(Stream.of(null, "", " ").flatMap(value -> Stream.of(
                missing("mti", builder -> builder.mti(value)),
                missing("stan", builder -> builder.stan(value)),
                missing("pan", builder -> builder.pan(value)),
                missing("processingCode", builder -> builder.processingCode(value)),
                missing("currencyCode", builder -> builder.currencyCode(value)),
                missing("terminalId", builder -> builder.terminalId(value)),
                missing("merchantId", builder -> builder.merchantId(value)),
                missing("mcc", builder -> builder.mcc(value)),
                missing("acquirerId", builder -> builder.acquirerId(value))
        )), Stream.of(
                missing("amount", builder -> builder.amount(null)),
                missing("transmissionDateTime", builder -> builder.transmissionDateTime(null))
        ));
    }

    private static Arguments missing(String field, Consumer<AuthorizationRequest.AuthorizationRequestBuilder> change) {
        var builder = validRequest();
        change.accept(builder);
        return Arguments.of(field, builder.build());
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder().mti("0100").stan("000001").pan("4000001234567890")
                .processingCode("000000").amount(new BigDecimal("1000")).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-01-31T23:59:59Z"))
                .terminalId("TERM0001").terminalType("POS").merchantId("MERCH0000000002")
                .mcc("5045").acquirerId("ACQ002");
    }
}
