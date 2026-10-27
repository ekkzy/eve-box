# Assets and provenance

## Backgrounds

softlumi packages 36 generated background images as lossless WebP resources
in `app/src/main/res/drawable-nodpi`. They were created for this project in
October 2026 using OpenAI's built-in image generation tool. They depict
fictional landscapes and abstract architectural forms.

Thirteen daylight variants were made by editing their matching night image,
with instructions to preserve the camera and composition while changing the
lighting. Generative edits are not a guarantee of identical fine geometry
or texture. No clock, interface text, publisher logo or watermark was requested
or baked into these backgrounds.

The gallery presents eighteen image pairs, a native calm background and
content ambience. There are no screensaver assets or video clips.
The images are roughly 1672 × 941 pixels and scale to the display; they are
not native 4K textures. All current background files are fully opaque.

The [image manifest](image-assets.json) identifies all 36 packaged files,
their dimensions, original PNG hashes and current WebP hashes. It also records
the generation identifiers of the thirteen daylight edits. The selected PNGs
and their WebP copies were compared as complete decoded RGBA data; encoding
did not resize or recolor them. This is an image-preservation check, not
evidence of Android memory use, decode speed or frame rate.

Generated assets are distributed with softlumi to the extent rights are
available. This is not a claim of global uniqueness or a license to any
third-party identity that might be depicted. Fonts and publisher marks have
their own terms below.

## Fonts and publisher marks

Inter and Outfit retain their SIL Open Font License files and source records
in [the font directory](../app/src/main/assets/fonts). Bundled app marks have
their own [source manifest](../app/src/main/assets/app-logos/catalog-sources.json)
and [source notes](../app/src/main/assets/app-logos/SOURCES.md). Other app artwork
comes from the installed publisher package. Provider posters are not bundled.

The native calm backgrounds, settings symbol and launcher icon are original
softlumi artwork. See [third-party notices](../THIRD_PARTY_NOTICES.md) and
[the project license](../LICENSE) before redistributing.
