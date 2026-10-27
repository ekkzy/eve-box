# Security

## Сообщить о проблеме

Если проблема позволяет прочитать личные данные, выполнить неожиданный intent
или обойти проверку файла, сначала сообщи владельцу репозитория приватно.
Не помещай подробности эксплуатации и личные данные в публичное обсуждение.

Достаточно версии Android и приложения, коротких шагов воспроизведения,
ожидаемого и фактического поведения. Приложи минимальный пример без аккаунтов,
истории просмотра, адресов устройств, токенов, приватных ключей и личных JSON-файлов.

Устанавливай APK из доверенного источника. Обновление требует того же ключа
подписи; удаление приложения ради обхода ошибки стирает данные. Файл настроек
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

Install APKs from a trusted source. Updates require the same signing key;
uninstalling to bypass a signature mismatch erases local data. Settings imports
have a size limit, schema validation and a preview before applying. They cannot
grant Android permissions.

If possible, check the current source and mention whether the issue only
affects an older build.
