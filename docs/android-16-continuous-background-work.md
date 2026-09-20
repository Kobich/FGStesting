# Android 16: как организовать постоянную фоновую работу без root

## Короткий вывод

На обычном Android 16 нельзя получить системную гарантию «процесс приложения никогда не будет остановлен». Foreground Service значительно повышает приоритет процесса, но не делает его бессмертным. Поэтому рабочая архитектура должна решать не одну, а две задачи:

1. Пока процесс жив — непрерывно выполнять выбранную пользователем работу в корректном Foreground Service.
2. После допустимой остановки — восстановить процесс, состояние, подписки и соединения несколькими независимыми способами.

Для нашего проекта рекомендуемая база выглядит так:

```text
Пользователь явно включает режим
→ приложение сохраняет конфигурацию режима на диск
→ из видимой Activity запускается FGS нужного типа
→ FGS регистрирует event-specific API
→ каждое событие сначала сохраняется локально
→ затем обрабатывается или подтверждается сервером

Если процесс убит системой
→ START_STICKY пытается пересоздать сервис
→ пассивные системные триггеры создают дополнительные точки восстановления
→ сервис читает состояние с диска и заново создаёт подписки

Если немедленный FGS-start запрещён
→ событие и намерение продолжить режим сохраняются
→ используется разрешённый fallback
→ при необходимости показывается уведомление для действия пользователя
```

Главный практический вывод:

> Максимально непрерывная работа на Android 16 достигается не обходом одного ограничения, а сочетанием корректного FGS, сохранённого состояния, системных событий, пользовательского battery exemption, восстановления соединений и измеряемого тестирования на каждом OEM.

Основные режимы:

| Задача | Основной механизм | Реалистичный результат |
|---|---|---|
| Постоянные координаты | `location` FGS | Наиболее прямой и поддерживаемый режим длительной работы |
| Постоянное BLE-соединение | `connectedDevice` FGS или Companion Device APIs | Можно держать связь долго; после смерти процесса соединение нужно создать заново |
| Обнаружение известного BLE-устройства | Filtered scan + `PendingIntent`; `connectedDevice` после подключения/начала взаимодействия | Близко к event-driven непрерывности без постоянного polling |
| Поиск любых BLE-устройств | Обычный scan | Непрерывность при screen-off не гарантируется; требование нужно сужать фильтром |
| Мониторинг существующей сети | FGS + `NetworkCallback` | Работает, пока жив процесс; callback сам по себе процесс не восстанавливает |
| Активное сканирование Wi-Fi AP | `WifiManager.startScan()` | Ограничено throttling; в Doze активные scans не выполняются |
| Входящие команды сервера без FCM | MQTT/WebSocket + reconnect | Нужен живой процесс и доступная сеть; в Doze без exemption постоянный канал ненадёжен |
| Файлы/SD | SAF или all-files access | Доступ возможен после первого unlock; постоянный watcher всё равно зависит от жизни процесса/CPU |
| NFC телефона при погашенном экране | Публичные NFC API | Надёжного непрерывного чтения нет; внешний считыватель превращает задачу в `connectedDevice` |

### Два рекомендуемых профиля

**Сбалансированный профиль** подходит как базовый production-вариант:

- корректный FGS активного режима;
- `START_STICKY` и состояние на диске;
- event-driven APIs вместо polling;
- локальный журнал и повторная доставка;
- адаптивные интервалы Location;
- wake lock только на короткую обработку;
- battery `Unrestricted` рекомендуется, но приложение умеет деградировать без него.

**Профиль максимальной непрерывности** включается отдельно и явно объясняет расход батареи:

- всё из сбалансированного профиля;
- обязательный battery exemption;
- OEM autostart/background activity;
- постоянный MQTT/WebSocket при активном режиме;
- более быстрый reconnect;
- background location, если он действительно нужен для восстановления;
- короткие wake locks вокруг приёма, reconnect и записи события;
- ADB commissioning и обязательный endurance-test конкретного устройства.

Постоянный wake lock и alarm-watchdog с минимальным интервалом не входят даже в профиль максимальной непрерывности по умолчанию. Они допускаются только как измеряемый эксперимент.

## 1. Контекст исследования

Исследование относится к следующим условиям:

