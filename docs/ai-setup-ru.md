# Установка через ИИ-помощника

[Все способы установки](https://github.com/ekkzy/softlumi/blob/main/docs/installation.md) · [English](ai-setup.md)

Этот способ подходит, если ИИ-помощник имеет доступ к локальному терминалу
Windows. Обычный чат может объяснить команды, но не выполнить их на компьютере.
Если ИИ не нужен, используй [Windows-мастер с одной командой](https://github.com/ekkzy/softlumi/blob/main/docs/one-command-setup-ru.md).

Скачай [softlumi-ai-setup-candidate.14.zip](https://github.com/ekkzy/softlumi/releases/download/v0.3.0-candidate.14-setup.2/softlumi-ai-setup-candidate.14.zip)
из [приватного выпуска setup.2](https://github.com/ekkzy/softlumi/releases/tag/v0.3.0-candidate.14-setup.2)
и распакуй в отдельную локальную папку. Для загрузки нужен доступ к репозиторию;
пароль GitHub и токены передавать помощнику не нужно. Внутри — приложение
**softlumi 0.3.0-candidate.14 / code 35**. EVE-BOX — новое имя проекта,
но название APK на ТВ, package и подпись в этом выпуске прежние.

## Подготовка

Нужны Windows PowerShell 5.1 или PowerShell 7, доверенные Android SDK
Platform-Tools (`adb`), Build-Tools (`apksigner`, `aapt`) и Java для `apksigner`.
ИИ-режим использует обычную проверку APK через SDK. Windows-мастер отличается:
он принимает только закреплённый APK этого выпуска и не требует Build-Tools.

Официальные инструменты: [Platform-Tools](https://developer.android.com/tools/releases/platform-tools),
[Build-Tools](https://developer.android.com/tools/releases/build-tools),
[apksigner](https://developer.android.com/tools/apksigner).
Если инструментов нет, сначала подготовь их; не подменяй проверку подписи
простым вычислением хеша неизвестного файла.

Включение отладки, первый запрос разрешения на ТВ и сопряжение по коду при
необходимости выполняет владелец. `setup-tv.ps1` сам не делает `connect`,
`pair` или `tcpip`. Помощник должен явно выбрать адресат ADB и прочитать его
модель и build fingerprint, а не брать IP из старого журнала.

Открой папку комплекта в помощнике и передай ему
[промпт установки](installation-prompt-ru.md). По умолчанию разрешена только
установка и открытие приложения. Любые дополнительные действия сначала должны
появиться в плане и получить твоё подтверждение.

## План установки

Метаданные ZIP находятся в `manifest.json`; копия в репозитории —
[`distribution/ai-setup/manifest.json`](https://github.com/ekkzy/softlumi/blob/main/distribution/ai-setup/manifest.json).
Сверь источник комплекта и файлы с доверенным выпуском и
[SHA256SUMS.txt](https://github.com/ekkzy/softlumi/releases/download/v0.3.0-candidate.14-setup.2/SHA256SUMS.txt).
Установщик проверяет SHA-256 APK, сертификат, пакет, Activity, версию и
совместимость Android. Пакет приложения — `io.github.ekkzy.softlumi`.

Пример для помощника, из корня распакованного комплекта. Пути, модель и
fingerprint нужно заменить данными именно выбранного устройства:

```powershell
$setupTarget = @{
    Serial = '<явно выбранный serial>'
    ExpectedModel = '<модель выбранного ТВ>'
    ExpectedFingerprint = '<build fingerprint выбранного ТВ>'
    AdbPath = 'C:\Android\Sdk\platform-tools\adb.exe'
}
$setupManifest = Get-Content .\manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json
New-Item -ItemType Directory -Path .\setup-private -Force | Out-Null
$setupPlan = '.\setup-private\plan-01.json'

.\scripts\setup-tv.ps1 -Mode Plan @setupTarget `
    -Apk $setupManifest.apk.file -ApkSha256 $setupManifest.apk.sha256 `
    -SigningCertSha256 $setupManifest.apk.signingCertSha256 `
    -ApkSignerPath 'C:\Android\Sdk\build-tools\35.0.0\apksigner.bat' `
    -AaptPath 'C:\Android\Sdk\build-tools\35.0.0\aapt.exe' -PlanPath $setupPlan

$setupApprovedSha = (Get-FileHash $setupPlan -Algorithm SHA256).Hash.ToLowerInvariant()
```

`Plan` читает состояние ТВ и сохраняет файл локально; на приставке ничего
не меняет. Помощник показывает устройство, APK, прежний и запрошенный HOME,
каждый выбранный пакет и действия без изменений. Подтверждение относится
к этому файлу и его SHA-256. Нельзя менять план и молча пересчитывать
одобренный хеш. План действует 30 минут; просроченный или новый план требует
нового просмотра. Каждой операции нужны новые имена плана и журнала.

Только после твоего подтверждения:

```powershell
.\scripts\setup-tv.ps1 -Mode Apply @setupTarget -PlanPath $setupPlan `
    -ApprovedPlanSha256 $setupApprovedSha -JournalPath .\setup-private\apply-01.json

.\scripts\setup-tv.ps1 -Mode Inspect @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Apply` устанавливает APK с сохранением данных, сверяет версию и SHA-256
установленного файла на устройстве, открывает softlumi и проверяет его передний
план. Затем выполняет выбранные изменения пакетов и HOME. Понижение версии
не допускается. `Inspect` только читает состояние, без исправлений и перезаписи
журнала.

## HOME и приложения

Профиль `generic-install-only` выбран по умолчанию: HOME и пакеты не меняются.
Для дополнительных действий нужна точная прошивка из
[профиля Mi Box S 3](../scripts/setup-profiles/mi-box-s3-android14.json):
модель `MiTV-AFMU0`, API 34 и fingerprint
`Xiaomi/twilight/twilight:14/UKG3.250826.001/V816.0.11.0.UZFAABX:user/release-keys`.
На другой модели или сборке остаётся базовая установка. Не редактируй профиль
ради обхода проверки.

В команду **Plan** можно явно добавить:

- `-ProfileId mi-box-s3-android14 -SetHome` — назначить softlumi домашним экраном;
- `-DisableStockHome` — дополнительно отключить Google TV Home; требует `-SetHome`;
- `-DisablePackages` — массив точных имён выбранных необязательных пакетов из профиля.

В списке выбора есть Netflix, Prime Video, YouTube Music, Google Play Games,
Mi Music, Xiaomi analytics и Google Feedback. Все варианты изначально выключены.
Отключённое приложение не запускается у пользователя Android 0, но его данные
сохраняются. Пакеты в состояниях 2, 3 и 4 остаются точно в прежнем состоянии.
Отсутствующий выбранный пакет останавливает подготовку или применение.
Ошибка во время выполнения может оставить частичный результат в журнале.

Это не перенос всех отключений с чужой приставки и не обещание убрать всю
рекламу, телеметрию или улучшить производительность. Службы пульта и старые
перехватчики HOME, accessibility, учётные записи, сон, заставка, CEC и голосовое
управление не настраиваются. Конфликт со старой службой требует отдельного
разбора, а не массового отключения специальных возможностей.

## Возврат и ошибки

Сначала выполни `Inspect`, затем при необходимости:

```powershell
.\scripts\setup-tv.ps1 -Mode Restore @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Restore` возвращает только записанные изменения HOME и пакетов, в том числе
из частично выполненного плана. Прежний HOME-пакет восстанавливается до выбора
его Activity. Установленный APK и данные сохраняются; старая версия приложения
не возвращается. Нужны тот же адресат и неизменный ADB. Журнал старше 14 дней,
другая прошивка или последующие изменения состояния требуют разбора.

При неизвестном результате установки `Inspect` показывает
`installOutcomeUnknown: true`, журнал может иметь статус `NeedsReconcile`.
Даже видимые версия и хеш APK не разрешают автоматический повтор:
`Restore` тоже отказывает в таком состоянии. Сначала нужно установить, что
установочная сессия Android завершена, либо отдельно устранить её неопределённость.
Не удаляй приложение, не очищай данные и не понижай версию ради обхода ошибки.

После потери ответа на отключение штатного HOME возможен частичный возврат:
`Restore` сначала вернёт свой затронутый HOME-пакет, но откажется перезаписывать
неизвестный сторонний выбор HOME. Сверь журнал перед продолжением; не редактируй
его вручную.

Проверь физическую кнопку Home, Back, запуск приложений и избранное.
Полный проход нового комплекта на Mi Box S 3 с заводской конфигурацией ещё
не подтверждён. Установка не выдаёт доступ к истории просмотра автоматически.
Планы и журналы содержат адресат, прошивку, HOME и состояния пакетов: храни
`setup-private` локально и не публикуй эти файлы.
