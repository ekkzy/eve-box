# Resources and comparison with Google TV Home

[Русский](performance.md) · [Back to the project](../README.en.md) · [All observations as JSON](performance-data.json)

On October 27, 2026, the installed versions of Eve Box and Google TV Home were
compared on one Xiaomi Mi Box S 3. Eight short observations covered an open
home screen without remote input. APK size, memory and CPU are reported separately.

The measured release was `0.3.0-candidate.12`, code 33, then named softlumi.
The text below uses the current name Eve Box; the original names and values
in JSON are preserved. These results do not describe `candidate.16` or the new app package.

**Memory accounting correction:** the initial description labelled `TOTAL PSS`
as PSS without separating swap. Android 14 includes swapped-out pages in that
total. They are separated below using the same saved observations; no new
device run or application change was made.

## Warm idle memory and CPU

The table shows the median of four observations, with minimum and maximum in
parentheses. Memory and CPU cover every discovered process named for each
package: Google's main and `:coreservices` processes, and one Eve Box process.

| Metric | Google TV Home | Eve Box |
| --- | ---: | ---: |
| Android TOTAL PSS, including SwapPss, MiB | 121.70 (119.87–130.44) | 139.48 (139.39–140.58) |
| Resident PSS: TOTAL PSS − SwapPss, MiB | 121.33 (119.51–129.91) | 96.54 (96.43–97.60) |
| SwapPss, logical swapped-out pages, MiB | 0.37 (0.37–0.52) | 42.96 (42.93–42.98) |
| RSS, MiB | 259.47 (257.66–262.61) | 150.87 (150.76–151.93) |
| Process CPU, % of one core | 0.066 (0.000–0.230) | 0.033 (0.000–0.066) |

In the [Android 14 source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/os/Debug.java),
`getTotalPss()` adds resident categories and `getTotalSwappedOutPss()`.
The detailed rows of all 12 process dumps confirm that accounting.
PSS apportions shared pages; RSS counts them fully for each process. SwapPss
does not measure the physical cost of storing compressed pages; that cost was
not measured separately. Neither subtracting swap nor comparing RSS proves
an overall device RAM saving. The two apps had substantially different swap states.

The original JSON's `activePss*`, `pssBMinusA*` and relative differences retain
the observed **TOTAL PSS including swap**. They were not renamed or rewritten
afterward. Additional observations for independent recalculation:

| Block | App | TOTAL PSS, KiB | SwapPss, KiB | Resident PSS, KiB |
| --- | --- | ---: | ---: | ---: |
| 1 | Google | 133568 | 537 | 133031 |
| 2 | Eve Box | 143958 | 44014 | 99944 |
| 3 | Eve Box | 142834 | 43994 | 98840 |
| 4 | Google | 124470 | 375 | 124095 |
| 5 | Eve Box | 142734 | 43986 | 98748 |
| 6 | Google | 124774 | 375 | 124399 |
| 7 | Google | 122749 | 375 | 122374 |
| 8 | Eve Box | 142822 | 43958 | 98864 |

Each pair's **TOTAL PSS including swap** difference, Eve Box minus Google:

| Pair | Run order | Difference, MiB | Relative to Google |
| --- | --- | ---: | ---: |
| 1 | AB | 10.15 | 7.78% |
| 2 | BA | 17.93 | 14.75% |
| 3 | BA | 17.54 | 14.39% |
| 4 | AB | 19.60 | 16.35% |

The median paired difference is 17.74 MiB; the median relative difference
is 14.57%. Positive values mean a larger **total including swap**, not a larger
resident PSS. The earlier conclusion that Eve Box uses more RAM based on this
total alone was too broad. Overall device RAM savings from changing launchers
are not established by this experiment.

### What occupies memory in Eve Box

In the last block, resident PSS was 96.55 MiB: about 68.0 MiB Graphics,
15.9 MiB Native Heap in App Summary, 4.3 MiB Java Heap and 8.3 MiB other
categories. SwapPss was a separate 42.9 MiB. App Summary's System category
also contains swap; it cannot be attributed entirely to resident system
libraries. Graphics values are reported by the driver.

An open Home retains decoded images and GPU layers for reuse even without
remote input. The glass implementation caches blur layers to reduce repeated
GPU work at a memory cost. Each layer's contribution to the 68 MiB was not
measured. The bitmap cache is limited to 12 MiB, but visible ImageViews can
retain images after cache eviction. Wallpapers are not all loaded at once;
the active background is bounded to 1920×1080 ARGB8888, about 7.9 MiB of pixels.
Graphics layers are the first profiling target; neither a leak nor a specific
amount of recoverable memory has been established.

CPU windows lasted 30.400–30.440 seconds, at 100 counter ticks/second.
Google accumulated 11 ticks and Eve Box 4 across their four windows each.
These short windows do not describe average load in everyday use. Zero ticks
means no recorded increment in that window, not zero work or energy use.

Device-wide CPU, including unrelated processes, had a median of
4.59% with Google and 4.63% with Eve Box,
normalized to aggregate CPU capacity. Ranges were 4.44–5.02%
and 4.44–4.82%. These values do not isolate the launcher
from other apps' background work.

## Installed APK size

| Application | Version | APK files | Combined size |
| --- | --- | ---: | ---: |
| Google TV Home | 1.0.994451607, code 831927 | 4: base and three splits | 23.54 MiB |
| Eve Box | 0.3.0-candidate.12, code 33 | 1 | 44.85 MiB |

The exact sizes are 24,683,288 and 47,026,568 bytes respectively. Bundled
wallpapers occupy 46,275,588 bytes inside the Eve Box APK, about 98.4% of
the file. They work offline but increase the package size. This excludes
app data, caches, optimized Android code and Google's immutable system copy;
it does not measure storage freed by changing launchers.

## Conditions and limits

- Xiaomi MiTV-AFMU0, Android 14/API 34, armeabi-v7a. Actual UI: 1920×1080,
  320 dpi, font scale 1.0. The README's 4K screenshots were captured separately
  on an emulator and are not measurement evidence.
- A is Google; B is Eve Box. Run order: **ABBABAAB**, four pairs AB/BA/BA/AB.
  Each interval followed a direct Home launch and 15 seconds of settling.
  All 8/8 cases completed; no selection of faster results. Memory was sampled
  after each CPU window through individual PID queries, with process identities
  and complete coverage checked before and after. Both packages remained enabled.
- These are different real home screens. Some Google provider covers remained
  unloaded. Content, network work, other apps and CPU placement were not normalized;
  no CPU/GPU traces establish causation.
- Enabling Google temporarily changes the system Home on this firmware. A legacy
  service redirecting Home to another launcher was paused in both configurations;
  the remote-key service stayed enabled. Afterward, Google was disabled again,
  Eve Box returned to Home, and the exact service list and sleep timers were restored.
- System animation scales stayed at 0.5. CPU frequency and power settings were not
  changed; dynamic frequency control and thermal state were not normalized.
  Isolated or unattributed helpers and the full compositor cost are not
  separately assigned. This is not a comparison of all system resources after
  removing either launcher.

Cold start, navigation, FPS, extended idle and energy use were not measured.
The summary JSON includes all eight observations without network addresses,
device identifiers, account information or viewing history. Raw screenshots
and logs from the personal device are not included in the repository.
