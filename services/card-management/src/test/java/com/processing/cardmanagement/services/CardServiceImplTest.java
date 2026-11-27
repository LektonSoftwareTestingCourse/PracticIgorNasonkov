package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.events.CardsAmountUpdatedEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String RRN = "600100001000";
    private static final BigDecimal AMOUNT = new BigDecimal("100");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private CardRepository cardRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationRollbackRepository rollbackRepository;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator panGenerator;
    @Mock private CardEventNotifier eventNotifier;
    @Mock private BinIssuerService binIssuerService;
    @Mock private RetryService retryService;

    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cardRepository, reservationRepository, rollbackRepository, settings, defaults,
                panGenerator, eventNotifier, binIssuerService, retryService, new TransactionRunnerImpl());
    }

    @Test
    void shouldReturnCardWhenPanExists() {
        // Arrange
        Card card = activeCard();
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        // Act
        Card result = service.getCard(PAN);

        // Assert
        assertThat(result).isSameAs(card);
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldThrowWhenCardDoesNotExist() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
        verifyNoInteractions(eventNotifier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "100", "1000"})
    void shouldSubtractReservationWhenBalanceCoversAmount(String value) {
        // Arrange
        BigDecimal amount = new BigDecimal(value);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card result = service.reserve(PAN, amount, RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo(new BigDecimal("1000").subtract(amount));
        var reservation = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(reservation.capture());
        assertThat(reservation.getValue().pan()).isEqualTo(PAN);
        assertThat(reservation.getValue().rrn()).isEqualTo(RRN);
        assertThat(reservation.getValue().reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.getValue().status()).isEqualTo(ReservationStatus.RESERVED);
        verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, amount));
    }

    @Test
    void shouldRejectReservationWhenBalanceIsInsufficient() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, new BigDecimal("1000.01"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(reservationRepository, eventNotifier);
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(
                activeCard().withData(status, new BigDecimal("10000"), new BigDecimal("50000"),
                        new BigDecimal("1000"))));

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(IllegalStateException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(reservationRepository, eventNotifier);
    }

    @Test
    void shouldRejectReservationWhenCardIsMissing() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(CardNotFoundException.class);
        verifyNoInteractions(reservationRepository, eventNotifier);
    }

    @Test
    void shouldRejectReservationWhenRrnAlreadyExistsForPan() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservationRepository, never()).save(any());
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRestoreBalanceWhenReservationIsRolledBack() {
        // Arrange
        Reservation reservation = reservation(ReservationStatus.RESERVED);
        Card reserved = activeCard().withReservation(reservation);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(reserved));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(rollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card result = service.rollback(PAN, AMOUNT, RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("1000");
        var saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(saved.getValue().id()).isEqualTo(reservation.id());
        verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, AMOUNT));
    }

    @Test
    void shouldRejectRollbackWhenReservationIsMissing() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN)).isInstanceOf(ReservationNotFoundException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    @Test
    void shouldRejectRollbackWhenReservationWasAlreadyRolledBack() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN))
                .thenReturn(Optional.of(reservation(ReservationStatus.ROLLED_BACK)));

        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN)).isInstanceOf(RollbackAlreadySatisfiedException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    @Test
    void shouldPreserveUnspecifiedValuesWhenPatchingStatus() {
        // Arrange
        Card card = activeCard();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card result = service.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        // Assert
        assertThat(result).isEqualTo(card.withData(CardStatus.BLOCKED, card.dailyLimit(),
                card.monthlyLimit(), card.availableBalance()));
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @Test
    void shouldUpdateLimitsAndBalanceWhenPatchSuppliesValues() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card result = service.patchCard(PAN, null, AMOUNT, new BigDecimal("200"), new BigDecimal("500"));

        // Assert
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(result.dailyLimit()).isEqualByComparingTo(AMOUNT);
        assertThat(result.monthlyLimit()).isEqualByComparingTo("200");
        assertThat(result.availableBalance()).isEqualByComparingTo("500");
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "100,-1", "101,100"})
    void shouldRejectPatchWhenLimitsAreInvalid(String daily, String monthly) {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

        // Act / Assert
        assertThatThrownBy(() -> service.patchCard(PAN, null, new BigDecimal(daily), new BigDecimal(monthly), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldSoftDeleteWhenCardExists() {
        // Arrange
        Card card = activeCard();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));

        // Act
        service.deleteCard(PAN);

        // Assert
        verify(cardRepository).update(card.deleted());
        verify(eventNotifier).notifyListeners(new CardServiceDeletionEvent(PAN));
    }

    @Test
    void shouldUseDefaultPaginationWhenLimitAndOffsetAreOmitted() {
        // Arrange
        when(defaults.pageLimit()).thenReturn(25);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(25, 0L, null, null, null, null, null)).thenReturn(List.of(activeCard()));

        // Act
        List<Card> result = service.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(result).containsExactly(activeCard());
    }

    @Test
    void shouldPassFiltersWhenPageLimitIsAtMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        when(cardRepository.findCards(100, 50L, CardStatus.ACTIVE, BIN, "ISS001", TIME, TIME))
                .thenReturn(List.of(activeCard()));

        // Act
        List<Card> result = service.getCards(100, 50L, CardStatus.ACTIVE, BIN, "ISS001", TIME, TIME);

        // Assert
        assertThat(result).containsExactly(activeCard());
    }

    @Test
    void shouldRejectPaginationWhenLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);

        // Act / Assert
        assertThatThrownBy(() -> service.getCards(101, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
        verifyNoInteractions(cardRepository);
    }

    @Test
    void shouldPublishCountWhenCountingCards() {
        // Arrange
        when(cardRepository.countAllCards()).thenReturn(42L);

        // Act
        long result = service.countAllCardsAndUpdate();

        // Assert
        assertThat(result).isEqualTo(42L);
        verify(eventNotifier).notifyListeners(new CardsAmountUpdatedEvent(42L));
    }

    @Test
    void shouldCreateActiveCardWhenIssuerAndPanAreAvailable() {
        // Arrange
        when(binIssuerService.getIssuerId(BIN)).thenReturn("ISS001");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxCardCreationRetries()).thenReturn(5);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(retryService.supply(eq(5), any())).thenAnswer(invocation ->
                invocation.<Supplier<Card>>getArgument(1).get());
        when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card result = service.createCard(BIN, "IVAN IVANOV", "643",
                new BigDecimal("10000"), new BigDecimal("50000"), new BigDecimal("1000"));

        // Assert
        assertThat(result.pan()).isEqualTo(PAN);
        assertThat(result.bin()).isEqualTo(BIN);
        assertThat(result.issuerId()).isEqualTo("ISS001");
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(result.availableBalance()).isEqualByComparingTo("1000");
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @Test
    void shouldRejectBulkUpdateWhenNoSelectorIsProvided() {
        // Arrange / Act / Assert
        assertThatThrownBy(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Either bin or pans must be provided");
        verifyNoInteractions(cardRepository);
    }

    @Test
    void shouldRejectBulkUpdateWhenBothSelectorsAreProvided() {
        // Arrange / Act / Assert
        assertThatThrownBy(() -> service.bulkUpdateStatus(List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Only one of bin or pans must be provided");
        verifyNoInteractions(cardRepository);
    }

    @Test
    void shouldUpdateMatchingCardsWhenSelectedByBins() {
        // Arrange
        when(cardRepository.findCardsByBinsForUpdate(List.of(BIN))).thenReturn(List.of(activeCard()));

        // Act
        int count = service.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

        // Assert
        assertThat(count).isEqualTo(1);
        verify(cardRepository).update(activeCard().withData(CardStatus.BLOCKED,
                new BigDecimal("10000"), new BigDecimal("50000"), new BigDecimal("1000")));
    }

    @Test
    void shouldUpdateMatchingCardsWhenSelectedByPans() {
        // Arrange
        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(activeCard()));

        // Act
        int count = service.bulkUpdateStatus(null, List.of(PAN), CardStatus.BLOCKED);

        // Assert
        assertThat(count).isEqualTo(1);
        verify(cardRepository).update(activeCard().withData(CardStatus.BLOCKED,
                new BigDecimal("10000"), new BigDecimal("50000"), new BigDecimal("1000")));
    }

    private static Card activeCard() {
        return new Card(new UUID(0, 1), PAN, BIN, "IVAN IVANOV", YearMonth.of(2029, 1), CardStatus.ACTIVE,
                "643", new BigDecimal("10000"), new BigDecimal("50000"), new BigDecimal("1000"), "ISS001", TIME);
    }

    private static Reservation reservation(ReservationStatus status) {
        return new Reservation(new UUID(0, 2), PAN, AMOUNT, RRN, status, TIME, TIME);
    }
}
