# Contributing

Keep changes focused on the behavior they improve. A useful change explains
the problem, the resulting behavior and how it was checked.

## Development

Use Java 17, native Android Views and the repository's Gradle Wrapper.
See [the build guide](docs/building.en.md) for setup and tasks. Discuss a large
framework migration or new dependency before expanding the implementation.

Keep network requests, provider queries, disk access and image decoding off
the UI thread. Bound caches and retries, cancel unnecessary work, and reject
stale results. Settings, source-card identity and D-pad focus need to survive
ordinary refreshes and returns from other apps.

## Verification

- Documentation changes need accurate text and working links.
- Logic changes need relevant tests and the build/lint checks affected by them.
  Add a regression test when it can reproduce a meaningful failure.
- Home visuals need both card formats, visible focus, D-pad/Back behavior and
  relevant loading, empty and error states. Use a dedicated Android TV emulator
  with an actual 3840 × 2160 app window and font scale 1.0 for the visual baseline.
- Device integration needs an explicitly selected test device and preserved
  initial settings. Do not change Home, accessibility services or sleep settings
  as a side effect of an app update. The production package is
  `io.github.ekkzy.evebox`; debug uses `io.github.ekkzy.evebox.debug`.
  Package migration must keep the previous app installed, support a reviewed
  JSON settings import, and require explicit Home and accessibility choices.

Record what actually ran. Compilation is not a device test, and a 4K HDMI
signal does not establish the app window's resolution. Performance claims need
comparable workloads and conditions. Live glass must remain transparent and
responsive to its source; do not replace it with a baked background image.

## Privacy and resources

Use synthetic fixtures for personal viewing data. Do not commit signing keys,
device identifiers, settings exports, account data or personal screenshots.
New assets need a documented source and redistribution terms; retain existing
copyright and license notices. See [privacy](docs/privacy.en.md),
[security](SECURITY.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
