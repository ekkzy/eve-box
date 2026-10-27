# Сборка

[English](building.en.md) · [На главную](../README.md)

softlumi написан на Java и native Android Views. Для сборки нужны JDK 17,
Android SDK Platform 35 и Build Tools 35.0.0. Репозиторий фиксирует Android
Gradle Plugin 8.9.2 и Gradle Wrapper 8.11.1. Минимальная версия Android — 8.0
(API 26), target SDK — 35.

Открой проект в Android Studio или укажи свой SDK через `ANDROID_HOME` либо
локальный `local.properties`. Локальные пути и ключи не должны попадать в Git.

## Debug APK

Из корня репозитория:

```sh
./gradlew :app:assembleDebug
```

В Windows PowerShell используй `./gradlew.bat` вместо `./gradlew`.
APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
Debug package — `io.github.ekkzy.softlumi.debug`.

## Проверки

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew :app:assembleDebugAndroidTest
```

Native UI-тесты находятся в `app/src/androidTest`. Они требуют отдельного
тестового Android TV-устройства или эмулятора; некоторые меняют тестовые
настройки и используют fixtures. Не запускай их на личном домашнем экране
без просмотра условий теста. Компиляция тестового APK не означает, что тесты
выполнены.

## Release

```sh
./gradlew :app:assembleRelease
```

Release использует R8 и сокращение ресурсов. По умолчанию результат не подписан.
Для установки подпиши его своим постоянным ключом Android; дальнейшие обновления
требуют того же ключа. В репозитории нет приватного ключа выпуска.

Лицензии ресурсов перечислены в [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Проверки на GitHub

GitHub Actions пока не настроен. Сборка, JVM-тесты и lint запускаются локально
командами выше; эмуляторные сценарии доступны в `scripts/ci-tv-smoke.sh` и
`scripts/ci-tv-permission.sh`. Онлайн-проверки можно добавить отдельно.
