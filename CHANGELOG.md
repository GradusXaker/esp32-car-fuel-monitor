# Changelog

## [2.2.0] - 2026-03-05

### Added
- **Android**: Обновлен визуальный стиль интерфейса (градиенты, типографика, подписи блоков)
- **Release**: Подготовлен свежий APK `CarFuelMonitor-v2.2.0-premium.apk`

### Fixed
- **Android**: Убраны deprecated вызовы `startActivityForResult` и `overridePendingTransition`
- **Android**: Улучшена обработка Bluetooth device extra для API 33+

### Changed
- **Android**: Обновлена версия приложения до `2.2.0` (`versionCode 3`)

## [1.0.2] - 2026-03-04

### Fixed
- **Android**: Исправлен краш приложения при запуске
- **Android**: Добавлена обработка null для BluetoothAdapter
- **Android**: Добавлены try-catch блоки во все методы
- **ESP32**: Убрана зацикленность при отсутствии OLED дисплея
- **ESP32**: Добавлена переменная displayFound для проверки дисплея
- **ESP32**: Явная инициализация Wire.begin(21, 22)

### Changed
- **Android**: Улучшена обработка ошибок
- **Android**: Добавлена защита от NullPointerException
- **ESP32**: Прошивка работает без OLED дисплея

## [1.0.1] - 2026-03-04

### Fixed
- Исправлена инициализация OLED дисплея
- Добавлена проверка наличия дисплея

## [1.0.0] - 2026-03-04

### Added
- Первый релиз
- ESP32 прошивка с OLED и Bluetooth
- Android приложение на Kotlin
- Документация
