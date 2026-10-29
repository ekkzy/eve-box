# Установка через прямые команды PowerShell

Обычная установка, в том числе с ИИ, использует
[Windows-мастер `install.cmd`](one-command-setup-ru.md). Этот документ нужен
для ручной работы с `scripts/setup-tv.ps1`: проверки APK через Android SDK,
подготовки отдельного файла плана и его применения по одобренному SHA-256.

Команды ниже выполняются из корня распакованного
[Eve-Box-setup-candidate.15.zip](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/Eve-Box-setup-candidate.15.zip).
Нужны Windows PowerShell 5.1 или PowerShell 7, доверенные
[Platform-Tools](https://developer.android.com/tools/releases/platform-tools),
[Build-Tools](https://developer.android.com/tools/releases/build-tools)
с `aapt` и `apksigner`, а также Java для
[проверки подписи](https://developer.android.com/tools/apksigner).
Эти дополнительные инструменты нужны только для описанного здесь SDK-пути.

## Подготовка плана

Сверь источник и файлы комплекта с `manifest.json` и
[SHA256SUMS.txt](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/SHA256SUMS.txt).
Метаданные содержат ожидаемые package, версию, SHA-256 APK и сертификата.
Хеш неизвестного файла сам по себе не подтверждает доверие к издателю.

Сначала подключи нужную приставку и разреши отладку на её экране.
`setup-tv.ps1` сам не делает `connect`, `pair` или `tcpip`. Укажи текущие
serial, модель и fingerprint выбранного устройства; не бери адрес из старого
журнала. Подставь свои пути к инструментам:

```powershell
$setupTarget = @{
    Serial = '<выбранный serial>'
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

`Plan` читает состояние ТВ и сохраняет локальный файл; на приставке ничего
не меняет. По умолчанию `generic-install-only` только устанавливает и открывает
приложение. Для дополнительных действий требуется точный
[профиль Mi Box S 3](../scripts/setup-profiles/mi-box-s3-android14.json).
В команду **Plan** можно явно добавить:

- `-ProfileId mi-box-s3-android14 -SetHome` — назначить Eve Box домашним экраном;
- `-DisableStockHome` — отключить Google TV Home; требует `-SetHome`;
- `-DisablePackages` — массив точных имён выбранных необязательных пакетов профиля.

Не меняй профиль для обхода несовпадения прошивки. Выбор каждого отключения
должен быть явным. Данные приложений сохраняются; служебные ограничения
и список доступных действий приведены в [инструкции мастера](one-command-setup-ru.md#выбор-действий).

Покажи владельцу приставки устройство, APK, прежний и запрошенный HOME,
каждый выбранный пакет и действия без изменений. Согласие относится
к конкретному файлу плана и его SHA-256. Не меняй план с молчаливым пересчётом
одобренного хеша. План действует 30 минут; новый план требует нового просмотра.
Используй новые имена плана и журнала для каждой операции и не запускай
несколько установщиков для одной приставки одновременно.

## Применение и проверка

Только после подтверждения конкретного плана владельцем:

```powershell
.\scripts\setup-tv.ps1 -Mode Apply @setupTarget -PlanPath $setupPlan `
    -ApprovedPlanSha256 $setupApprovedSha -JournalPath .\setup-private\apply-01.json

.\scripts\setup-tv.ps1 -Mode Inspect @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Apply` устанавливает APK с сохранением данных, проверяет фактические версию
и SHA-256, открывает лаунчер и затем выполняет выбранные изменения пакетов
и HOME. Понижение версии не допускается. `Inspect` только читает состояние;
он не исправляет ошибки и не перезаписывает журнал.

Проверь физические Home и Back, запуск приложений и избранное. Установка
не выдаёт доступ к истории просмотра автоматически. Полный проход нового
установщика на физической Mi Box S 3 с заводской конфигурацией не подтверждён.

## Возврат и ошибки

Сначала прочитай результат `Inspect` и журнал. Если возврат доступен:

```powershell
.\scripts\setup-tv.ps1 -Mode Restore @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Restore` возвращает только записанные изменения HOME и пакетов, включая
частично выполненный план. APK и данные остаются. Требуются тот же адресат,
прошивка и неизменный ADB, журнал не старше 14 дней и отсутствие конфликтующих
последующих изменений. Журнал не является резервной копией всей системы.

При неизвестном результате установки `Inspect` показывает
`installOutcomeUnknown: true`; журнал может иметь статус `NeedsReconcile`.
Наблюдаемые версия и SHA-256 не разрешают автоматический повтор. `Restore`
тоже отказывает: сначала нужно проверить завершение установочной сессии Android
либо отдельно устранить её неопределённость. Не удаляй приложение, не очищай
данные и не понижай версию ради обхода ошибки.

При частичном возврате и конфликте HOME часть записанных изменений уже может
быть отменена. Не редактируй журнал и не перезаписывай сторонний выбор HOME;
сверь результат `Inspect` перед продолжением.

Планы и журналы содержат сведения о приставке и её настройках. Храни
`setup-private` локально; не публикуй и не отправляй эти файлы в облако.