- Android 16, API/target SDK 36;
- устройства OnePlus, Rock и Pixel;
- обычное пользовательское приложение без root и кастомной прошивки;
- APK устанавливается локально и не публикуется в Google Play;
- постоянное уведомление FGS допустимо;
- пользователь готов выдать необходимые разрешения и выполнить разовые настройки;
- ADB при первичной настройке допустим;
- Firebase/FCM не используется;
- серверный канал нужен преимущественно для приёма;
- одновременно обычно активен один режим;
- работа до первого unlock после reboot пока не требуется.

Цель — минимизировать задержку события и промежутки недоступности, сохранив разумный расход батареи. Приоритет отдаётся гарантированной доставке там, где Android вообще доставил событие приложению.

Важно различать две гарантии:

- **гарантия обработки полученного события** достижима через локальный журнал, идентификаторы событий, повторную обработку и подтверждения;
- **гарантия того, что Android всегда создаст событие и разбудит приложение**, зависит от конкретного API и не всегда существует.

## 2. Почему одного Foreground Service недостаточно

FGS решает только часть задачи. Он сообщает Android и пользователю, что приложение выполняет заметную длительную работу, показывает постоянное уведомление и повышает важность процесса. Но FGS не отменяет:

- Doze;
- ограничения фонового запуска FGS;
- prerequisites конкретного FGS type;
- ограничения Bluetooth/Wi-Fi/NFC API;
- отзыв runtime permissions;
- OEM power management;
- пользовательскую остановку приложения;
- потерю сети и разрыв внешнего соединения.

Поэтому нельзя рассуждать так:

```text
FGS запущен → процесс бессмертен → все события приходят
```

Корректная модель состоит из шести независимых проверок:

1. Жив ли процесс.
2. Есть ли право создать или восстановить FGS сейчас.
3. Выбран ли правильный FGS type и выполнены ли его prerequisites.
4. Доставляет ли нужный Android API события при screen-off/Doze.
5. Доступны ли CPU и сеть.
6. Не ограничил ли приложение OEM.

