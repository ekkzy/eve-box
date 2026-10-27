# Installation

[Русский](installation.md) · [Home](../README.en.md)

Use an Android TV 8.0+ television or box with a D-pad remote. Phones and touch
input are not supported. Default Home selection and access to viewing cards
depend on the firmware.

## APK

Members of the private repository can download a signed APK in the
[setup kit](ai-setup.md). There is no public stable release yet.
You can also [build the app](building.en.md). Do not install a build of unknown origin.

1. Transfer the APK to your device and open it in a file manager.
2. If needed, allow installation from that source in Android settings.
3. Install it and open softlumi from the app list.

An update needs the same package and signing key as the installed app. Do not
uninstall the app to work around a signature mismatch: uninstalling deletes
local settings. Debug builds use a separate `.debug` package and can coexist
with the main app.

## First launch

Add favorite apps, then choose a card format and background. Weather and app
recommendations are off by default. Choose a city for weather; enable
recommendations and select the apps you want. TV card access uses a separate
Android permission where the firmware makes it available.

Installing an APK does not change Home, remote-control services or sleep
settings. To use softlumi as Home, select it in Android's default-app settings
if that option is available. Keep a way back to the previous Home. Some firmware
restricts Home selection; the [setup kit](ai-setup.md) has an experimental mode
for one Mi Box S 3 firmware. Do not transfer its app-disabling profile to other models.

Remapping the two supported Xiaomi remote buttons is optional. It requires
both the softlumi switch and manual enablement in Android Accessibility
settings. The launcher itself does not need accessibility access.

## Moving settings

Export a JSON file from softlumi settings to a location you choose. Import
shows a preview before applying changes. The file may include favorites,
custom app labels and your selected city; review it before sharing.
Enabled recommendation apps and Android permissions are not transferred.
Choose those apps again on the destination device.
