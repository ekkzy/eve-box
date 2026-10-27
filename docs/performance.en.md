# Resources and comparison with Google TV Home

[Русский](performance.md) · [Back to the project](../README.en.md) · [All observations as JSON](performance-data.json)

On October 27, 2026, the installed versions of softlumi and Google TV Home were
compared on one Xiaomi Mi Box S 3. Eight short observations covered an open
home screen without remote input. APK size, memory and CPU are reported separately.

## Warm idle memory and CPU

The table shows the median of four observations, with minimum and maximum in
parentheses. Memory and CPU cover every discovered process named for each
package: Google's main and `:coreservices` processes, and one softlumi process.

| Metric | Google TV Home | softlumi |
| --- | ---: | ---: |
| PSS, MiB | 121.70 (119.87–130.44) | 139.48 (139.39–140.58) |
| RSS, MiB | 259.47 (257.66–262.61) | 150.87 (150.76–151.93) |
| Process CPU, % of one core | 0.066 (0.000–0.230) | 0.033 (0.000–0.066) |

PSS apportions shared pages; RSS counts them in full for each process.
A lower RSS therefore does not mean that much physical RAM would be freed.
See the [Android definitions](https://developer.android.com/tools/dumpsys#meminfo).

Each pair's PSS difference, softlumi minus Google:

| Pair | Run order | Difference, MiB | Relative to Google |
| --- | --- | ---: | ---: |
| 1 | AB | 10.15 | 7.78% |
| 2 | BA | 17.93 | 14.75% |
| 3 | BA | 17.54 | 14.39% |
| 4 | AB | 19.60 | 16.35% |

The median paired difference is 17.74 MiB; the median relative difference
is 14.57%. Positive values mean more PSS for softlumi. This observation
applies to these screens and versions, not RAM guaranteed to be freed by changing Home.

CPU windows lasted 30.400–30.440 seconds, at 100 counter ticks/second.
Google accumulated 11 ticks and softlumi 4 across their four windows each.
These short windows do not describe average load in everyday use. Zero ticks
means no recorded increment in that window, not zero work or energy use.

Device-wide CPU, including unrelated processes, had a median of
4.59% with Google and 4.63% with softlumi,
normalized to aggregate CPU capacity. Ranges were 4.44–5.02%
and 4.44–4.82%. These values do not isolate the launcher
from other apps' background work.

## Installed APK size

| Application | Version | APK files | Combined size |
| --- | --- | ---: | ---: |
| Google TV Home | 1.0.994451607, code 831927 | 4: base and three splits | 23.54 MiB |
| softlumi | 0.3.0-candidate.12, code 33 | 1 | 44.85 MiB |

The exact sizes are 24,683,288 and 47,026,568 bytes respectively. Bundled
wallpapers occupy 46,275,588 bytes inside the softlumi APK, about 98.4% of
the file. They work offline but increase the package size. This excludes
app data, caches, optimized Android code and Google's immutable system copy;
it does not measure storage freed by changing launchers.

## Conditions and limits

- Xiaomi MiTV-AFMU0, Android 14/API 34, armeabi-v7a. Actual UI: 1920×1080,
  320 dpi, font scale 1.0. The README's 4K screenshots were captured separately
  on an emulator and are not measurement evidence.
- A is Google; B is softlumi. Run order: **ABBABAAB**, four pairs AB/BA/BA/AB.
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
  softlumi returned to Home, and the exact service list and sleep timers were restored.
- System animation scales stayed at 0.5. CPU frequency and power settings were not
  changed; dynamic frequency control and thermal state were not normalized.
  Isolated or unattributed helpers and the full compositor cost are not
  separately assigned. This is not a comparison of all system resources after
  removing either launcher.

Cold start, navigation, FPS, extended idle and energy use were not measured.
The summary JSON includes all eight observations without network addresses,
device identifiers, account information or viewing history. Raw screenshots
and logs from the personal device are not included in the repository.
