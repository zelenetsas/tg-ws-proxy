<div align="center">

# TG WS Proxy — Android Port

**Нативный Android-порт оригинального десктопного проекта**
**[tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) от [Flowseal](https://github.com/Flowseal)**

Этот репозиторий содержит полный исходный код оригинального проекта
(лицензия MIT) плюс нативный порт на Kotlin (Jetpack Compose) в папке
[`android/`](./android/).

Оригинал: **https://github.com/Flowseal/tg-ws-proxy**

</div>

---

## Что это

Локальный MTProto-прокси для Telegram, который ускоряет работу Telegram,
перенаправляя трафик через WebSocket-соединения к датацентрам Telegram.
Данные передаются в том же зашифрованном виде, сторонние серверы не нужны.

```
Telegram → MTProto Proxy (127.0.0.1:1443) → WebSocket/TLS → Telegram DC
                                             ↘ фолбэк: CF Worker → CF proxy → прямой TCP
```

## Структура репозитория

| Путь | Описание |
|---|---|
| `proxy/`, `ui/`, `utils/`, `windows.py`, `macos.py`, `linux.py` | Оригинальное десктопное приложение (Python/asyncio) |
| `android/` | **Android-порт** (Kotlin, Jetpack Compose, Foreground Service) |
| `tools/gen_kotlin_vectors.py` | Генератор тест-векторов из Python-эталона для проверки Kotlin-порта |
| `docs/` | Документация оригинального проекта ([RU](./docs/README.md) / [EN](./docs/EN/README.md)) |

## Android-приложение

- Полный порт крипто-ядра: MTProto obfs2 handshake, AES-CTR ре-шифровка,
  WebSocket-клиент с контролем SNI, пулы соединений, цепочка фолбэков
- Foreground Service + уведомление + Quick Settings Tile
- Устойчивость к Xiaomi/HyperOS: WakeLock, watchdog-рестарт, BootReceiver,
  keepalive-пинги (см. [`android/README.md`](./android/README.md))
- QR-код и «Поделиться» — в LAN-режиме (`host=0.0.0.0`): раздача прокси на ПК/другие
  устройства той же Wi-Fi сети или точки доступа; «Собрать отчёт» для багрепортов,
  авто-реакция на смену сети, пресеты DC («Всё через CF» — фикс негрузящихся медиа)
- Кросс-тесты с Python-эталоном: 13/13 юнит-тестов на эталонных векторах

### Сборка Android

```bash
cd android
./gradlew testDebugUnitTest # кросс-тесты с эталоном
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # release APK (нужен свой keystore, см. ниже)
```

Требования: JDK 17, Android SDK (API 36). Подробнее:
[`android/README.md`](./android/README.md).

## Атрибуция

- Оригинальный проект: [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — Copyright (c) 2024 Flowseal
- Android-порт: Copyright (c) 2026 zelenetas

Распространяется под лицензией [MIT](./LICENSE). Копирайты обоих авторов
сохранены в соответствии с требованиями лицензии.

## Лицензия

[MIT License](./LICENSE)
