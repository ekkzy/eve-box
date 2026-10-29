# Moving to the new Eve Box package

[Русский](package-migration.md) · [Installation](installation.en.md)

Starting with `0.3.0-candidate.16`, Eve Box uses `io.github.ekkzy.evebox`.
Earlier releases, including candidate.15 with the Eve Box display name, used
`io.github.ekkzy.softlumi`. Android treats these as separate apps: installing
the new APK does not update the old app or access its data.

1. Export settings from the old app to JSON and keep the file locally.
2. Install the new APK and open the new app from the installation screen or
   wizard. Both versions may appear as Eve Box.
3. Open the saved JSON in the new app, review the changes and confirm the import.
   Check your favorites, app order, background and selected city.
4. Select recommendation sources and grant the required Android permissions
   again. For remote buttons, disable the old accessibility service in Android,
   then enable the new app's service and select the button actions in Eve Box.
5. To use the new Eve Box with the Home button, select it in Android settings.
   On supported Mi Box S 3 firmware, you can also choose this explicitly in the
   [setup wizard](one-command-setup-ru.md). Check Home, Back, app launches and
   focus when returning.

JSON transfers supported settings, not a complete app backup. Viewing history,
weather cache, enabled recommendation sources, Android permissions and remote
button actions are excluded. Access to a selected screensaver video must also
be granted again. Import shows a preview and requires confirmation. Treat the
settings file as personal data.

The old app and its data stay in place. The installer does not uninstall it
or enable accessibility for you. Keep a way back to the previous home screen
until you have checked the new version. Removing the old app is a separate
manual step after checking the transfer; it deletes that app's local data.

Keep the previous setup kit with its journals: inspect and restore old operations
using the kit that created them. The new wizard does not accept earlier plans
or journals. Resolve any unfinished old operation before a new installation;
do not delete its journal to bypass the check.

Future updates to `io.github.ekkzy.evebox` signed with the same key install over
the new app. Changing the key or clearing data is not an installation repair.
