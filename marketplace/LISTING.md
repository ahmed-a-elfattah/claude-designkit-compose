# JetBrains Marketplace listing: Claude DesignKit for Compose

## Upload
- File: `plugin/build/distributions/plugin-1.0.0-signed.zip` (signed; rebuild with `./gradlew :plugin:signPlugin`)
- Plugin ID: `com.abdelfattah.ahmed.designimporter` (permanent)
- Name: Claude DesignKit for Compose
- Version: 1.0.0
- Compatibility: Android Studio 2026.2+ (build 262+), requires the Android plugin
- License: Apache-2.0 (`https://www.apache.org/licenses/LICENSE-2.0`)
- Channel: Stable

## Tags
Android · Jetpack Compose · Design System · UI · Code Generation · Localization

## Short description (overview card)
Import a Claude Design export into Jetpack Compose: theme, Material icons without duplicates, components and English/Arabic strings.

## Full description
Taken from `plugin.xml` automatically on upload.

## Screenshots (in this order, 1280×800)
1. `screenshot-1.png`: overview with the generated components in light, dark and Arabic
2. `screenshot-2.png`: generated theme with usage notes as KDoc, and Material icon references
3. `screenshot-3.png`: strings merged into values/strings.xml and values-ar/strings.xml

Still worth adding: a screenshot of the import dialog (Tools › Import Claude Design System…) from your own Android Studio.

## Notes for review
- "Claude" is a trademark of Anthropic. The description states the plugin is independent and not affiliated with or endorsed by Anthropic. If review asks for a rename, change only the name; the ID stays.
- Third-party notices: `NOTICE` (Gson, Material icon geometry index; both Apache-2.0).
