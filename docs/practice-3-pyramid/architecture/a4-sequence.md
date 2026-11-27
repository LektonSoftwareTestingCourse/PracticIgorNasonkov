# А4. Sequence: happy path, declined, reversal

## Чего не хватало и что добавлено

Исходная sequence-диаграмма в `docs/architecture.md` описывает happy path. Здесь добавлены отказ по BLOCKED, компенсация после ошибки публикации, граница HTTP/AMQP и независимая обработка сообщения Logger. Диаграмма показывает **текущую реализацию**, а не желаемую гарантию доставки.

```mermaid
sequenceDiagram
    actor Client as Терминал / мерчант
    participant GW as Gateway
    participant SW as Switch / RouteService
    participant AUTH as Authorization
    participant BIN as BIN Lookup
    participant CMS as Card-Management
    participant DB as PostgreSQL
    participant FEE as Merchant-Acquirer
    participant MQ as RabbitMQ
    participant LOG as Transaction Logger

    Client->>GW: sync HTTP POST /api/transactions, mti=0100
    Note over GW: Валидация: PAN, amount > 0, mti=0100
    GW->>SW: sync HTTP POST /api/internal/route
    Note over SW: RoutingService: BIN → issuerId
    SW->>AUTH: sync HTTP POST /api/internal/authorize
    AUTH->>CMS: sync HTTP GET /api/cards/{pan}
    CMS->>DB: sync JPA/JDBC: поиск карты
    DB-->>CMS: Card
    CMS-->>AUTH: sync HTTP CardModel
    AUTH->>BIN: sync HTTP GET /api/bin/{bin}
    BIN-->>AUTH: issuerId / ошибка → Optional.empty
    Note over AUTH: Результат BIN логируется, но request не изменяется

    alt declined: карта BLOCKED
        AUTH-->>SW: sync HTTP DECLINED / CARD_BLOCKED
        Note over AUTH,CMS: Нет reserve и обновления limit_usage
    else happy path / будущая компенсация
        Note over AUTH: ACTIVE → срок → баланс → лимиты
        AUTH->>DB: sync JPA/native SQL: upsertLimitUsage
        DB-->>AUTH: 1 строка обновлена
        AUTH->>DB: sync JDBC: блок RRN из rrn_seq
        AUTH->>CMS: sync HTTP POST /api/cards/{pan}/reserve
        CMS->>DB: sync JDBC: lock Card, reserve, balance, outbox
        DB-->>CMS: commit
        CMS-->>AUTH: sync HTTP 200, новый баланс
        AUTH-->>SW: sync HTTP APPROVED / 00, rrn, authCode
    end

    SW->>FEE: sync HTTP: запрос acquiring fee
    FEE-->>SW: комиссия / fallback клиента
    SW->>MQ: async AMQP convertAndSend: smp.transactions / transaction.log

    alt convertAndSend вернулся без AmqpException
        Note over SW,MQ: LoggerClient возвращает true, ожидания confirm нет
        par Ответ не ждёт сохранения в Logger
            SW-->>GW: sync HTTP: исходный APPROVED либо DECLINED
            GW-->>Client: sync HTTP: результат
        and Независимая доставка
            MQ-->>LOG: async AMQP: transaction-log
            LOG->>DB: sync JPA/JDBC: store по id
            Note over LOG: WebSocket broadcast только для новой записи
        end
    else AmqpException и исходный ответ APPROVED
        SW->>AUTH: sync HTTP POST /api/internal/rollback, pan + rrn + amount
        AUTH->>CMS: sync HTTP POST /api/cards/{pan}/rollback
        CMS->>DB: sync JDBC: lock reservation, ROLLED_BACK, возврат баланса, outbox
        CMS-->>AUTH: результат rollback
        AUTH-->>SW: APPROVED / DECLINED либо ошибка клиента
        Note over SW: Даже при неудаче rollback клиент получает systemError
        SW-->>GW: sync HTTP DECLINED / 96
        GW-->>Client: sync HTTP DECLINED / 96
    else AmqpException и исходный ответ DECLINED
        Note over SW: Компенсация не нужна, лог может быть потерян
        SW-->>GW: sync HTTP: исходный DECLINED
        GW-->>Client: sync HTTP: исходный DECLINED
    end
```

## Reversal 0400: требование и фактическая реализация

- В задании reversal обозначен как `mti=0400`. В текущем коде компенсация вызывается отдельным HTTP-запросом `POST /api/internal/rollback`; `RollbackRequest` содержит `rrn`, `pan`, `amount`, а не `mti`.
- В `AuthorizationRequest` есть helper `forReversal`, создающий `mti=0400`, но `RouteService` не использует его в рабочей ветке компенсации. Наличие helper не означает существование маршрута обработки reversal.
- Gateway принимает в транзакционном запросе **только `0100`**, поэтому рисовать работающий внешний маршрут `0400 → rollback` было бы неверно. `0400` остаётся пробелом реализации относительно задания.
- `publisher-confirm-type: correlated` включён, но `LoggerClient.log()` не создаёт `CorrelationData`, не ожидает подтверждения и не реализует дедлайн 2 секунды. Триггер показанной компенсации — синхронный `AmqpException`, **не** timeout подтверждения.
- После компенсации Switch не публикует отдельное событие `ROLLED_BACK`. `AuthServiceImpl.rollback()` также не уменьшает `limit_usage`. Нет распределённой транзакции между AUTH и CMS; отказ reserve после upsert требует отдельной проверки целостности.
- Публикация карточных outbox-событий планировщиком независима от HTTP-ответа. Её полная цепочка приведена в А2, А3 и А5.

## Подтверждение кодом

- [Gateway routes](../../../services/gateway/src/main/resources/application.yml), [TransactionRequestValidator.validate](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java).
- [RouteService.route / buildTransaction](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [AuthorizationClient.authorize / rollback](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java), [AcquiringFeeClient](../../../services/switch/src/main/java/com/processing/service/AcquiringFeeClient.java).
- [AuthServiceImpl.authorize / rollback](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [LimitUsageRepository.upsertLimitUsage](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java).
- [CardServiceImpl.reserve / rollback](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [Reservation](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java).
- [LoggerClient.log](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [Switch configuration](../../../services/switch/src/main/resources/application.yml), [RollbackRequest](../../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java).
- [TransactionLogListener](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [TransactionService.store](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java).
