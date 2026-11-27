# Практика 3 — тест-кейсы и пирамида тестирования
## 1. Методика и данные

| Уровень | Граница теста | Реальные зависимости |
|---|---|---|
| unit | Метод / доменная модель / оркестратор с doubles | Нет HTTP-сервера, PostgreSQL и RabbitMQ; проверяем логику, аргументы, порядок вызовов |
| api | HTTP-контракт одного сервиса: validation, mapping, serialization | HTTP/web slice и stub/mock бизнес-слоя; не доказывает сохранение в БД |
| integration | Реальная связь сервиса с БД/брокером либо ограниченной цепочки сервисов | PostgreSQL, RabbitMQ и необходимые соседи; HTTP-вход не делает тест E2E автоматически |
| e2e | Публичный пользовательский сценарий целиком | Gateway, Switch, Authorization, CMS, Logger, БД/AMQP и симулятор/браузер по сценарию |

Выбирается самый низкий уровень, который доказывает **все** заявленные assertions. Моки БД не подтверждают SQL, lock, commit и отсутствие изменений в реальной БД. Старые кейсы распределяются с сохранением их HTTP-шагов и постусловий; «низкоуровневый» в практике 2 не равнозначен `unit`. Если проверка перенесена на чистый метод, это явно обозначено как отдельная новая проверка.

**Общие фикстуры новых кейсов:**

- Все суммы — целое число **копеек**, шаг границы — 1, без неоднозначного `0.01`. Базовая карта: ACTIVE, BIN `400000`, валидный сгенерированный PAN `P`, валюта `643`, dailyLimit `15000000`, monthlyLimit `300000000`, availableBalance `100000000`. Срок — месяц запроса + 12 месяцев. Данные старой практики не исправляются молча.
- Корректное тело транзакции: `mti=0100`, шестизначный STAN, `processingCode=000000`, `amount=1000`, `currencyCode=643`, текущий ISO instant в `transmissionDateTime`, `terminalId=TERM0001` (8), `merchantId=MERCH0000000001` (15), `terminalType=POS`, `mcc=5411`, `acquirerId=ACQ001`. Для прямого Authorization-входа обязательно `issuerId=ISS001`. Для Gateway issuerId назначает Switch.
- Каждый тест изолирует PAN, STAN, RRN, UUID сообщения, дату и начальные счётчики. Валидный RRN — 12 цифр, уникальный для теста; перед проверками SQL зафиксировать исходные строки. Восстановить контейнеры/fault-injection, удалить только собственные фикстуры после теста.
- Для временных границ задать `transmissionDateTime=2026-11-26T12:00:00Z`: текущий MMYY — **1126**, предыдущий — **1026**, следующий — **1226**. `0926` из старой практики на 26 ноября 2026 уже истёк. При переносе времени теста пересчитать месяцы либо зафиксировать дату запроса.
- Ожидание eventual consistency: polling каждые 100 ms с **тестовым** deadline 10 s, а не фиксированный sleep и не заявленный SLA. Для DLQ TTL использовать тестовое окно до 70 s, не читать сообщение до истечения, иначе измеряется не TTL. Изолировать очереди/БД или отключить посторонний трафик.
- Асинхронные карточные уведомления коррелировать по routing key и полям payload (для patch/reserve — PAN/RRN), а не только по общему count. Creation event содержит count, поэтому для него нужны изолированный поток и снимок baseline; не предполагать наличие PAN во всех событиях.

## 2. Часть (б): новые тест-кейсы

