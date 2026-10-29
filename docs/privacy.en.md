# Privacy

[Русский](privacy.md) · [Home](../README.en.md)

Eve Box has no accounts, ads, analytics, automatic crash uploads or
project-operated server.

## Data on the device

Favorites, app ordering and custom labels, appearance, the selected weather
city and enabled recommendation apps are stored in app-private preferences.
Weather is cached locally. TV cards and their artwork are cached in memory.
Android system backup is disabled in the manifest.

Continue watching and recommendations are read from the Android TV provider
when permission is available. Eve Box does not write provider data or upload
cards to a server. Recommendations are off by default: you select source apps,
and Home loads publications for the active tab. This does not connect accounts
or access a service's private viewing history.

Turning a feature off stops new queries without revoking Android permission.
Permission can be revoked in Android settings. Known loss of access clears
the recommendation cache before a result is shown.

## Network requests

- City search sends entered text to Open-Meteo Geocoding, which uses GeoNames
  data. Forecast requests send the chosen city's coordinates to Open-Meteo.
  MET Norway may be used when the primary service fails. These services see
  the client IP address. No GPS or Android location permission is used.
- A TV card may supply an HTTPS poster URL. Loading that artwork contacts the
  host selected by its source app; that host sees the IP address and URL.
- Opening a card passes a validated intent to an installed app. That app's
  further behavior follows its own policies.

Weather is enabled by choosing a city and can be turned off in settings.
There is no Eve Box background or logo download catalog: backgrounds ship
with the app; logos use the licensed bundled set or installed-app resources.

## Export and optional access

A manual JSON export can include favorites, custom labels and the selected
city. Treat it as a personal file. Viewing history, cards, poster URLs,
weather cache, enabled recommendation apps, Android permissions and remote
button settings are excluded. Import has a 64 KiB limit and requires explicit
confirmation after a preview.

The optional button-remapping service receives key events when the user
enables it in Android. It handles only two supported Xiaomi button codes,
does not request screen content, record input or send it over the network.
Home works without this service.

Clearing Eve Box's data or uninstalling it removes its local settings and
cache. It does not delete another app's history. Delete deliberately exported
JSON files separately.

Weather sources: [Open-Meteo](https://open-meteo.com/),
[GeoNames](https://www.geonames.org/), [MET Norway](https://api.met.no/).
See [third-party notices](../THIRD_PARTY_NOTICES.md) for licenses.
