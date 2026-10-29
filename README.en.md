# Eve Box

**An Android TV launcher with a customizable home screen.**

Favorite apps, continue-watching cards and weather share one screen. The
interface is designed for a remote: choose a card layout, manage recommendations
and set up automatic switching between daytime and nighttime appearance.

[Download](https://github.com/ekkzy/eve-box/releases/tag/v0.3.0-candidate.16-setup.1) · [Installation](#installation) · [Русский](README.md) · [Build](docs/building.en.md)

![Eve Box promotional artwork](docs/brand/promo/eve-box-promo.png)

## About the project

Eve Box started with a wish to make my own TV box easier to use and more
responsive. I wasn't satisfied with the stock interface's speed. After
disabling apps I didn't need, I began building a launcher around Russian
streaming services and my everyday viewing habits.

The project is developed with help from AI and tested on my Xiaomi Mi Box S 3.
Before the first GitHub publication, I used it daily for a week while refining
the interface, navigation and memory use. Suggestions and bug reports are welcome.

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

![The Eve Box home screen with landscape cards](docs/images/home-landscape.png)

These screenshots show the real interface with demonstration cards and weather,
without personal viewing history. Available publications depend on installed apps.
Below are dark appearance, the wallpaper gallery and settings operated with a remote.
Open any image to view it at full resolution.

| Dark appearance and posters | Main settings |
| --- | --- |
| ![The dark home screen with portrait posters](docs/images/home-poster.png) | ![The main Eve Box settings menu](docs/images/settings.png) |

| Wallpaper gallery | Card format, palette and dimming |
| --- | --- |
| ![The wallpaper gallery](docs/images/wallpapers.png) | ![Background and appearance settings](docs/images/appearance.png) |

| Recommendations by app | Day and night settings |
| --- | --- |
| ![Recommendation switches for individual apps](docs/images/sources.png) | ![Day and night settings](docs/images/day-night.png) |

![Weather and forecast for the selected city](docs/images/weather.png)

## Installation

On Windows, download the [Eve Box setup kit](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.16-setup.1/Eve-Box-setup-candidate.16.zip).
Use the same kit yourself or with an AI assistant. You need Windows and
[ADB](https://developer.android.com/tools/releases/platform-tools).
Java, Android Studio and SDK Build-Tools are not required.

1. Extract the ZIP and enable debugging on your TV box.
2. Open a terminal in the extracted folder and run:

```powershell
.\install.cmd
```

The wizard helps connect to your TV box, choose optional actions and review
the plan before installation. [Detailed guide in Russian](docs/one-command-setup-ru.md).

**With AI:** open the same folder in an assistant with a local terminal and
send the [short setup prompt](docs/ai-setup.md#ready-to-copy-prompt).
It helps prepare ADB and run the same wizard. You choose the device and apps
and approve the plan. [Three steps with AI](docs/ai-setup.md).

**APK only:** [download the app](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.16-setup.1/Eve-Box-0.3.0-candidate.16.apk),
transfer it to your TV and open it in a file manager. A PC is optional.
[APK installation and the manual ADB command](docs/installation.en.md).

Home selection and app disabling in the wizard require the exact supported
Mi Box S 3 firmware. Other devices get APK installation only. App data is
preserved; keep the journal to restore recorded Home and package changes.
Remote services and sleep timers are preserved.

This is a prerelease: **Eve Box 0.3.0-candidate.16**, code37.
The app package is `io.github.ekkzy.evebox`. It installs separately from the
previous package. Export settings from the old app, then open the JSON in the
new app and review the import. Home and accessibility need to be configured
again. The old app is not removed automatically.
[Moving from earlier releases](docs/package-migration.en.md).
Download hashes are in `SHA256SUMS.txt`.
[Verification and limitations, in Russian](docs/ai-setup-validation.md).

Android TV 8.0 or later and a D-pad remote are required. The primary development
device is Xiaomi Mi Box S 3. TV data access and default Home selection depend
on the firmware. Live blur is available on Android 12 and later; earlier
versions use a translucent material.

## A few things to know

Viewing cards depend on installed apps publishing accessible Android TV data.
Eve Box does not connect to streaming accounts or promise a complete viewing
history or exact resume for every video. Source apps are selected explicitly;
private or unavailable publications are not bypassed.

There are no ads, analytics or project-operated servers. Settings stay on the
device and can be exported manually as JSON. Imports show a preview before
applying any changes. Weather and poster loading contact their respective
external sources; see
[privacy](docs/privacy.en.md).

## Resources

The comparison below describes `candidate.12`, released under the previous
name softlumi, before the memory changes in
`candidate.13`; it does not describe the new APK in the setup kit.
Four pairs of short idle observations were collected on a Xiaomi Mi Box S 3.
Median resident PSS excluding swapped-out pages was **96.5 MiB** for Eve Box
and **121.3 MiB** for Google TV Home. Swap was **43.0 and 0.4 MiB** separately.
The earlier 139.5/121.7 MiB values are Android's `TOTAL PSS`, which includes swap;
they are not entirely resident RAM. Median process CPU was 0.033% and
0.066% of one core respectively. The Eve Box APK is larger:
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

[Eve Box logos and brand kit](docs/brand/README-RU.md) · [Download brand kit ZIP](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.16-setup.1/EVE-BOX-brand-kit.zip).
