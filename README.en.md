# softlumi

A calm home screen for Android TV.

Favorite apps, things to keep watching, a clock and the weather share one
remote-friendly screen. Translucent panels, soft lighting and a visible focus
state keep navigation clear while leaving room for your content.

[Русский](README.md) · [Installation](docs/installation.en.md) · [Build](docs/building.en.md)

![The softlumi home screen with landscape cards](docs/images/home-landscape.png)

Screenshots show the real interface with demonstration cards, collections and
weather. They do not show personal viewing history or promise availability
of specific integrations.

## What's inside

- **Your apps, within reach.** Up to eight favorites, custom ordering, hidden
  apps and a separate app catalog. Logos keep their colors, with accessible
  names available to assistive technology.
- **Two card formats.** Wide cards with large artwork or portrait posters.
  Changing the format keeps your favorites and appearance settings.
- **Continue watching and recommendations.** Personal Android Watch Next cards
  stay separate from recommendations. Recommendations have one switch per app
  in settings and one app tab on the home screen.
- **Appearance through the day.** Twenty background choices with day and night
  states, light and dark palettes, and adjustable dimming. Choose the period
  automatically, by schedule or manually.
- **Weather for your chosen city.** Current conditions, upcoming hours and a
  three-day forecast. Weather is optional and needs no location permission.

| Posters | Appearance | Weather |
| --- | --- | --- |
| ![The home screen with portrait posters](docs/images/home-poster.png) | ![Background and appearance settings](docs/images/appearance.png) | ![Weather and forecast for the selected city](docs/images/weather.png) |

## Getting started

The project is currently available as source, without a public APK release.
You can [build a debug APK](docs/building.en.md) and
[install it on Android TV](docs/installation.en.md). Installing the app does not
make it the default Home or switch remote-control services; those are separate
choices for the device owner.

Android TV 8.0 or later and a D-pad remote are required. The primary development
device is Xiaomi Mi Box S 3. TV data access and default Home selection depend
on the firmware. Live blur is available on Android 12 and later; earlier
versions use a translucent material.

## A few things to know

Viewing cards depend on installed apps publishing accessible Android TV data.
softlumi does not connect to streaming accounts or promise a complete viewing
history or exact resume for every video. Source apps are selected explicitly;
private or unavailable publications are not bypassed.

There are no ads, analytics or project-operated servers. Settings stay on the
device and can be exported manually as JSON. Imports show a preview before
applying any changes. Weather and poster loading contact their respective
external sources; see
[privacy](docs/privacy.en.md).

## For developers

Java 17 and native Android Views, without Compose or a third-party UI framework.
The [build guide](docs/building.en.md) covers the toolchain and checks.
See [CONTRIBUTING.md](CONTRIBUTING.md) for change and verification guidelines.
See [SECURITY.md](SECURITY.md) for security reporting.

Code is licensed under [MIT](LICENSE). Fonts, logos, weather data and images
have separate terms listed in [third-party notices](THIRD_PARTY_NOTICES.md).
The historical [Free My TV attribution](docs/licenses/FreeMyTV-MIT.txt) is retained.
