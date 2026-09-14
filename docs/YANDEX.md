# Яндекс Карты в Terra

Нативный MapKit SDK 4.42.0-lite подключён через CocoaPods на iOS и Maven Central на Android. Яндекс выбран по умолчанию. Настройки → Карта позволяют переключиться на Apple Maps (iOS) или OpenStreetMap (Android). Туман, места, геопозиция и отдельная карта истории используют выбранного провайдера. Запись GPS и хранилище от него не зависят.

## Ключ

1. В [кабинете Яндекса](https://developer.tech.yandex.ru/) выбери «Подключить API» → **MapKit — мобильный SDK**. Ключ JavaScript API не подходит.
2. Выбери подходящий тариф. Активация нового ключа обычно занимает около 15 минут.
3. Сохрани ключ в GitHub → Settings → Secrets and variables → Actions как `YANDEX_MAPKIT_API_KEY`.
4. Запусти workflow **Native iOS and Android**. Изменение секрета само по себе сборку не запускает.

Workflow генерирует исключённый из Git файл `mobile/ios/Terra/MapSecrets.swift`. Android читает переменную окружения в BuildConfig. Ключ не записывается в исходники репозитория, но входит в устанавливаемое приложение, как и любой ключ клиентского SDK. Ограничения и квоты настраиваются в кабинете Яндекса.

Идентификатор обеих платформ — `app.terra.explore`. При настройке ограничений учитывай, что AltStore может изменить Bundle ID. Используй фактический идентификатор подписанной сборки.

## Виды карты

Яндекс предоставляет схему со светлой и тёмной темой. Спутник и гибрид в мобильном SDK разрешены только приложениям Яндекса ([MapType](https://yandex.ru/maps-api/docs/mapkit/com/yandex/mapkit/map/MapType.html)). На iPhone спутник и гибрид можно выбрать после переключения на Apple Maps.

Google Maps ещё не подключена. Нужны отдельные ключи Maps SDK for iOS / Android и проект Google Cloud с биллингом.

## Локальная сборка

Установи переменную окружения `YANDEX_MAPKIT_API_KEY`, не публикуя её значение.

На Mac из корня репозитория:

```sh
python3 scripts/configure-map-key.py
cd mobile/ios
xcodegen generate
pod install
open Terra.xcworkspace
```

Android Gradle использует ту же переменную. Без неё локальная Android-сборка оставляет OpenStreetMap и не активирует Яндекс.

Официальные инструкции: [iOS](https://yandex.ru/maps-api/docs/mapkit/ios/generated/getting_started.html), [Android](https://yandex.ru/maps-api/docs/mapkit/android/generated/getting_started.html).
