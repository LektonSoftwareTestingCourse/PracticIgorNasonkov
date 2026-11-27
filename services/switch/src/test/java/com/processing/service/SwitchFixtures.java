package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.config.SwitchProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class SwitchFixtures {
    static final String PAN = "4000001234567890";
    static final String STAN = "000001";
    static final String RRN = "600100001000";
    static final String ISSUER = "ISS001";
    static final BigDecimal AMOUNT = new BigDecimal("1000");
    static final Instant TIME = Instant.parse("2026-01-31T23:59:59Z");

    private SwitchFixtures() {
    }

    static AuthorizationRequest request() {
        return request(PAN, "TERM0001", "MERCH0000000002");
    }

    static AuthorizationRequest request(String pan, String terminal, String merchant) {
        return new AuthorizationRequest("0100", STAN, pan, "000000", AMOUNT, "643", TIME,
                terminal, "POS", merchant, "5045", "ACQ002", null);
    }

    static AuthorizationResponse approved() {
        return new AuthorizationResponse("0110", STAN, RRN, "ABC123", "00", "APPROVED", null, 17);
    }

    static SwitchProperties properties() {
        return properties(List.of(1L, 2L));
    }

    static SwitchProperties properties(List<Long> backoff) {
        return new SwitchProperties("1.0.0", Map.of("400000", ISSUER, "500000", "ISS002"),
                "http://authorization", "http://logger", "http://merchant",
                new SwitchProperties.HttpProperties(100, 100, 100),
                new SwitchProperties.RetryProperties(3, backoff),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 2, 60000)));
    }
}
