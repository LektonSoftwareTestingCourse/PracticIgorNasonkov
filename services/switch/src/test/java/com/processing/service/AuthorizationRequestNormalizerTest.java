package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource({"T,T0000000", "TERM001,TERM0010", "TERM0001,TERM0001", "TERM00012,TERM0001"})
    void shouldPadOrTruncateWhenNormalizingTerminalId(String input, String expected) {
        // Arrange / Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void shouldPreserveWhenTerminalIdIsBlank(String input) {
        // Arrange / Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(input);
    }

    @ParameterizedTest
    @CsvSource({
            "M,M00000000000000",
            "12345678901234,123456789012340",
            "MERCH1234567890,MERCH1234567890",
            "MERCH01234567890,MERCH1234567890",
            "MERCH11234567890,MERCH1123456789",
            "OTHER01234567890,OTHER0123456789",
            "MERCH001234567890,MERCH0012345678"
    })
    void shouldNormalizeWhenMerchantIdHasDifferentLengths(String input, String expected) {
        // Arrange / Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void shouldPreserveWhenMerchantIdIsBlank(String input) {
        // Arrange / Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(input);
    }

    @Test
    void shouldPreserveTransactionFieldsWhenNormalizingIdentifiers() {
        // Arrange
        AuthorizationRequest original = SwitchFixtures.request(SwitchFixtures.PAN, "T", "M");

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(original);

        // Assert
        assertThat(normalized).isEqualTo(SwitchFixtures.request(SwitchFixtures.PAN, "T0000000", "M00000000000000"));
        assertThat(original.terminalId()).isEqualTo("T");
        assertThat(original.merchantId()).isEqualTo("M");
    }
}
