# TgWsProxy for Android

Нативный Kotlin-порт [tg-ws-proxy](../../README.md): локальный MTProto-прокси
для Telegram, который переносит трафик через WebSocket-соединения к DC Telegram.

```
Telegram → MTProto (127.0.0.1:1443) → WebSocket/TLS → Telegram DC
                                        ↘ фолбэк: CF Worker → CF proxy → прямой TCP
```

## Сборка

Проверено на macOS 13.7 x64, JDK 17.0.12, Gradle 8.11.1, AGP 8.9.1,
compileSdk 36, build-tools 36.0.0:

```bash
cd android
./gradlew testDebugUnitTest # 13/13 тестов (кросс-валидация с Python-эталоном)
./gradlew assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk (~10 МБ)
```

Установка на устройство:

```bash
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Требуется JDK 17 и Android SDK (API 36). Платформа android-36 устанавливается
AGP автоматически (лицензии должны быть приняты), либо вручную:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```


## Структура

| Пакет | Содержимое | Порт из |
|---|---|---|
| `proxy/` | Ядро прокси (без Android-зависимостей) | `proxy/*.py` |
| `proxy/Protocol.kt` | Константы, DC-IP, ссылки `tg://proxy` | `proxy/utils.py` |
| `proxy/Handshake.kt` | obfs2 init, relay-init, CryptoCtx | `tg_ws_proxy.py` |
| `proxy/MsgSplitter.kt` | Нарезка MTProto-пакетов для WS-фреймов | `bridge.py` |
| `proxy/WsCodec.kt` + `RawWebSocket.kt` | WebSocket-клиент с контролем SNI | `raw_websocket.py` |
| `proxy/WsPool.kt` / `CfWorkerPool.kt` | Пулы соединений с ротацией | `pool.py` |
| `proxy/Fallback.kt` | Цепочка фолбэков | `bridge.py::do_fallback` |
| `proxy/CfDomains.kt` | Пул CF-доменов + обновление с GitHub | `config.py` |
| `config/ConfigStore.kt` | config.json + валидация | `default_config.py`, `ui/settings.py` |
| `service/ProxyService.kt` | Foreground Service + уведомление | трей-приложение |
| `service/ProxyTile.kt` | Плитка в шторке Quick Settings | — |
| `ui/` | Compose: статус/настройки/логи | `ui/ctk_*.py` |
| `log/` | Лог с ротацией + цензор доменов | `logging_setup.py` |

## Тесты

`app/src/test/VectorsTest.kt` сверяет крипто-ядро с эталонной Python-
реализацией. Векторы в `app/src/test/resources/vectors/vectors.json`
генерируются скриптом из корня репозитория:

```bash
python3 tools/gen_kotlin_vectors.py   # требует certifi + cryptography
```

При изменении Python-ядра (`proxy/*.py`) перегенерируйте векторы и перегоните
тесты — расхождение Kotlin-порта будет поймано на этапе `testDebugUnitTest`.

## Xiaomi / HyperOS: надёжная работа в фоне

Прошивка агрессивно убивает фоновые процессы. В приложении учтено кодом:

- **WakeLock + WifiLock** на время работы прокси (CPU и Wi-Fi не «засыпают» при выключенном экране);
- **Watchdog**: свайп из «Недавних» (`onTaskRemoved`) и неожиданное убийство (`onDestroy`) планируют рестарт через `AlarmManager` (exact-alarm будит даже в Doze);
- **BootReceiver**: автоподъём после перезагрузки (флаг `autostart` в config.json, по умолчанию включён);
- **Keepalive PING** на idle-WS-соединениях пула старше 45 с — против «тихих» разрывов NAT/Doze;
- Вкладка **«Фон»** в UI: статус исключения из энергосбережения, переключатель автозапуска, переход к настройкам Xiaomi (автозапуск MIUI/HyperOS, точные будильники) с fallback на страницу приложения.

**Обязательные ручные шаги (код их сделать не может):**

1. Вкладка «Фон» → «Запросить исключение» (экономия батареи);
2. Вкладка «Фон» → «Открыть Автозапуск Xiaomi» → включить для TG WS Proxy;
3. В «Недавних»: долгое нажатие на карточку приложения → замок (закрепить);
4. Вкладка «Фон» → «Открыть настройку» (точные будильники) → разрешить.

## Ограничения текущей версии

- **Fake TLS (ee-secret)** и **HTTP/2 media (CfH2)** — этап 2 (ядро готово к ним:
  `buildEeLink` есть, `h2Enabled` учитывается).
- **PROXY protocol v1** — не портирован (нужен только за nginx).
- Проверка обновлений — диалог есть в UI, проверка при старте — этап 2.
