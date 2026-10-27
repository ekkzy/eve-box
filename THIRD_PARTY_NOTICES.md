# Third-party notices

softlumi code is distributed under the [MIT License](LICENSE). Fonts, publisher
marks and external data retain their own licenses and rights.

## Project history

Early softlumi revisions incorporated selected implementations from
[Free My TV](https://github.com/simbasjungles/free-my-tv). The main transferred
components were later rewritten or removed. The original
[MIT notice](docs/licenses/FreeMyTV-MIT.txt), Copyright (c) 2026 Free My TV
contributors, is retained as a record of the project's history. Free My TV is
not a bundled runtime library of the current application.

## Fonts

| Font | License and source | Use |
| --- | --- | --- |
| Inter Variable | [Inter Project / Rasmus Andersson, v4.1](https://github.com/rsms/inter/releases/tag/v4.1), SIL Open Font License 1.1. [Full license](app/src/main/assets/fonts/OFL.txt), [source record](app/src/main/assets/fonts/SOURCE.json). | Interface typography. No Apple SF font is bundled. |
| Outfit Variable | Copyright 2021 The Outfit Project Authors, SIL Open Font License 1.1. [Author project](https://github.com/Outfitio/Outfit-Fonts), [full license](app/src/main/assets/fonts/OUTFIT-OFL.txt), [source record](app/src/main/assets/fonts/OUTFIT-SOURCE.json). | Home clock, weight 400. The unmodified font was obtained from the MIT-licensed Talkies repository; the font itself is OFL-licensed. No Talkies application code is included. |

Android supplies fallback fonts; those are not redistributed by this repository.

## App artwork

The bundled catalog contains 18 independently sourced publisher assets.
Exact sources, transformations and applicable licenses are recorded in
[the artwork manifest](app/src/main/assets/app-logos/catalog-sources.json)
and [source notes](app/src/main/assets/app-logos/SOURCES.md).

- Simple Icons paths are CC0-1.0. The
  [full CC0 text](app/src/main/assets/licenses/Simple-Icons-CC0.txt) is included.
- The SmartTube icon is MIT-licensed, with its
  [copyright and license](app/src/main/assets/licenses/SmartTube-MIT.txt) retained.
- VK Video and RUTUBE geometric marks use the Wikimedia Commons sources and
  public-domain textlogo descriptions identified in the manifest.
- Other publisher artwork is read from the installed app's own Android
  resources. Movie posters are supplied by Android TV providers and are not
  bundled with softlumi.

Software and artwork licenses do not grant trademark rights or imply
affiliation with the named services. The softlumi settings symbol, launcher
icon and native Canvas/vector backgrounds are original project artwork.

## Background images

The app includes **36 lossless WebP backgrounds**, generated specifically
for softlumi. Thirteen daylight variants were edited from their corresponding
night scenes. The gallery has twenty choices, including the native calm
background and content ambience. There is no bundled screensaver, DreamService,
video catalog or scene-layer collection.

The [asset notes](docs/assets.md) describe their origin and current packaging.
Generated images are distributed as project assets to the extent rights are
available; no global uniqueness, documentary accuracy or third-party trademark
rights are claimed. Their inclusion does not change the separate licenses
of fonts and publisher marks.

## Weather data

| Source | Terms |
| --- | --- |
| [Open-Meteo](https://open-meteo.com/) | [CC BY 4.0 data license](https://open-meteo.com/en/licence); API access terms apply separately. |
| [MET Norway](https://api.met.no/) | [CC BY 4.0 data policy](https://api.met.no/doc/License) and the service's API terms. Used as a forecast fallback. |
| [GeoNames](https://www.geonames.org/) via Open-Meteo Geocoding | City names and coordinates returned during city search; attribution is shown in the app. |

## Platform and development tools

Android APIs come from the Android Open Source Project under Apache-2.0 and
component-specific licenses; platform binaries are not bundled. Gradle Wrapper
is Apache-2.0. JUnit 4 is EPL-1.0, AndroidX Test is Apache-2.0 and JSON-java
is public domain; these dependencies are used for development/tests.
No third-party production UI or networking library is bundled.
