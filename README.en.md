# softlumi

A calm home screen for Android TV.

Favorite apps, things to keep watching, a clock and the weather share one
remote-friendly screen. Translucent panels, soft lighting and a visible focus
state keep navigation clear while leaving room for your content.

[Русский](README.md) · [AI-assisted setup](docs/ai-setup.md) · [Build](docs/building.en.md)

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

## Interface and settings

The home screen above uses Amber arch at night. Below are its light daytime
pair, both card formats and settings operated with the remote. Open any image
to view it at full resolution.

| Light appearance and posters | Main settings |
| --- | --- |
| ![The home screen with portrait posters](docs/images/home-poster.png) | ![The main softlumi settings menu](docs/images/settings.png) |

| Day and night wallpaper pairs | Card format, palette and dimming |
| --- | --- |
| ![The paired wallpaper gallery](docs/images/wallpapers.png) | ![Background and appearance settings](docs/images/appearance.png) |

| Recommendations by app | Day and night schedule |
| --- | --- |
| ![Recommendation switches for individual apps](docs/images/sources.png) | ![Day and night schedule settings](docs/images/day-night.png) |

![Weather and forecast for the selected city](docs/images/weather.png)

## Getting started

**[Download the Windows setup kit](https://github.com/ekkzy/softlumi/releases/tag/v0.3.0-candidate.13-setup.1)**
contains the signed `0.3.0-candidate.13` APK, scripts and an AI prompt in one ZIP.
This is a prerelease in a private repository; downloading requires access.

Extract it and give the [setup prompt](docs/installation-prompt-ru.md) to an
assistant with local terminal access. It helps connect the device and presents
a plan. Installation, Home selection and app disabling are explicit choices in
that plan; the script records previous settings for restoration.
See the [step-by-step guide](docs/ai-setup.md).

The default only installs the launcher. The optional app-disabling profile is
limited to one Mi Box S 3 firmware and remains experimental: the complete
transition has not yet been checked on a fresh physical device. Netflix, Prime
Video and other optional apps are disabled only when selected by their owner.
The installer does not change remote services or sleep timeouts.

You can also [build a debug APK](docs/building.en.md) and
[install the app manually](docs/installation.en.md).

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

## Resources

The comparison below describes `candidate.12`, before the memory changes in
`candidate.13`; it does not describe the new APK in the setup kit.
Four pairs of short idle observations were collected on a Xiaomi Mi Box S 3.
Median resident PSS excluding swapped-out pages was **96.5 MiB** for softlumi
and **121.3 MiB** for Google TV Home. Swap was **43.0 and 0.4 MiB** separately.
The earlier 139.5/121.7 MiB values are Android's `TOTAL PSS`, which includes swap;
they are not entirely resident RAM. Median process CPU was 0.033% and
0.066% of one core respectively. The softlumi APK is larger:
44.85 versus 23.54 MiB, almost all of it bundled wallpapers.
These observations do not establish overall resource savings.
[Results, versions and limits](docs/performance.en.md).

## For developers

Java 17 and native Android Views, without Compose or a third-party UI framework.
The [build guide](docs/building.en.md) covers the toolchain and checks.
See [CONTRIBUTING.md](CONTRIBUTING.md) for change and verification guidelines.
See [SECURITY.md](SECURITY.md) for security reporting.

Code is licensed under [MIT](LICENSE). Fonts, logos, weather data and images
have separate terms listed in [third-party notices](THIRD_PARTY_NOTICES.md).