**24 новых кейса:** 8 unit, 4 api, 10 integration, 2 e2e. Позитивный/негативный вид и источник выведены из модели и контрактов; известные расхождения с заданием обозначены как **GAP**. Для GAP ожидаемый результат — целевое требование, а не утверждение о текущем прохождении. Приоритет новых кейсов высокий, кроме N-07 и N-12 (средний).

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N-01 | [ТЗ Switch: маршрутизация](../../tz/03-switch.md#2-маршрутизация-post-apiinternalroute) | unit | Позитивный | `SwitchProperties.binRouting`: 400000 → ISS001 | Вызвать `getIssuerIdByPan(P)` с BIN 400000 | Возвращён ISS001; HTTP/БД/AMQP не нужны | Класс эквивалентности: известный BIN |
| N-02 | [ТЗ Switch: маршрутизация](../../tz/03-switch.md#2-маршрутизация-post-apiinternalroute) | unit | Негативный | Таблица не содержит 499999 | Вызвать метод для PAN с BIN 499999 | `UnknownBinException`; не выполняются сетевые вызовы | Класс эквивалентности: неизвестный BIN |
| N-03 | [ТЗ Card Management: резервирование средств](../../tz/05-card-management.md#5-резервирование-средств-post-api-cardspanreserve) | unit | Позитивный, граничный | ACTIVE Card, B=1000 | Создать reserve amount=1000, затем `withReservation` | Новый баланс 0, исходный immutable Card остаётся с B=1000, PAN/RRN резерва сохранены | Граничное значение: amount=B |
| N-04 | [ТЗ Card Management: резервирование средств](../../tz/05-card-management.md#5-резервирование-средств-post-api-cardspanreserve) | unit | Негативный, граничный | ACTIVE Card, B=1000 | `startReservation(1001, rrn)` | `InsufficientFundsException`; исходный баланс 1000 | Граничное значение: B+1 копейка |
| N-05 | [ТЗ Authorization: reversal](../../tz/04-authorization.md#дополнительные-задания-бонусные-баллы) | unit | Негативный | Reservation уже ROLLED_BACK | Вызвать `startRollback` повторно | `RollbackAlreadySatisfiedException`, новый rollback не создаётся | Класс эквивалентности: повторная компенсация |
| N-06 | [ТЗ Authorization: алгоритм проверки](../../tz/04-authorization.md#2-авторизация-post-apiinternalauthorize) | unit | Негативный | CMS double отдаёт BLOCKED, истёкший срок, B=0; BIN double возвращает empty | Вызвать `authorize` с amount=1000 | CARD_BLOCKED/05; `upsertLimitUsage` и reserve не вызваны; никаких утверждений о реальной БД | Сценарий: конкурирующие причины отказа |
| N-07 | [ТЗ Card Management: Outbox → RabbitMQ](../../tz/05-card-management.md#асинхронная-публикация-событий-outbox-rabbitmq-вариант-c) | unit | Негативный, граничный | `maxRetryCount=3`; pending event retryCount=2; RabbitTemplate double бросает исключение, repository double захватывает save | `processSingleEvent(event)` | Сохранена копия FAILED, retryCount=3, lastError заполнен; PROCESSED не выставлен | Граничное значение: последняя попытка |
| N-08 | [ТЗ Switch: обработка ошибок](../../tz/03-switch.md#4-обработка-ошибок) | unit | Негативный | Authorization double → APPROVED с RRN; fee double; LoggerClient double → false | Вызвать `route`; захватить rollback | Ровно один rollback с исходными PAN/amount/RRN; ответ DECLINED/96. Это проверка ветки, не фактического возврата денег | Сценарий: публикация не удалась после APPROVED |
| N-09 | [ТЗ Gateway: приём транзакций](../../tz/02-gateway.md#2-приём-транзакций-post-apitransactions) | api | Негативный, граничный | Gateway web test, downstream HTTP stub; прочие поля валидны | POST /api/transactions с amount=0 | HTTP 400, сообщение о amount; downstream Switch не вызван | Контракт: amount > 0 на публичном входе |
| N-10 | [ТЗ Card Management: резервирование средств](../../tz/05-card-management.md#5-резервирование-средств-post-api-cardspanreserve) | api | Негативный, граничный | Web slice CMS, CardService mock, валидный PAN | POST reserve с amount=1000 и 11-значным RRN | HTTP 400; service.reserve не вызван | Контракт: RRN ровно 12 цифр |
| N-11 | [ТЗ Authorization: авторизация](../../tz/04-authorization.md#2-авторизация-post-apiinternalauthorize) | api | Негативный | Web slice, сервис возвращает CARD_BLOCKED/05; корректное тело включает issuerId | POST /api/internal/authorize | HTTP 403; JSON DECLINED, responseCode=05, declineReason=CARD_BLOCKED; rrn/authCode null | Контракт: HTTP-код отдельно от бизнес-кода |
| N-12 | [ТЗ Card Management: CRUD карт](../../tz/05-card-management.md#2-crud-карт) | api | Позитивный | Web slice, `deleteCard` service mock успешен | DELETE /api/cards/P | HTTP 204, пустое тело, вызван deleteCard(P); сохранение статуса здесь не проверяется | Контракт: DELETE без тела ответа |
| N-13 | [ТЗ Authorization: использованные лимиты](../../tz/04-authorization.md#4-отслеживание-использованных-лимитов) | integration | Граничный | Реальный PostgreSQL; dailyAmount=999, dailyLimit=1000, monthlyLimit достаточен | В транзакции upsert amount=1; после commit снова upsert amount=1 | Первый вернул 1 и сумма=1000; второй вернул 0; дневная и месячная суммы после отказа не увеличены | Состояние БД: SQL ON CONFLICT и граница лимита |
| N-14 | [ТЗ Card Management: резервирование средств](../../tz/05-card-management.md#5-резервирование-средств-post-api-cardspanreserve); [ТЗ Authorization: атомарность резервирования](../../tz/04-authorization.md#2-авторизация-post-apiinternalauthorize) | integration | Конкурентный, негативный | CMS с реальной БД, B=1000; два валидных различных RRN | Через барьер одновременно отправить два reserve по 1000; дождаться обоих ответов; прочитать БД | Один HTTP 200, второй 402 после lock; B=0, один резерв, нет отрицательного баланса. При lock timeout результат отдельно фиксируется как сбой теста, не «разрешённый flaky» | Сценарий: конкуренция за один баланс |
| N-15 | [ТЗ Card Management: CRUD и мягкое удаление](../../tz/05-card-management.md#2-crud-карт) | integration | Позитивный | CMS и PostgreSQL; BIN issuer существует | POST create → GET → PATCH status BLOCKED → DELETE → GET; проверить строку SQL | 201 → 200 → 200 → 204 → 404; физическая строка остаётся DELETED; несвязанные поля PATCH не изменены | Состояние БД: CRUD и SQLRestriction |
| N-16 | [ТЗ Transaction Logger: приём транзакций и идемпотентность](../../tz/08-transaction-logger.md#2-приём-транзакций-post-apiinternallog) | integration | Позитивный | Logger + PostgreSQL + RabbitMQ, пустой тестовый UUID; WebSocket observer | Опубликовать валидный TransactionRequest через smp.transactions/transaction.log; дождаться строки; повторить тот же id и payload | За deadline сохранена одна строка с исходными полями; повторный message обработан без второй строки/второго broadcast. Проверить завершение consumer по диагностике, не только мгновенный count | Сценарий: доставка и повтор; состояние БД |
| N-17 | [ТЗ Card Management: Outbox → RabbitMQ](../../tz/05-card-management.md#асинхронная-публикация-событий-outbox-rabbitmq-вариант-c) | integration | Позитивный, async | CMS + Notification + PostgreSQL + RabbitMQ; scheduler временно выключен; ACTIVE P | PATCH P в BLOCKED; проверить pending CardServicePatchEvent; включить processor; poll notification по routingKey/PAN | Outbox переходит PENDING → PROCESSED; независимо появляется card_notifications с key card.CardServicePatchEvent и PAN P; статус карты BLOCKED | Состояние БД: outbox и eventual consistency |
| N-18 | [ТЗ Switch: обработка ошибок](../../tz/03-switch.md#4-обработка-ошибок); [ТЗ Authorization: reversal](../../tz/04-authorization.md#дополнительные-задания-бонусные-баллы) | integration | Негативный | Switch + AUTH + CMS + PostgreSQL; fee/BIN stubs; изолированно заставить RabbitTemplate Switch бросить AmqpException до отправки | Подать валидный route, дождаться approve затем ошибки публикации; прочитать reserve/rollback/card | Клиент DECLINED/96; резерв ROLLED_BACK, одна rollback-запись, B восстановлен. Отдельно измерить limit_usage: текущий код его не компенсирует; GAP N-21 | Сценарий: HTTP-компенсация после AMQP failure |
| N-19 | **GAP: publisher-confirm ≤2s**, [ТЗ Switch, п. 3: Publisher Confirms](../../tz/03-switch.md#3-отправка-в-transaction-logger-основной-способ--rabbitmq) | integration | Негативный, целевой | Реальный брокер через управляемый AMQP proxy; пропускать publish, блокировать только broker confirms; reserve успешно | POST route; измерить время от завершения publish до компенсации; наблюдать rollback и ответ | Цель задания: после 2s без ack запускается rollback, ответ DECLINED/96. Допуск измерения таймера задать в harness; текущий LoggerClient не ждёт ack, поэтому соответствие не реализовано. Не объявлять отсутствие сообщений в Logger — publish мог быть принят | Контракт: timeout publisher-confirm и неопределённость доставки |
| N-20 | **GAP: consumer retry=3 + DLQ TTL=60s**, [ТЗ Logger: надёжность записи и приём из RabbitMQ](../../tz/08-transaction-logger.md); число попыток и TTL в `tz/` не заданы | integration | Негативный, целевой | Изолированные Logger/RabbitMQ; постоянная ошибка store на валидном DTO; инструментация числа вызовов consumer | Опубликовать одно сообщение; наблюдать попытки и DLQ без изъятия; затем проверить TTL | Цель: не более 3 обработок, затем DLX → transaction-log-dlq; через TTL сообщение исчезает, не возвращается в main queue. По x-max-delivery=3 и default listener settings выполнение не доказано; фиксировать фактическое requeue отдельно | Сценарий: poison message; контракт DLX/DLQ |
| N-21 | **GAP: целостность лимитов после rollback**, [ТЗ Authorization: reversal и снятие записи из limit_usage](../../tz/04-authorization.md#дополнительные-задания-бонусные-баллы) | integration | Негативный, целевой | AUTH + CMS + PostgreSQL; успешная авторизация A; сохранены начальные B и usage | Вызвать /api/internal/rollback для того же PAN/RRN/A; прочитать карты, reserve, limit_usage | Цель согласованной компенсации: баланс и дневное/месячное usage восстановлены ровно один раз. Текущий rollback восстанавливает CMS, но не меняет usage: документировать как риск бизнес-семантики, подтвердить требование с владельцем, не считать реализованным | Состояние БД: инварианты компенсации |
| N-22 | [ТЗ Card Management: Outbox → RabbitMQ](../../tz/05-card-management.md#асинхронная-публикация-событий-outbox--rabbitmq-вариант-c); [ТЗ Card Management: PATCH](../../tz/05-card-management.md#2-crud-карт) | integration | Негативный | CMS + реальный PostgreSQL, исходная карта ACTIVE, scheduler выключен; test failpoint после записи outbox, до commit внешней операции | PATCH status BLOCKED; в той же транзакции после save event бросить RuntimeException; проверить отдельным connection | Откатились и status, и новый outbox_event; карта ACTIVE, события для этого изменения нет. Не переносить вывод автоматически на createCard без общей обёртки | Состояние БД: атомарность локальной транзакции |
| N-23 | [ТЗ Gateway: приём транзакций](../../tz/02-gateway.md#2-приём-транзакций-post-apitransactions); [ТЗ Switch: маршрутизация](../../tz/03-switch.md#2-маршрутизация-post-apiinternalroute); [ТЗ Authorization: авторизация](../../tz/04-authorization.md#2-авторизация-post-apiinternalauthorize); [ТЗ Logger: приём транзакций](../../tz/08-transaction-logger.md#2-приём-транзакций-post-apiinternallog) | e2e | Позитивный | Полный основной Compose-контур без faults; валидная карта, изолированные STAN/merchantId; нет расхода лимитов | Терминальный HTTP-клиент → публичный Gateway POST /api/transactions; затем poll GET /api/transactions/search по уникальному merchantId и сопоставить STAN/RRN | HTTP 200 APPROVED/00, корректные RRN/authCode; B уменьшен ровно на A; за deadline Logger содержит одну APPROVED-запись с тем же RRN. Не требовать сохранения до ответа Gateway | Сценарий: пользовательский платёж + eventual consistency |
| N-24 | [ТЗ Card Management: CRUD и PATCH](../../tz/05-card-management.md#2-crud-карт); [ТЗ Gateway: приём транзакций](../../tz/02-gateway.md#2-приём-транзакций-post-apitransactions); [ТЗ Authorization: авторизация](../../tz/04-authorization.md#2-авторизация-post-apiinternalauthorize); [ТЗ Logger: приём транзакций](../../tz/08-transaction-logger.md#2-приём-транзакций-post-apiinternallog) | e2e | Негативный | Полный контур; ACTIVE карта, уникальный merchantId, снимок B/usage | Через публичный /api/cards/P PATCH BLOCKED; сразу отправить платёж через Gateway; poll Logger | Платёж HTTP 200 с DECLINED/CARD_BLOCKED/05 (Switch оборачивает бизнес-ответ); B/usage неизменны, нового reserve нет; запись DECLINED появляется eventual. Возможный stale Gateway cache измерить отдельно, не маскировать повтором платежа | Сценарий: изменение состояния карты и сквозной отказ |

### Почему выбран именно этот уровень

- N-01…N-08 доказывают логику без инфраструктуры. N-08 не заменяет N-18: успешный вызов mock.rollback не доказывает реального возврата денег.
- N-09…N-12 проверяют транспортный контракт, который теряется при прямом вызове метода. N-12 не заменяет проверку мягкого удаления с БД в N-15.
- N-13…N-22 требуют commit/SQL, брокера, redelivery или нескольких связанных сервисов. Они не требуют Dashboard и всех симуляторов; расширение их до E2E удорожило бы локализацию сбоя без дополнительных assertions.
- N-23/N-24 оставлены сквозными: это минимальные пользовательские проверки нормального платежа и отказа после изменения карты. Fault-injection с подтверждениями не дублируется отдельным E2E.

## 3. Часть (в): распределение всех кейсов практики 2

Источник: [test-design.md](../practice-2/test-design.md), разделы 5.1–5.7. Учтены **143 уникальных ID**, включая все 60 pairwise-строк и 6 ручных дополнений; повторные упоминания ID в тексте не считаются тестами. Для каждой строки указаны объект проверки и минимально достаточная граница. Классификация — план реализации тестов, а не утверждение о наличии 143 исполняемых автотестов.

### 3.1. Authorization — классы эквивалентности

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-AUTH-01 | Успешная авторизация | HTTP-ответ, реальный reserve, баланс и limit_usage | integration | Нужны AUTH/CMS и PostgreSQL; один mock не подтверждает постусловия |
| TC-AUTH-02 | INACTIVE | Отказ без reserve и изменения usage | integration | Сохраняем HTTP-шаг и проверки состояния CMS/БД |
| TC-AUTH-03 | BLOCKED | CARD_BLOCKED и неизменный баланс | integration | Требуются HTTP-вход и наблюдение сохранённого баланса |
| TC-AUTH-04 | EXPIRED status | Код 54 до проверки даты, без списания | integration | Проверяются ответ сервиса и отсутствие записи reserve |
| TC-AUTH-05 | PAN не найден | Преобразование CMS 404 в отказ AUTH и отсутствие reserve | integration | Проверяется реальная связь AUTH–CMS, не только форма JSON |
| TC-AUTH-06 | DELETED | CMS скрывает удалённую карту, AUTH отклоняет | integration | SQLRestriction и межсервисный вызов требуют БД |
| TC-AUTH-07 | Истёкший срок | Отказ по дате при ACTIVE, баланс неизменен | integration | HTTP-сценарий с сохранённой картой и постусловием |
| TC-AUTH-08 | Неверный формат expiry | Реакция на некорректное поле CMS и неизменный баланс | integration | Это контракт чтения карточных данных и persistence mapping, не чистое сравнение дат |
| TC-AUTH-09 | Дневной лимит | Отказ по лимиту, отсутствие reserve и изменений usage | integration | Условие native SQL проверяется с PostgreSQL |
| TC-AUTH-10 | Месячный лимит | Накопленное usage + amount превышает лимит | integration | Нужны реальные сохранённые месячные счётчики |
| TC-AUTH-11 | Баланс превышен | Код 51 и неизменный баланс | integration | HTTP-вход плюс чтение реального состояния; исправить фикстуру лимитов перед выполнением |
| TC-AUTH-12 | Нулевая сумма | Валидация прямого AUTH-входа и бизнес-код | api | Нулевая сумма — контракт одного endpoint; БД заменяется double, отдельный публичный контракт N-09 |
| TC-AUTH-13 | Отрицательная сумма | HTTP/DTO-валидация и формат отказа | api | Нужен web slice, а не весь платёжный контур |
| TC-AUTH-14 | CMS недоступен | HTTP connection failure после остановки CMS | integration | Оригинальный шаг останавливает реальную зависимость; mock-исключение было бы другим тестом |
| TC-AUTH-15 | BIN недоступен | Продолжение авторизации и реальный reserve при lookup failure | integration | AUTH, CMS, SQL и HTTP failure; полный Gateway/UI не нужен |
| TC-AUTH-16 | POS/ATM/ECOM | Три успешных HTTP-запроса и суммарное уменьшение B на 3000 | integration | Сохраняем исходное постусловие, не подменяем его unit-проверкой DTO |

### 3.2. Authorization — граничные значения

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-AUTH-BV-01 | Daily L−1 | Одобрение и сохранённый расход L−1 | integration | Native SQL + reserve + commit |
| TC-AUTH-BV-02 | Daily L | Включительность границы и списание L | integration | Проверяем реальный лимит, а не результат mock SQL |
| TC-AUTH-BV-03 | Daily L+1 | Отказ и неизменность счётчиков | integration | Нужна проверка состояния PostgreSQL |
| TC-AUTH-BV-04 | Monthly M−1 | Одобрение и monthly_amount=M−1 | integration | Зависимость от накопления SQL usage |
| TC-AUTH-BV-05 | Monthly M | Одобрение на границе месяца и usage=M | integration | Реальные SQL-предикаты и commit |
| TC-AUTH-BV-06 | Monthly M+1 | Отказ без списания | integration | Проверка persisted состояния при отказе |
| TC-AUTH-BV-07 | Balance B−1 | После reserve остаток 1 | integration | Проверяется сохранённый CMS баланс |
| TC-AUTH-BV-08 | Balance B | После reserve остаток 0 | integration | HTTP + реальное резервирование |
| TC-AUTH-BV-09 | Balance B+1 | Отказ 51, B не изменяется | integration | Проверка ответа и состояния зависимости |
| TC-AUTH-BV-10 | Прошлый месяц | Отказ 54 и отсутствие списания | integration | Исходный HTTP-кейс включает постусловие CMS |
| TC-AUTH-BV-11 | Текущий месяц | Карта действует до конца месяца, reserve проходит | integration | Нужны дата запроса и сохранённый результат списания |
| TC-AUTH-BV-12 | Следующий месяц | Одобрение будущего expiry | integration | Исходный кейс проверяет реальный баланс после запроса |
| TC-AUTH-BV-13 | Daily usage + A=L+1 | Отказ при уже накопленном usage | integration | Реальный ON CONFLICT и неизменные счётчики |
| TC-AUTH-BV-14 | Daily usage + A=L | Исчерпание остатка лимита | integration | SQL обновляет существующую строку ровно до L |
| TC-AUTH-BV-15 | Amount=1 | Минимальный reserve без потери единицы | integration | Подтверждается сохранённый баланс, не только арифметика |
| TC-AUTH-BV-16 | RRN/authCode format | Формат полей после успешной HTTP-авторизации и списание | integration | Исходное постусловие — реальный баланс; RRN получает блок из БД |

### 3.3. Authorization — все строки pairwise

В названии указана строка набора, полный вектор и ожидаемый результат остаются в исходном документе. Все эти кейсы имеют HTTP-шаг и постусловие о балансе/usage; поэтому для **исходного** набора выбран integration, даже если отдельное правило можно дополнительно проверить unit-тестом.

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-AUTH-PW-01 | AUTH pairwise 01 | APPROVED при below/below/below, B→1 | integration | HTTP и сохранённый reserve/usage |
| TC-AUTH-PW-02 | AUTH pairwise 02 | Равные лимиты/баланс, текущий месяц, B→0 | integration | Границы SQL и commit CMS |
| TC-AUTH-PW-03 | AUTH pairwise 03 | Истёкший expiry раньше превышения лимитов | integration | Отказ по HTTP и отсутствие списания в CMS |
| TC-AUTH-PW-04 | AUTH pairwise 04 | INACTIVE при остальных допустимых значениях | integration | HTTP + неизменный сохранённый баланс |
| TC-AUTH-PW-05 | AUTH pairwise 05 | Баланс ниже amount при равных лимитах | integration | Состояние карты и отказ без reserve |
| TC-AUTH-PW-06 | AUTH pairwise 06 | BLOCKED вместе с истёкшим сроком | integration | Проверяем исходный HTTP-сценарий и постусловие |
| TC-AUTH-PW-07 | AUTH pairwise 07 | Одновременное превышение баланса и лимитов | integration | Проверяем фактический приоритет и отсутствие списания; старый oracle требует сверки |
| TC-AUTH-PW-08 | AUTH pairwise 08 | Превышение monthly при допустимых daily/B | integration | Реальный SQL monthly predicate |
| TC-AUTH-PW-09 | AUTH pairwise 09 | Истёкший срок при равных лимитах | integration | Ответ HTTP и неизменность CMS |
| TC-AUTH-PW-10 | AUTH pairwise 10 | Статус EXPIRED, POS/electronics | integration | Карта из CMS, отсутствие reserve |
| TC-AUTH-PW-11 | AUTH pairwise 11 | Daily превышен, B равен amount | integration | SQL отказ и отсутствие списания |
| TC-AUTH-PW-12 | AUTH pairwise 12 | Monthly/B равны amount, текущий месяц | integration | Commit usage и B→0 |
| TC-AUTH-PW-13 | AUTH pairwise 13 | Balance превышен, daily равен amount | integration | Реальная связь AUTH–CMS |
| TC-AUTH-PW-14 | AUTH pairwise 14 | Истёкший expiry при daily ниже amount | integration | Порядок отказа и сохранённый баланс |
| TC-AUTH-PW-15 | AUTH pairwise 15 | Monthly ниже amount, B равен amount | integration | Native SQL и отказ reserve |
| TC-AUTH-PW-16 | AUTH pairwise 16 | BLOCKED, ATM/grocery | integration | HTTP и неизменность CMS |
| TC-AUTH-PW-17 | AUTH pairwise 17 | Monthly и баланс ниже amount | integration | Фактический порядок проверок; oracle старой строки требует сверки |
| TC-AUTH-PW-18 | AUTH pairwise 18 | INACTIVE, POS/grocery | integration | Сохранённое состояние карты не изменено |
| TC-AUTH-PW-19 | AUTH pairwise 19 | INACTIVE вместе с истёкшим expiry | integration | Исходный HTTP и отсутствие reserve |
| TC-AUTH-PW-20 | AUTH pairwise 20 | BLOCKED, POS/electronics | integration | Ответ сервиса плюс постусловие о балансе |
| TC-AUTH-PW-21 | AUTH pairwise 21 | EXPIRED, POS/electronics | integration | Отказ по состоянию реальной карты |
| TC-AUTH-PW-22 | AUTH pairwise 22 | EXPIRED, ECOM/grocery | integration | HTTP + БД-постусловие |
| TC-AUTH-PW-23 | AUTH pairwise 23 | Daily=amount, остальные достаточны | integration | SQL граница и реальный reserve, B→1 |
| TC-AUTH-PW-24 | AUTH pairwise 24 | INACTIVE, POS/electronics | integration | Наблюдение отсутствия списания |
| TC-AUTH-PW-25 | AUTH pairwise 25 | BLOCKED, POS/travel | integration | HTTP-отказ и неизменный баланс |
| TC-AUTH-PW-26 | AUTH pairwise 26 | EXPIRED, POS/travel | integration | Сохраняем самостоятельный ID исходного набора и его постусловие |

### 3.4. Card-Management — классы эквивалентности

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-CM-01 | Create valid | Создание карты и полей в БД | integration | Нужен commit, генерация PAN и BIN repository |
| TC-CM-02 | Unknown BIN | Поиск отсутствующего BIN issuer и отсутствие карты | integration | Валидный формат BIN не заменяет проверку DB lookup |
| TC-CM-03 | BIN длина 5 | HTTP validation до создания | api | Web slice и verifyNoInteractions service достаточно |
| TC-CM-04 | Пустое имя | Bean validation и HTTP 400 | api | Контракт входа; persistence не вызывается |
| TC-CM-05 | Currency 999 | Разрешённость валюты по контракту | api | Проверка HTTP/DTO; текущее отсутствие whitelist фиксируется отдельно |
| TC-CM-06 | Daily=0 | Контракт допустимости нулевого лимита | api | Web slice; исходный expected 400 противоречит BV-05 |
| TC-CM-07 | Daily<0 | HTTP-отказ по отрицательному лимиту | api | Валидация тела без БД |
| TC-CM-08 | InitialBalance<0 | Контракт на отрицательный initialBalance | api | Ожидаемая validation до бизнес-слоя; реальное отсутствие аннотации — GAP |
| TC-CM-09 | PAN collision | Реальная уникальность PAN при create | integration | Нужен unique constraint; generator можно заменить deterministic double |
| TC-CM-10 | GET found | Схема/поля ответа найденной карты | api | Контракт ответа проверяем с CardService double; реальный поиск проверяется отдельно |
| TC-CM-11 | GET absent | Преобразование CardNotFoundException в 404 | api | Handler + web slice; не заявлен отдельный SQL assertion |
| TC-CM-12 | GET deleted | Скрытие DELETED через ORM | integration | Mock not-found не докажет SQLRestriction |
| TC-CM-13 | Pagination | Реальные total=120 и page length=50 | integration | Нужен набор строк и SQL pagination/count |
| TC-CM-14 | Status filter | Только BLOCKED из смешанного набора | integration | Проверяется DB query, не только query-param binding |
| TC-CM-15 | UNKNOWN status filter | Enum binding и HTTP 400 | api | Для некорректного enum БД не нужна |
| TC-CM-16 | BIN filter | Реальная фильтрация по BIN | integration | Нужен неоднородный DB dataset |
| TC-CM-17 | List excludes DELETED | Исключение мягко удалённых строк | integration | Проверяем ORM restriction на реальном SQL |
| TC-CM-18 | PATCH partial | Изменение status/daily и сохранность прочих полей | integration | Commit и сравнение строк до/после |
| TC-CM-19 | PATCH absent | HTTP 404 при отсутствии ресурса | api | Сервисный double бросает доменное исключение; DB lookup покрыт integration |
| TC-CM-20 | PATCH UNKNOWN status | JSON enum validation до изменения карты | api | Web slice проверяет 400 и отсутствие вызова сервиса |
| TC-CM-21 | DELETE soft | DELETED в БД, последующий GET 404 | integration | Проверяется persistence и исключение строки; HTTP oracle требует 204 |
| TC-CM-22 | DELETE twice | Повторное удаление не меняет БД | integration | Два запроса к реальному состоянию |
| TC-CM-23 | Reserve sufficient | Уменьшение B на 150000 | integration | Commit reserve и баланс |
| TC-CM-24 | Reserve inactive | Отказ без изменения сохранённого баланса | integration | Текущий handler выдаёт 409, не исходный 422; уровень от этого не меняется |
| TC-CM-25 | Generate 100 | 100 новых карт, BIN distribution, PAN | integration | Нужны массовое сохранение и подсчёт строк |
| TC-CM-26 | Generate zero | Однозначный контракт count=0 и отсутствие создания | api | Web slice и service invocation; исходное «400 либо 200» нужно уточнить |
| TC-CM-27 | Luhn validity | Алгоритм контрольной суммы | unit | PAN из предыдущего теста заменить самостоятельной фиксированной выборкой; HTTP не является предметом этого кейса |
| TC-CM-28 | Non-numeric PAN | Validation path PAN | api | Web slice без БД |

### 3.5. Card-Management — граничные значения

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-CM-BV-01 | PAN 15 | Отклонение короткого path PAN | api | Validation на HTTP-входе |
| TC-CM-BV-02 | PAN 16 | Допуск формата и схема Card ответа | api | Web slice с найденной картой от double |
| TC-CM-BV-03 | PAN 17 | Отклонение длинного PAN | api | Контракт длины, без persistence |
| TC-CM-BV-04 | Invalid Luhn absent | 404 для отсутствующего PAN из 16 цифр | api | Проверяется mapping not-found; текущий PanValidator не проверяет Луна |
| TC-CM-BV-05 | Daily=0 | Успешное создание с нулевым лимитом | integration | Исходное постусловие требует сохранённой карты |
| TC-CM-BV-06 | Daily=−1 | Validation отрицательного лимита | api | Запрос отвергается до сохранения |
| TC-CM-BV-07 | Reserve B−1 | Сохранённый остаток 1 | integration | Real reserve и commit |
| TC-CM-BV-08 | Reserve B | Сохранённый остаток 0 | integration | Persistence и отсутствие off-by-one |
| TC-CM-BV-09 | Reserve B+1 | Отказ без изменения баланса | integration | Реальные данные карты; HTTP-код в старом oracle требует сверки |
| TC-CM-BV-10 | Reserve 0 | HTTP 400 по Positive | api | Web slice; добавить валидный RRN, иначе тест проверит другое поле |
| TC-CM-BV-11 | Reserve −1 | HTTP 400 по отрицательной сумме | api | DTO validation, с валидным RRN |
| TC-CM-BV-12 | Name 255 | Создание на границе длины | integration | Постусловие о созданной карте и ограничение DB column |
| TC-CM-BV-13 | Name 256 | Отказ по максимальной длине | integration | В текущем DTO нет Size(max=255); DB varchar может быть реальной границей |
| TC-CM-BV-14 | Offset N−1 | Последняя строка набора N=120 | integration | SQL offset и реальный dataset |
| TC-CM-BV-15 | Offset N | Пустая страница после последней строки | integration | SQL pagination |
| TC-CM-BV-16 | Limit −1 | HTTP validation limit | api | Query-param validation без БД |
| TC-CM-BV-17 | Limit 0 | Контракт limit=0 | api | Текущий Positive отвергает 0; расхождение не превращается в E2E |

### 3.6. Card-Management — все строки pairwise

Валидационные строки — api с business double. Строки, чья суть — удалённость/существование/сохранение карты, — integration. Параметры, которых нет в DTO, нельзя считать проверенными только из-за общего HTTP 400. Во всех reserve-строках при реализации добавить корректный RRN, если он не является предметом негативной проверки.

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-CM-PW-01 | CMS pairwise 01 | Create valid, последующий GET созданного | integration | Проверяется сохранение карты |
| TC-CM-PW-02 | CMS pairwise 02 | GET отсутствующего/удалённого PAN | integration | Удалённость — persisted состояние, не формат |
| TC-CM-PW-03 | CMS pairwise 03 | Короткий PAN в PATCH | api | Входной контракт |
| TC-CM-PW-04 | CMS pairwise 04 | Non-numeric PAN в reserve | api | Валидация path |
| TC-CM-PW-05 | CMS pairwise 05 | Negative daily и short currency в PATCH | api | Контракт тела; currency отсутствует в текущем PatchCardRequest |
| TC-CM-PW-06 | CMS pairwise 06 | Короткий PAN при reserve | api | Ошибка до поиска/баланса |
| TC-CM-PW-07 | CMS pairwise 07 | Amount=0 прежде поиска карты | api | Web validation на валидном PAN и RRN |
| TC-CM-PW-08 | CMS pairwise 08 | Non-numeric PAN в PATCH | api | Path validation |
| TC-CM-PW-09 | CMS pairwise 09 | Currency unknown / daily negative | api | Проверяем контракт тела, не DB status |
| TC-CM-PW-10 | CMS pairwise 10 | Short currency в PATCH | api | Целевой контракт; такого поля DTO сейчас нет |
| TC-CM-PW-11 | CMS pairwise 11 | PAN неверной длины | api | Web slice до business call |
| TC-CM-PW-12 | CMS pairwise 12 | Non-numeric PAN и unknown currency | api | Наблюдаем отказ входного контракта, не делаем вывод о покрытии валюты |
| TC-CM-PW-13 | CMS pairwise 13 | Negative dailyLimit | api | DTO validation |
| TC-CM-PW-14 | CMS pairwise 14 | Non-numeric PAN при short currency | api | Формат пути |
| TC-CM-PW-15 | CMS pairwise 15 | Reserve INACTIVE при недостатке средств | integration | Реальное состояние карты, неизменный B; текущий отказ по статусу 409 |
| TC-CM-PW-16 | CMS pairwise 16 | Negative amount прежде отсутствующего PAN | api | Validation тела |
| TC-CM-PW-17 | CMS pairwise 17 | GET PAN длина 15 | api | Path validation |
| TC-CM-PW-18 | CMS pairwise 18 | Create unknown currency и daily=0 | api | Контракт create; whitelist валют не реализован |
| TC-CM-PW-19 | CMS pairwise 19 | GET non-numeric PAN | api | Формат входа |
| TC-CM-PW-20 | CMS pairwise 20 | PATCH отсутствующей карты | integration | Исходное постусловие о неизменённой карте/состоянии |
| TC-CM-PW-21 | CMS pairwise 21 | Короткий PAN и некорректное тело | api | HTTP validation; порядок нескольких ошибок отдельно не гарантируем |
| TC-CM-PW-22 | CMS pairwise 22 | Reserve удалённой карты | integration | SQLRestriction и неизменный баланс |
| TC-CM-PW-23 | CMS pairwise 23 | Non-numeric PAN, negative daily | api | Входная валидация |
| TC-CM-PW-24 | CMS pairwise 24 | GET BLOCKED существующей карты | integration | Доказывает, что не-ACTIVE статус не скрывает ресурс в БД |
| TC-CM-PW-25 | CMS pairwise 25 | Reserve отсутствующего PAN | integration | Реальный поиск и отсутствие списания |
| TC-CM-PW-26 | CMS pairwise 26 | Not-found прежде status/balance | integration | Нужна реальная граница поиска и отсутствие reserve |
| TC-CM-PW-27 | CMS pairwise 27 | Короткий PAN и amount=0 | api | Web slice, без БД |
| TC-CM-PW-28 | CMS pairwise 28 | Короткий PAN при допустимой сумме | api | Path validation |
| TC-CM-PW-29 | CMS pairwise 29 | Non-numeric PAN и negative amount | api | Контракт валидации нескольких полей |
| TC-CM-PW-30 | CMS pairwise 30 | Non-numeric PAN при превышенном балансе | api | Входной отказ до business lookup |
| TC-CM-PW-31 | CMS pairwise 31 | Create short currency и negative daily | api | DTO validation |
| TC-CM-PW-32 | CMS pairwise 32 | PATCH unknown currency, daily=0 | api | Целевой контракт PATCH; текущий DTO currency не содержит |
| TC-CM-PW-33 | CMS pairwise 33 | PATCH unknown currency | api | Контракт тела; старое ожидание требует пересмотра |
| TC-CM-PW-34 | CMS pairwise 34 | PATCH short currency | api | Контракт тела, не состояние EXPIRED |

### 3.7. Ручные дополнения

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-AUTH-MAN-01 | Статус раньше expiry/B | CARD_BLOCKED при трёх причинах, баланс неизменен | integration | Исходный HTTP-шаг с CMS-постусловием; чистый эквивалент отдельно N-06 |
| TC-AUTH-MAN-02 | Expiry раньше лимитов/B | Код 54 и отсутствие списания | integration | Сохраняем исходную границу HTTP + состояние карты |
| TC-AUTH-MAN-03 | Race reserve | Конкурентные авторизации, B не отрицательный | integration | Нужны реальные locks/SQL; исходные суммы 1 и 2 при B=1 не создают равноправной гонки, см. N-14 |
| TC-AUTH-MAN-04 | RRN uniqueness | Два успешных запроса, разные RRN и записи | integration | RRN зависит от PostgreSQL sequence и состояния генератора |
| TC-CM-MAN-01 | Duplicate PAN | Уникальность строки при повторной генерации PAN | integration | Проверяем unique constraint и retry, а не только exception mapping |
| TC-CM-MAN-02 | Full reserve current expiry | Реальный reserve до B=0 в текущем месяце | integration | HTTP/БД; проверка expiry в CMS не реализована и этот позитивный кейс её сам по себе не доказывает |

## 4. Расхождения и готовность к автоматизации

Классификация не легализует неверный oracle. Прежде чем автоматизировать старые кейсы, необходимо согласовать следующие расхождения; исходные файлы практики 2 оставлены без изменений.

| Область | Что обнаружено в коде / старом документе | Следствие для теста |
|---|---|---|
| Прямой AUTH HTTP | [AuthControllerImpl](../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java) возвращает 403/404/422/503 в зависимости от причины; Switch route возвращает HTTP 200 | Не путать HTTP публичного входа с внутренним; старое «HTTP 200 при любом отказе AUTH» неверно для реализации |
| Приоритет отказа | [AuthServiceImpl](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java): status → expiry → balance → limits | Старые PW-07/PW-17 с одновременным недостатком B и превышением лимитов ожидают 61, но по коду получают 51 |
| CMS unavailable | [DeclineOutcome](../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java): SERVICE_UNAVAILABLE/96 | Не переносить старое ISSUER_TIMEOUT/05 без проверки границы; Switch transport failure — отдельная ветка |
| Формат прямого AUTH | [AuthorizationRequest](../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java) требует issuerId, terminalId длины 8, merchantId длины 15 | В исходной AUTH-фикстуре terminalId длины 7 и нет issuerId; запрос может не дойти до проверяемого правила |
| Amount=0 | DTO прямого AUTH имеет PositiveOrZero; Gateway проверяет строго >0; reserve DTO Positive | Это разные контракты; ноль нельзя считать эквивалентно обработанным на всех входах |
| Expiry fixtures | На 26.11.2026 0926 уже в прошлом; AUTH сравнивает с transmissionDateTime | Для «текущего месяца» использовать 1126 при ноябрьской дате либо фиксировать исходную сентябрьскую дату |
| CMS delete/reserve | [CardController](../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java), [GlobalExceptionHandler](../../services/card-management/src/main/java/com/processing/cardmanagement/configuration/GlobalExceptionHandler.java): DELETE 204, недостаток B 402, не-ACTIVE IllegalState 409 | Старые 200/409/422 соответственно — не текущая реализация |
| Currency и initialBalance | [CreateCardRequest](../../services/common/src/main/java/com/processing/common/dto/cardmanagement/CreateCardRequest.java): digits/length валюты без whitelist 643; initialBalance без NotNegative | TC-CM-05/08 и некоторые PW-строки могут не получить ожидаемый validation failure; проверять как GAP, не объявлять PASS |
| PATCH currency | [PatchCardRequest](../../services/common/src/main/java/com/processing/common/dto/cardmanagement/PatchCardRequest.java) не содержит currencyCode | Нельзя доказывать проверку валюты ответом PATCH, вызванным другим невалидным полем |
| Daily=0 | Create DTO NotNegative; TC-CM-06 ожидает 400, TC-CM-BV-05 — 201 | В старом наборе внутреннее противоречие; модель допускает ноль |
| Имя и limit=0 | У create имени нет Size(max=255), [CardEntity](../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardEntity.java) использует строковый column; list limit помечен Positive | Граница имени может проявляться в БД; limit=0 возвращает validation error, не гарантированный пустой список |
| RRN в reserve | [ReserveRequest](../../services/common/src/main/java/com/processing/common/dto/cardmanagement/ReserveRequest.java) требует непустой корректный RRN | В ряде старых BV/PW тел RRN отсутствует; сначала исправить fixture, иначе тестирует не amount/PAN |
| PAN collision | [CardServiceImpl](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java) повторяет генерацию через RetryService | Duplicate PAN не обязательно сразу даёт HTTP 409; проверить retry exhaustion и handler, не исключать кейс из подсчёта |
| Reserve expiry | [Card.startReservation](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java) проверяет ACTIVE и B, но не expiryDate | Успех с текущим expiry не доказывает отказ с истёкшим; проверка срока реализована в AUTH |
| Confirm / retry / TTL | [А5](architecture/a5-rabbitmq.md) | 2s ack wait не реализован; x-max-delivery сам по себе не доказывает число обработок; TTL задан на DLQ |
| Reversal / state | [А4](architecture/a4-sequence.md), [А6](architecture/a6-states.md) | Работающий rollback HTTP нельзя подменять несуществующим внешним mti=0400; ROLLED_BACK — статус reserve, не Transaction |

## 5. Часть (г): пропорции и выводы

Числа ниже считаются по **уникальным ID**, не по количеству assertions, строк pairwise-модели вне раздела тест-кейсов или числу вызовов параметризованного теста. GAP-кейсы входят в **план покрытия** и явно не считаются успешно выполненными автотестами.

<!-- PYRAMID_COUNTS_START -->
| Уровень | Старые (пр. 2) | Новые (часть б) | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 1 | 8 | 9 | 5,39% |
| api / integration | 142 | 14 | 156 | 93,41% |
| e2e | 0 | 2 | 2 | 1,20% |
| **Итого** | **143** | **24** | **167** | **100,00%** |

Подробно средний слой: **api — 50 старых + 4 новых = 54 (32,34%)**; **integration — 92 старых + 10 новых = 102 (61,08%)**. Доли округлены независимо до двух знаков; сумма подробных округлённых долей может отличаться от 100% на 0,01 п.п. Сводная таблица рассчитана по исходным целым числам: `9/167`, `156/167`, `2/167`.
<!-- PYRAMID_COUNTS_END -->

### Интерпретация

- Это пирамида **данного документированного набора**, а не инвентаризация всех тестов репозитория. Практика 2 сконцентрирована на HTTP с реальным reserve и БД-постусловиями, поэтому integration занимает большую долю.
- Не подгоняем распределение под 70/20/10. Существующий кейс с обязательным SQL-постусловием нельзя назвать unit только ради широкой базы. Нужен отдельный быстрый unit-набор для доменных правил; N-01…N-08 начинают этот слой.
- Высокая доля API/integration объясняется реальными рисками native SQL, уникальности PAN/RRN, пессимистических блокировок, outbox, RabbitMQ и сериализации. Однако столь узкая unit-база означает более медленную обратную связь; это не «идеальная» пирамида только потому, что E2E мало.
- Ice-cream cone по количеству E2E здесь нет: сквозными оставлены только два критичных пользовательских маршрута. Все permutation/validation/race-проверки поднимать до E2E не нужно.
- Дефицит контрактов оценивается не одной долей: новые проверки отделяют HTTP-код от бизнес-кода, RRN contract, Gateway validation и DELETE schema. Старые contract oracles сначала требуют согласования по разделу 4.
- Дубли классов эквивалентности/BV/pairwise сохранены для трассируемости, а не скрыты уменьшением denominator. В будущем можно убрать доказанные дубли, но это отдельная согласованная переработка исходного набора.
- Рекомендуемый порядок запуска: unit/api в каждом изменении; integration на изолированных PostgreSQL/RabbitMQ; два E2E после сборки стенда. Временные окна в новых async-кейсах — параметры тестов, не гарантия производительности системы.

## 6. Проверка артефакта

Проверяются шесть Mermaid-файлов, локальные ссылки, уникальность и полнота 143 старых ID, 24 новых ID, допустимые уровни, структура таблиц и арифметика долей. Это статическая проверка документов; не замена запуску описанных платёжных сценариев и не индивидуальной защите.
