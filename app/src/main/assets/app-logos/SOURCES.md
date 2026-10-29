# App artwork sources

The launcher matches exact installed package identities. A user-edited label
cannot select a different brand, and there are no runtime artwork downloads.

## Bundled artwork

The current catalog contains 18 independently sourced logo assets: Simple Icons
CC0 paths, the documented Commons PD-textlogo marks and SmartTube's MIT resource.
The source URLs, package evidence, transformations and hashes are recorded in
`catalog-sources.json`. Publisher trademarks remain with their respective owners.
The settings symbol is separately authored Eve Box artwork, not a publisher logo.

Only assets with applicable documented rights belong in the bundled source list.
A public publisher download by itself is not treated as an open redistribution
license. Simple Icons and SmartTube notices remain in `../licenses/`; the Commons
source references and public-domain status remain in the JSON source records.

## Installed-package artwork

YouTube, Disney+, TiviMate, Vimu, Send Files to TV, Иви, Okko, Wink, КИОН and START
have exact package identities but no bundled brand resource. Applicable standalone
asset rights were not established for this use. The former copied resources are
removed rather than renamed, re-exported or replaced by an imitation.

These entries read the installed activity/application banner or icon through
Android PackageManager. If the package is absent, an app tile uses the platform's
generic activity icon and a provider badge stays empty. Hiding/renaming/reordering
an app does not change this source boundary. `nativeFallbacks` in the JSON records
the decisions and source-review references; historical resource hashes and
removal evidence belong to the local replacement audit.

YouTube's direct review on 2026-10-25 checked its
[API branding guidelines](https://developers.google.com/youtube/terms/branding-guidelines)
and [brand resource site](https://brand.youtube/). The API-client guidelines are
not assumed to grant a bundled asset license to this non-API launcher.

## Rendering and verification

The host View supplies the neutral glass surface and accessible name; the
renderer adds no generic card or label. It fits occupied geometry into a common
envelope and draws once at destination size. Transparent pixels, source aspect
and source ownership are preserved.

`MonochromeArtwork` is an independent Eve Box helper used only for the retained
licensed bundled marks. Its documented brand colors and counters remain.
There are no source-specific masks for the removed publisher resources.

Native artwork takes a separate conservative path. An adaptive foreground is
already a publisher layer. Only a complete, opaque, near-uniform perimeter
permits removal of the connected outer field. Interior gray planes and existing
transparent counters are preserved. A gradient or photographic perimeter stays
intact. An isolated single-tone achromatic glyph can receive a contrast tint;
mixed artwork keeps its source RGB and alpha. When removing a field would make
an exposed dark/light wordmark disappear in the current palette, the intact
native banner is retained.

Bitmap dimensions, source decode sizes and temporary work are bounded.
`UiImageLoader` owns returned images, caching, cancellation and screen delivery.

Offline atlas tests cover the actually bundled licensed catalog. Native RGBA,
contrast, counters and immutable source pixels are checked with explicit
synthetic fixtures. The eight-app composition preview contains four licensed
marks and four native/generic identities; it does not claim to reproduce
uninstalled services' real TV artwork. Actual installed branding and HDMI
appearance require the later authorized hardware check.
