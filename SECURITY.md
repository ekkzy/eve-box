# Security

## Сообщить о проблеме

Если проблема позволяет прочитать личные данные, выполнить неожиданный intent
или обойти проверку файла, сначала сообщи владельцу репозитория приватно.
Не помещай подробности эксплуатации и личные данные в публичное обсуждение.

Достаточно версии Android и приложения, коротких шагов воспроизведения,
ожидаемого и фактического поведения. Приложи минимальный пример без аккаунтов,
истории просмотра, адресов устройств, токенов, приватных ключей и личных JSON-файлов.

Устанавливай APK из доверенного источника. Обновление существующего пакета
требует того же ключа подписи; удаление приложения ради обхода ошибки стирает
данные. Переход на `io.github.ekkzy.evebox` создаёт отдельное приложение:
настройки переносятся вручную через JSON, разрешения выдаются заново.
[Инструкция по переходу](docs/package-migration.md). Файл настроек
перед импортом проходит ограничение размера, проверку схемы и предварительный
просмотр. Он не может предоставить системные разрешения.

По возможности проверь воспроизведение на текущем исходном коде и укажи,
если проблема относится только к старой сборке.

## Reporting an issue

The optional [AI-assisted setup kit](docs/ai-setup.md) runs local ADB commands.
Review its plan before approving changes. Keep plans and transaction journals
private: they contain device identifiers and previous settings. A matching hash
detects file changes but does not establish trust in an unknown publisher.
Use the kit and certificate fingerprint from this repository's verified release.
Restoration returns recorded package states and Home selection; it keeps the
installed APK and app data and does not reinstall an earlier version.

If an issue exposes personal data, launches an unexpected intent or bypasses
file validation, contact the repository owner privately first. Keep exploit
details and personal information out of public discussions.

Include Android and app versions, minimal reproduction steps, expected behavior
and the observed result. Use a small example without accounts, viewing history,
device addresses, tokens, private keys or personal settings exports.

Install APKs from a trusted source. Updates to an existing package require the
same signing key; uninstalling to bypass a signature mismatch erases local data.
Moving to `io.github.ekkzy.evebox` creates a separate app: settings move through
manual JSON export/import and permissions must be granted again.
[Migration guide](docs/package-migration.en.md). Settings imports
have a size limit, schema validation and a preview before applying. They cannot
grant Android permissions.

If possible, check the current source and mention whether the issue only
affects an older build.

## One-command Windows setup

The [Eve Box wizard](docs/one-command-setup-ru.md) uses the same local
Plan/Apply/Inspect/Restore backend with explicit device and app selection.
Its pinned release mode accepts only the exact pinned APK37 and
profile bytes. The client verifies their size/hash without Java or Build-Tools;
signature and package metadata must be checked before packaging. Download the
whole kit from a trusted source: APK pinning cannot authenticate a replaced script.

Confirmation is bound to the displayed plan or journal. Concurrent wizards for
the same target are blocked, and unresolved transactions require inspection.
Journal writes use atomic replacement; bounded retries require exact old/new
bytes and specific Windows file-lock errors. ADB mutations are not retried blindly.
Keep `setup-private` local and out of reports. Restoration preserves the installed
APK and app data and does not restore an earlier APK version.
Earlier plans and journals must be inspected or restored with the original kit;
the new package's tools reject them without modifying them. Keep unresolved
journals and establish their outcome before starting another operation.
