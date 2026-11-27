package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.CircuitBreakerFactory;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec post;
    @Mock private RestClient.ResponseSpec responseSpec;

    private CircuitBreaker circuitBreaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(SwitchFixtures.properties());
        client = new AuthorizationClient(SwitchFixtures.properties(), restClient,
                Retry.of("test", RetryConfig.custom().maxAttempts(1).build()), circuitBreaker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnResponseWhenAuthorizationStatusIsRecognized(String status) {
        // Arrange
        postResponds();
        var response = new AuthorizationResponse("0110", SwitchFixtures.STAN, SwitchFixtures.RRN,
                null, "00", status, null, 1);
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(response);

        // Act
        AuthorizationResponse result = client.authorize(SwitchFixtures.request());

        // Assert
        assertThat(result).isSameAs(response);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "PENDING"})
    void shouldRejectWhenAuthorizationStatusIsInvalid(String status) {
        // Arrange
        postResponds();
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(new AuthorizationResponse(
                "0110", SwitchFixtures.STAN, null, null, "00", status, null, 1));

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(SwitchFixtures.request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("Invalid response from Authorization");
    }

    @Test
    void shouldRejectWhenAuthorizationBodyIsMissing() {
        // Arrange
        postResponds();
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(null);

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(SwitchFixtures.request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining(SwitchFixtures.STAN);
    }

    @Test
    void shouldUseUnderlyingCauseWhenAuthorizationThrows() {
        // Arrange
        when(restClient.post()).thenThrow(new IllegalStateException("request failed",
                new IllegalArgumentException("connection refused")));

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(SwitchFixtures.request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("connection refused");
    }

    @Test
    void shouldRejectImmediatelyWhenCircuitIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(SwitchFixtures.request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("Circuit breaker open");
    }

    @Test
    void shouldReturnApprovalWhenTransientFailureSucceedsOnRetry() {
        // Arrange
        postResponds();
        Retry retry = Retry.of("transient", RetryConfig.custom().maxAttempts(2)
                .waitDuration(Duration.ofMillis(1)).build());
        client = new AuthorizationClient(SwitchFixtures.properties(), restClient, retry, circuitBreaker);
        when(responseSpec.body(AuthorizationResponse.class))
                .thenThrow(new IllegalStateException("temporary")).thenReturn(SwitchFixtures.approved());

        // Act
        AuthorizationResponse result = client.authorize(SwitchFixtures.request());

        // Assert
        assertThat(result).isEqualTo(SwitchFixtures.approved());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnResponseWhenRollbackStatusIsRecognized(String status) {
        // Arrange
        postResponds();
        var rollback = new RollbackResponse(SwitchFixtures.RRN, "00", status, null, 1);
        when(responseSpec.body(RollbackResponse.class)).thenReturn(rollback);

        // Act
        RollbackResponse result = client.rollback(SwitchFixtures.request(), SwitchFixtures.RRN);

        // Assert
        assertThat(result).isSameAs(rollback);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "PENDING"})
    void shouldReturnNullWhenRollbackStatusIsInvalid(String status) {
        // Arrange
        postResponds();
        when(responseSpec.body(RollbackResponse.class))
                .thenReturn(new RollbackResponse(SwitchFixtures.RRN, "00", status, null, 1));

        // Act
        RollbackResponse result = client.rollback(SwitchFixtures.request(), SwitchFixtures.RRN);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackBodyIsMissing() {
        // Arrange
        postResponds();
        when(responseSpec.body(RollbackResponse.class)).thenReturn(null);

        // Act
        RollbackResponse result = client.rollback(SwitchFixtures.request(), SwitchFixtures.RRN);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackConnectionFails() {
        // Arrange
        when(restClient.post()).thenThrow(new IllegalStateException("connection refused"));

        // Act
        RollbackResponse result = client.rollback(SwitchFixtures.request(), SwitchFixtures.RRN);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldReportDownWhenHealthConnectionFails() {
        // Arrange
        when(restClient.get()).thenThrow(new IllegalStateException("connection refused"));

        // Act
        String status = client.checkHealth();

        // Assert
        assertThat(status).isEqualTo("down");
    }

    private void postResponds() {
        when(restClient.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(post);
        when(post.body(any(Object.class))).thenReturn(post);
        when(post.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    }
}