Официальная документация Android прямо рекомендует проектировать долгоживущий service с учётом возможного уничтожения и восстановления процесса: [Services overview](https://developer.android.com/develop/background-work/services). Типы и разрешения FGS для target 34+ описаны в [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

## 3. Рекомендуемая архитектура

### 3.1 Один контроллер активного режима

Для production-версии разумно иметь один started-service, например `MonitoringService`, и хранить выбранный режим отдельно:

```text
LOCATION
BLE_CONNECTION
BLE_DISCOVERY
WIFI_CONNECTION
WIFI_DISCOVERY
FILE_WATCH
SERVER_CHANNEL
```

В manifest объявляются только потенциально используемые типы. При конкретном вызове `startForeground()` передаётся только фактически активный тип:

- `location` для координат;
- `connectedDevice` для Bluetooth/взаимодействия с внешним устройством;
- `specialUse` только для валидного постоянного сценария, который не покрывает стандартный тип;
- `dataSync` только для настоящей ограниченной по времени передачи/обработки данных.

Если передать сразу несколько типов, Android применяет требования каждого из них. Поэтому схема «всегда передавать все типы на всякий случай» повышает вероятность `SecurityException` и не даёт дополнительных возможностей.

Порядок запуска:

1. Проверить runtime permissions и системные настройки.
2. Записать `monitoringEnabled`, выбранный режим и конфигурацию на диск.
3. Пока Activity видима, вызвать `startForegroundService()`.
4. В сервисе создать notification channel и вызвать `startForeground()` не позднее системного пятисекундного окна.
5. Только после этого инициализировать GPS, BLE, сеть или другой источник.

Android описывает этот двухэтапный запуск в [Launch a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch).

### 3.2 Состояние должно жить дольше процесса

В памяти процесса нельзя хранить единственную копию состояния режима. Минимально на диск сохраняются:

```text
monitoringEnabled
activeMode
modeConfiguration
sessionId
lastSuccessfulEventTime
lastServerAck
restartAttempt
lastFailureReason
```

`onStartCommand()` возвращает `START_STICKY`. При пересоздании Android может вызвать `onStartCommand()` с `intent == null`, поэтому сервис читает конфигурацию с диска и выполняет идемпотентную инициализацию.

`START_STICKY` — не watchdog и не гарантия мгновенного рестарта. Он означает, что после обычного уничтожения started-service система оставляет его в started state и позже пытается создать заново. Ограничение фонового FGS-start на Android 12+ не блокирует именно системное восстановление sticky-FGS, но продолжает действовать на новую попытку старта из background. Это зафиксировано в [`Service.START_STICKY`](https://developer.android.com/reference/android/app/Service#START_STICKY).

### 3.3 Локальный журнал событий

Каждое принятое событие сначала записывается в локальное долговечное хранилище, например Room:

```text
eventId
bootSessionId
processSessionId
serviceSessionId
eventType
payload
wallTimeUtc
elapsedRealtime
deliveryState
retryCount
```

Это позволяет обеспечить at-least-once processing:

1. событие получено;
2. сохранено транзакционно;
3. обработано локально;
4. при необходимости отправлено;
5. сервер подтвердил `eventId`;
6. запись помечена доставленной.

После смерти процесса очередь продолжает существовать. Повторная обработка не должна создавать дубликаты на сервере: `eventId` используется как idempotency key.

### 3.4 Восстановление без restart loop

При каждом старте сервис:

1. определяет причину запуска;
2. проверяет, что режим всё ещё включён пользователем;
3. проверяет разрешения и состояние оборудования;
4. восстанавливает только отсутствующие registrations/соединения;
5. использует backoff после повторяющихся ошибок;
6. не пытается бесконечно рестартовать себя при постоянной ошибке permission/provider.

Для диагностики полезно сохранять `ApplicationExitInfo`, если система его предоставляет, и отдельно учитывать user-requested stop.

## 4. Что происходит при разных остановках

| Ситуация | Ожидаемое поведение | Стратегия |
|---|---|---|
| Activity закрыта/Home | FGS продолжает работу | Нормальный рабочий режим |
| Удаление task из Recents | FGS обычно продолжает работу, если приложение/OEM не связывает это с остановкой | Проверять на каждом OEM |
| Обычная смерть процесса | Соединения и callbacks теряются; sticky-service может быть пересоздан | `START_STICKY`, persisted state, повторная регистрация |
| OEM kill | Поведение не стандартизировано | Battery unrestricted, autostart/background activity, endurance-test |
| Stop через Android FGS Task Manager | Android удаляет процесс, back stack и FGS notification; callback приложению не посылается | Считать явной пользовательской остановкой; при следующем запуске анализировать `REASON_USER_REQUESTED` |
| Force Stop в Settings/ADB | Административная остановка приложения | Не обещать автоматический обход; использовать как отрицательный контроль |
| Reboot | Все процессы и runtime registrations исчезают | `BOOT_COMPLETED` восстанавливает persisted registrations; Direct Boot сейчас не нужен |
| Обновление APK | Процесс может быть пересоздан, registrations нужно восстановить | `MY_PACKAGE_REPLACED` + persisted state |
| Permission revoked | Следующая операция может завершиться ошибкой | Остановить соответствующий режим, сохранить причину, запросить действие пользователя |

Android отдельно документирует действие кнопки Stop в FGS Task Manager: процесс удаляется из памяти, уведомление убирается, но ранее запланированные jobs и alarms остаются. Приложение не получает callback в момент остановки: [Handle user-initiated stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).

Force Stop нельзя смешивать с обычной смертью процесса. Требование «самостоятельно воскреснуть после административного Force Stop» не должно быть критерием успеха обычного приложения.

## 5. Запуск FGS из background

На Android 12+ произвольный запуск FGS из background запрещён. Основной безопасный путь — начинать пользовательский режим из видимой Activity.

Официальные исключения включают, среди прочего:

- действие пользователя в notification/widget/activity;
- exact alarm для действия, запрошенного пользователем;
- geofence или Activity Recognition transition;
- `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` с дополнительными ограничениями типов;
- Companion Device permissions;
- приложение, выведенное пользователем из battery optimizations;
- некоторые системные роли;
- видимый overlay при наличии `SYSTEM_ALERT_WINDOW` на Android 15+.

Полный перечень: [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

### Отдельная проблема location

Location permission относится к while-in-use permissions. Даже если приложение попало в общее исключение для background FGS-start, этого может быть недостаточно для создания `location` FGS из настоящего background. На Android 14+ проверка выполняется в момент создания сервиса.

Практическая схема:

- первый запуск режима — только из видимой Activity;
- `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` запрашиваются до старта;
- `ACCESS_BACKGROUND_LOCATION` добавляется только если нужен сценарий создания/восстановления location-доступа из background;
- foreground и background location запрашиваются последовательно согласно системной модели permissions.

## 6. FGS-типы для проекта

| Тип | Использование | Ограничение |
|---|---|---|
| `location` | Длительные координаты, navigation/location sharing | Нужны включённая системная Location и location permission; фоновый старт осложнён while-in-use rule |
| `connectedDevice` | Bluetooth, USB, NFC, IR или сетевое взаимодействие с внешним устройством | Должен быть выполнен хотя бы один документированный prerequisite |
| `dataSync` | Настоящий upload/download/import/export/local processing | Для target 35+ суммарно 6 часов за 24 часа в background |
| `shortService` | Короткая критическая операция | Около 3 минут; sticky не поддерживается |
| `specialUse` | Валидный длительный use case, не покрытый именованными типами | Нужно описать subtype; тип не отменяет Doze и API-level ограничения |
| `systemExempted` | Системные и специальные интеграции | Доступен только при выполнении специальных критериев; не универсальный keep-alive |

### `location`

Это основной тип для режима координат. У `location` нет документированного шестичасового timeout, который применяется к `dataSync`. Это не означает бесконечную гарантию процесса, но позволяет держать пользовательскую location-сессию длительно.

### `connectedDevice`

Это основной тип для постоянной связи с BLE/GATT-устройством или внешним RFID reader. Среди prerequisites Android перечисляет Bluetooth runtime permissions, Wi-Fi/network manifest permissions, NFC и USB permission. Сам тип не отменяет ограничения сканера; он только корректно описывает длительную работу с устройством.

### `dataSync`

Не подходит для вечного service. Для приложений, targeting Android 15+, `dataSync` и `mediaProcessing` получают общий для каждого типа бюджет 6 часов за 24 часа, пока приложение находится в background. После исчерпания бюджета Android вызывает `onTimeout()`, а новый старт может завершиться `ForegroundServiceStartNotAllowedException`: [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).

### `specialUse`

Допустим для настоящего FGS use case, не покрытого другими типами. В manifest указывается `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Для sideloaded APK отсутствует этап проверки описания через Google Play Console, но Android runtime всё равно применяет:

- background-start restrictions;
- Doze;
- permissions;
- ограничения Wi-Fi/BLE/NFC API;
- OEM power management.

Следовательно, `specialUse` может легально описать режим, но не является техническим bypass.

### `systemExempted`

Не использовать как базовую схему. Android разрешает этот тип только специальным категориям, среди которых Device Owner/Profile Owner, Device Admin, emergency role, настроенный VPN, demo mode и приложения с exact-alarm permission. Сам факт eligibility для типа не отменяет остальные ограничения и не превращает arbitrary monitoring в гарантированную работу.

## 7. Doze и App Standby

Doze и App Standby — разные механизмы.

FGS обычно не даёт приложению перейти в App Standby, потому что у приложения есть foreground process. Но device-wide Doze всё равно существует.

В обычном Doze Android:

- приостанавливает доступ к сети;
- игнорирует wake locks;
- откладывает обычные alarms;
- не запускает jobs и WorkManager;
- не выполняет Wi-Fi scans;
- периодически открывает maintenance windows.

Источник: [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

### Что даёт battery exemption

Если пользователь вручную выводит приложение из battery optimization, Android разрешает ему во время Doze:

- использовать сеть;
- удерживать partial wake locks.

При этом exemption не снимает все ограничения. В частности, он не превращает Wi-Fi scanning в гарантированную операцию в Doze, не делает WorkManager точным таймером и не отменяет prerequisites FGS-типа.

Для нашего проекта `Unrestricted`/battery exemption — обязательная рекомендуемая настройка режима с минимальной задержкой. Но в документе и UI её нужно честно называть пользовательской настройкой энергопотребления, а не скрытым permission.

### Wake lock

FGS и wake lock решают разные задачи:

- FGS повышает важность процесса и показывает пользователю длительную работу;
- `PARTIAL_WAKE_LOCK` удерживает CPU после выключения экрана.

Базовая рекомендация — брать wake lock на ограниченный период вокруг критической обработки и отпускать в `finally`. Постоянный wake lock допустим только как отдельный экспериментальный high-power режим с измеренным расходом и явным предупреждением пользователю.

### AlarmManager

Правила «один alarm в восемь часов» нет. `setAndAllowWhileIdle()` и `setExactAndAllowWhileIdle()` могут срабатывать в Doze, но не чаще примерно одного раза за девять минут на приложение. Это верхний платформенный предел, а не рекомендуемая частота watchdog.

Exact alarms применяются только для действительно точного пользовательского события. Для target 33+ используются `SCHEDULE_EXACT_ALARM` или ограниченный по use case `USE_EXACT_ALARM`: [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms).

Рекомендуемый порядок:

```text
START_STICKY
→ passive/event-specific triggers
→ battery exemption
→ редкий inexact/allow-while-idle recovery alarm, если обоснован
→ exact alarm только для точного пользовательского события
```

Не следует строить основную непрерывность на alarm каждые девять минут. Это расходует батарею, создаёт большие окна потери событий и борется с платформой вместо использования event-driven API.

### WorkManager и JobScheduler

WorkManager предназначен для гарантированного eventual execution, но не для низкой задержки и не для удержания процесса. Минимальный интервал periodic work — 15 минут, а фактический запуск зависит от системных условий.

На Android 16 jobs расходуют runtime quota даже когда одновременно работает FGS. Это относится также к WorkManager и DownloadManager: [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all).

WorkManager следует использовать для:

- повторной отправки локальной очереди;
- обслуживания базы;
- eventual reconciliation;
- неоперативного восстановления.

Не использовать его как heartbeat работающего FGS.

## 8. Режимы проекта

### 8.1 Координаты

Рекомендуемая схема:

```text
видимая Activity
→ permission check
→ location FGS
→ adaptive location request
→ локальный журнал
→ сервер/локальная обработка
```

Для высокой оперативности используется high accuracy с небольшим интервалом. Для экономии — balanced/passive updates, batching, geofences или переключение профиля по движению.

Нужно различать:

- **непрерывный tracking** — работающий `location` FGS;
- **событие входа/выхода из области** — geofence как более дешёвый системный trigger;
- **переключение профиля при движении** — Activity Recognition transition.

Обычное background-приложение без FGS получает location лишь несколько раз в час. Background location и рекомендации по батарее описаны в [Access location in the background](https://developer.android.com/develop/sensors-and-location/location/background) и [About background location and battery life](https://developer.android.com/develop/sensors-and-location/location/battery).

Практический профиль:

- движение/активная сессия — точные updates;
- неподвижность — увеличить интервал или перейти на geofence/passive;
- экран off не является причиной останавливать FGS;
- provider off/permission revoked — режим переходит в `DEGRADED`, а не restart loop.

### 8.2 BLE-соединение

Для долгого GATT-соединения используются `connectedDevice` FGS или Companion Device APIs.

После смерти процесса обычное GATT-соединение закрывается. Поэтому service при восстановлении должен:

1. прочитать адрес/association;
2. проверить Bluetooth permissions и состояние адаптера;
3. создать соединение заново;
4. повторно включить characteristic notifications;
5. использовать backoff.

Android рекомендует для долгой связи `connectedDevice` FGS либо Companion Device presence: [Communicate in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background).

### 8.3 BLE discovery

Постоянный callback-scan требует живого процесса. Для обнаружения известного устройства лучше использовать:

- `ScanFilter` по service UUID/manufacturer data;
- `BluetoothLeScanner.startScan(..., PendingIntent)`;
- Companion Device association/presence, если подходит модель устройства.

`PendingIntent` scan позволяет системе создать процесс при обнаружении соответствующего advertising-пакета. Это лучше периодического alarm-polling.

Если задача заканчивается только обнаружением advertising-пакета, сам scan API является основным механизмом, а `connectedDevice` не нужно добавлять автоматически. Этот FGS-type становится естественным после подключения или начала длительного взаимодействия с внешним устройством. Для отдельной длительной пользовательской scan-сессии тип нужно выбирать по фактическому use case, а не только по наличию permission `BLUETOOTH_SCAN`.

Требование «обнаруживать любое BLE-устройство» нужно считать отдельным high-cost режимом без гарантии непрерывности при screen-off. Если бизнес-событие можно выразить фильтром, нужно выразить его фильтром.

### 8.4 Wi-Fi

Есть две разные задачи:

1. Следить за текущим подключением — `ConnectivityManager.NetworkCallback` при живом процессе.
2. Искать окружающие AP — `WifiManager.startScan()`.

Вторая задача жёстко ограничена. Android 10+ сохраняет throttling:

- foreground app: до четырёх scan requests за две минуты;
- все background apps вместе: один scan за 30 минут;
- в Doze Wi-Fi scans не выполняются.

Источник: [Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan).

FGS или `specialUse` не отменяют эти ограничения. Поэтому проект не должен обещать мгновенное обнаружение любого Wi-Fi AP в deep Doze. Допустимые варианты:

- использовать результаты scan, выполненного системой/другим приложением;
- следить за изменением уже существующего network;
- ограничить ожидания по latency;
- изменить источник события.

### 8.5 Входящий серверный канал

Без FCM приложение поддерживает собственное MQTT/WebSocket-соединение. Это работает, пока одновременно выполняются условия:

- процесс жив;
- сеть доступна;
- Doze/OEM не приостановил доступ;
- NAT/server не закрыл idle connection.

Рекомендуемая схема:

- соединение принадлежит активному FGS-режиму;
- battery exemption предлагается пользователю;
- heartbeat выбирается по реальному NAT timeout, а не ставится максимально частым;
- disconnect всегда запускает reconnect с exponential backoff и jitter;
- после reconnect выполняется resubscribe/reconciliation;
- сервер нумерует команды, приложение хранит последний подтверждённый sequence;
- отсутствие сети не приводит к потере локальных событий.

Для generic входящего серверного канала нет универсального FGS-типа «постоянный socket». Если соединение обслуживает активный `location` или `connectedDevice` режим, тип определяется основной пользовательской работой. Если постоянный приём является самостоятельной пользовательской функцией и не подходит ни под один именованный тип, `specialUse` можно рассматривать как отдельный кандидат. `remoteMessaging` нельзя использовать только из-за слова messaging: этот тип предназначен для переноса текстовых сообщений между устройствами.

Даже при exemption нельзя обещать абсолютную вечность TCP-соединения: радио, сеть, сервер и OEM остаются независимыми причинами разрыва. Гарантировать нужно не соединение, а обнаружение разрыва и корректное восстановление.

### 8.6 NFC/RFID и SD

Встроенный NFC телефона не превращается в непрерывный screen-off reader с помощью FGS. Для постоянного RFID-мониторинга практичный вариант — внешний считыватель по BLE/USB и `connectedDevice`.

Для SD/файлов:

- предпочтителен Storage Access Framework с persistable URI permission;
- полный доступ требует отдельного all-files special access;
- после первого unlock повторная блокировка экрана сама по себе не закрывает credential-encrypted storage;
- file watcher зависит от жизни процесса и CPU, поэтому для постоянного пользовательского режима может потребоваться FGS;
- реальный import/export нельзя маскировать `specialUse`, если работа соответствует `dataSync`.

## 9. Fallback ladder

```text
Уровень A — основной режим
FGS жив
→ Location/BLE/network callbacks
→ локальная запись события

Уровень B — системное восстановление
Процесс убит системой
→ START_STICKY
→ чтение persisted state
→ повторная регистрация/соединение

Уровень C — event-driven recovery
Geofence / Activity Transition / filtered BLE PendingIntent /
Companion presence / разрешённый broadcast
→ процесс создаётся из-за реального события

Уровень D — deferred recovery
FGS сейчас нельзя стартовать
→ сохранить событие и desired state
→ WorkManager или пользовательское notification action

Уровень E — reboot/update
BOOT_COMPLETED / MY_PACKAGE_REPLACED
→ восстановить passive registrations и state
→ запускать FGS только когда тип и permissions это допускают

Уровень F — provisioning
Battery unrestricted
+ OEM background activity/autostart
+ ADB-настройки, если они входят в процесс установки

Уровень G — отрицательная граница
Force Stop / отключённые permissions / запрещённый provider
→ не обещать автоматический обход
→ ждать явного действия пользователя
```

## 10. Установка и настройка устройства

### Обязательные общие шаги

1. Установить APK ожидаемым способом.
2. Разрешить notifications.
3. Разрешить permissions выбранного режима.
4. Запустить режим из видимой Activity.
5. Перевести приложение в battery `Unrestricted`/исключить из оптимизации.
6. Проверить наличие FGS notification и реального `foregroundServiceType` через `dumpsys`.
7. На OEM включить background activity/autostart, если настройки присутствуют.

### Permissions по режимам

| Режим | Основные разрешения/условия |
|---|---|
| Любой FGS | `FOREGROUND_SERVICE`; `POST_NOTIFICATIONS` нужен для обычной видимости notification, хотя сам FGS может стартовать и при отказе |
| Location | `FOREGROUND_SERVICE_LOCATION`, coarse/fine; background location при обоснованном background-start/recovery |
| BLE scan | `BLUETOOTH_SCAN`; FGS type добавляется только когда длительная пользовательская scan-сессия имеет самостоятельный валидный use case |
| BLE connection | Дополнительно `BLUETOOTH_CONNECT` |
| Wi-Fi scan | `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, fine location и включённая системная Location согласно API |
| Wake lock | `WAKE_LOCK` |
| Exact alarm | `SCHEDULE_EXACT_ALARM` или `USE_EXACT_ALARM` только при соответствующем use case |

### ADB

ADB полезен для:

- выдачи обычных runtime permissions там, где команда поддерживается;
- проверки app ops;
- настройки/проверки Doze whitelist в контролируемом развёртывании;
- воспроизводимого тестирования Doze, standby bucket и process death;
- диагностики services, alarms, jobs и battery state.

Не следует строить production-решение на глобальном отключении Doze, undocumented `device_config` или изменениях, затрагивающих весь телефон. Такие настройки нестабильны между OTA/OEM и маскируют реальные ограничения.

### Sideload и Android verification

Отсутствие Google Play снимает магазинную проверку и часть Play policy, но не системные ограничения Android. Установка и background runtime — разные слои.

Начиная с сентября 2026 Android вводит developer verification для распространения приложений на затронутых certified devices. Это следует учесть в deployment-процессе, но verification не даёт дополнительных прав на FGS/Doze: [Android developer verification guide](https://developer.android.com/developer-verification/guides/pdf-guides/adc-guide.pdf).

## 11. OEM: Pixel, OnePlus, Rock

Pixel используется как AOSP baseline. Если схема не работает на Pixel, сначала ищется ошибка permissions/type/Doze/API, а не OEM workaround.

Для OnePlus и Rock нужно считать commissioning частью установки:

- battery mode `Unrestricted`;
- allow background activity;
- autostart/auto launch, если доступен;
- исключение из sleep/deep optimization;
- проверка поведения Clear All;
- Recents lock можно тестировать как дополнительный слой, но не считать гарантией.

Пути меню и фактическое поведение зависят от модели и firmware. Их нельзя надёжно вывести из generic Android API. Поэтому для каждого устройства сохраняются:

```text
manufacturer/model
build fingerprint
Android security patch
набор включённых OEM-настроек
результат endurance-test
```

После OTA тесты повторяются.

## 12. Что не использовать как основную схему

| Метод | Почему не подходит как база |
|---|---|
| Fake `mediaPlayback` | Тип не соответствует работе; не решает API/Doze restrictions |
| `dataSync` как вечный FGS | 6 часов за 24 часа для target 35+ |
| `shortService` | Около трёх минут, sticky не поддерживается |
| Частый exact-alarm watchdog | Высокий расход, ограничения частоты, плохая latency между проверками |
| WorkManager heartbeat | Минимум 15 минут, Doze и quota; Android 16 учитывает quota рядом с FGS |
| Бесконечный wake lock | Высокий расход, thermal/OEM restrictions |
| `specialUse` как магический bypass | Тип не отменяет ограничения конкретных API |
| `systemExempted` без реального основания | Может завершиться `ForegroundServiceTypeNotAllowedException` |
| Accessibility/Notification Listener только ради keep-alive | Пользовательская special access, Restricted Settings и отсутствие контракта непрерывности |
| Overlay только ради background start | На Android 15+ требуется реально видимое overlay window; плохая пользовательская модель |
| Shizuku как единственная основа | Зависит от отдельного привилегированного канала/ADB и не является обычным SDK-контрактом приложения |

Экспериментальные методы можно тестировать отдельно, но основной продукт не должен становиться неработоспособным без них.

## 13. Тестирование

### Что измерять

| Метрика | Смысл |
|---|---|
| Event capture rate | Сколько сгенерированных событий приложение реально получило |
| Processing latency P50/P95/P99 | Время от физического события до локальной записи |
| Recovery latency | Время от смерти процесса/reboot до рабочего режима |
| Maximum silent gap | Максимальный интервал без ожидаемых событий/health markers |
| Unexpected process deaths/day | Количество неожиданных process sessions |
| Delivery completeness | События, подтверждённые сервером, относительно сохранённых |
| Battery cost | %/час или mAh/час по каждому режиму |

### Минимальная матрица

- экран выключен 8–12 часов;
- forced Doze;
- обычная смерть процесса;
- удаление task из Recents;
- OEM Clear All;
- reboot;
- Stop из FGS Task Manager;
- Force Stop как отрицательный контроль;
- Battery Saver;
- заряд ниже 15%;
- отзыв и возврат permission;
- Bluetooth/Location/Wi-Fi off/on;
- отсутствие сети 30–120 минут;
- OTA/обновление APK;
- 24–72 часа непрерывного прогона на каждой модели.

Полезные команды:

```bash
adb shell dumpsys activity services com.example.app
adb shell pidof com.example.app
adb shell dumpsys deviceidle
adb shell dumpsys location
adb shell dumpsys jobscheduler
adb shell dumpsys alarm
adb shell dumpsys batterystats com.example.app

adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset

adb shell am set-standby-bucket com.example.app active
adb shell am set-standby-bucket com.example.app restricted
adb shell cmd activity stop-app com.example.app
adb shell am force-stop com.example.app
```

Последние две команды моделируют разные пользовательские остановки и не должны объединяться в один результат.

## 14. Рекомендованный порядок реализации

1. Реализовать `MonitoringService` с постоянным notification, persisted state и `START_STICKY`.
2. Передавать в `startForeground()` только тип активного режима.
3. Сделать Location эталонным длительным режимом.
4. Реализовать BLE connection и filtered `PendingIntent` scan.
5. Добавить локальный журнал и idempotent delivery.
6. Реализовать reconnect серверного канала и reconciliation.
7. Добавить battery/OEM provisioning screen с проверкой состояния.
8. Восстанавливать passive registrations после reboot/update.
9. WorkManager оставить для eventual retry/maintenance.
10. AlarmManager добавлять только после измеренного recovery gap и с явно сформулированной задачей.
11. Провести endurance-тесты на Pixel, OnePlus и Rock.
12. Только после тестов решать, нужен ли отдельный `specialUse` режим или экспериментальный watchdog.

## 15. Итоговый вердикт

На Android 16 без root можно построить систему, которая работает в фоне очень долго и восстанавливается после обычной смерти процесса. Для Location и постоянного соединения с внешним устройством Android предоставляет подходящие FGS-типы. Для BLE discovery есть event-driven механизмы, способные создать процесс по совпадению фильтра.

Нельзя честно гарантировать:

- бессмертие процесса;
- автоматическое восстановление после Force Stop;
- непрерывный unfiltered BLE scan при screen-off;
- активный Wi-Fi scan в deep Doze;
- мгновенный собственный серверный push без доступной сети/процесса;
- одинаковое поведение разных OEM без commissioning и тестирования.

Поэтому целевое требование следует формулировать так:

> Во время включённого пользователем режима приложение поддерживает корректный FGS, сохраняет каждое принятое событие до обработки, использует несколько независимых механизмов восстановления и достигает измеренного SLA по задержке, потерям событий и времени восстановления на каждой целевой модели.

Это реалистичная, проверяемая и поддерживаемая модель «непрерывной» фоновой работы для Android 16.

## Основные официальные источники

- [Foreground services overview](https://developer.android.com/develop/background-work/services/fgs)
- [Launch a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [`Service.START_STICKY`](https://developer.android.com/reference/android/app/Service#START_STICKY)
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)
- [BLE in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Background location](https://developer.android.com/develop/sensors-and-location/location/background)
- [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)
- [FGS Task Manager stop](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
