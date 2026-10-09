# Roadmap

Ground rule: **nothing that works today gets disturbed.** New work goes on branches. The current lock-screen Earth stays installed and untouched until a replacement is proven better.

## Next: "Sets" — an iPhone-style wallpaper and look system for the Pixel

The Earth app grows into one app for the phone's whole look. Earth becomes a *featured* wallpaper among others.

**Set** = a pair: **lock screen + home screen**. The two do not have to be the same picture; the pair *is* the set.

- **Sets are like skins.** Beyond the two wallpapers, a set can carry:
  - theme colour and style (Material You seed: tonal / vibrant / expressive…)
  - lock-screen clock style
  - dark/light
  - optionally phone behaviour: silence/DND, brightness, screen timeout…
- **Modes** (Travel, Sleep, Work, …) are each associated with a set. Turning a mode on (by hand, on a schedule, or by location) switches the whole look.
- **Wallpaper gallery:**
  - featured live wallpapers (Earth now, more later)
  - normal ones made on the device on the fly: own photos, photo shuffle, colours and gradients
- **Keep everything stock Pixel (Android 17) offers, plus the missing link between profiles, modes, lock, home, theme and colour.**

### How it works on Android (no root)
- Set the app **once** as the live wallpaper for both lock and home. It renders whatever the active set says. Switching is instant, with no "set wallpaper?" prompts. The unlock transition (lock image flowing into the home image) is drawn by the app.
- Theme colour: a live wallpaper reports `WallpaperColors`, and the system re-themes. An exact seed and style is possible through `Settings.Secure` after a one-time `adb pm grant … WRITE_SECURE_SETTINGS`.
- Clock face: `Settings.Secure` lock-screen clock setting, same grant.
- Modes: the app registers its own Android Modes (`AutomaticZenRule` with types such as bedtime and driving). They appear in the system Modes UI and quick settings, and the app switches sets when one turns on.

### Not possible for a third-party app
- Lock-screen corner shortcuts are stored inside SystemUI. Deep-link to Pixel's own picker instead.
- Pixel's depth effect (subject over clock) and 3D cinematic wallpapers are Pixel-only system features.

### Phases
1. Sets: lock+home pairs, gallery (Earth + photos + colours), unlock transition.
2. Theme colour + clock style per set.
3. Modes → sets, automatic switching.
4. Polish: photo shuffle, schedules, more live wallpapers.

Note: phase 1 replaces Magic Portrait on the home screen only when the user chooses to.

## Earth wallpaper — open items
- [ ] Confirm cloud motion is non-zero after the next real satellite update. Log line: `cloud motion estimated … mean X deg/h`.
- [ ] Back taps: confirm walking never triggers a zoom.
- [ ] Pixel Quick Tap is still on and fires on the same back double-tap; the user should turn it off in Settings → System → Gestures.
- [ ] Optional: faster live satellite source (NASA GIBS GOES-East/West + Himawari, ~40 min latency, free).
- [ ] Cleanup: remove unused `JoystickView`, `ZoomTapActivity`, the weather code in `ExploreActivity`, and the verbose knock logs.

## Web version (done)
- https://kckbytes.github.io/earth-web/ (public repo kckBytes/earth-web, served by GitHub Pages).
- The same `globe.frag`; `flow.js` is a port of `FlowEstimator`.
- The browser keeps the last two distinct cloud maps for motion.
- Controls match control mode. Cities can be added by name or by location.

## Other ideas on the list
- **Phone ↔ PC from anywhere:** Tailscale (free personal tier) as the private network; scrcpy (4.x virtual displays: each phone app in its own Windows window) for PC→phone; Windows OpenSSH for a phone→PC terminal that can open PC apps; notifications as Windows toasts with reply; shared clipboard; file drag-and-drop.
- **Link dashboard app:** give a list of links and each becomes a self-updating card.
  - Jobs: Greenhouse, Lever and Workday public feeds for chosen companies.
  - NCSU Moodle: the calendar export (iCal) link for deadlines, no login needed.
- **Ads / adware:**
  - Private DNS `dns.adguard-dns.com` for system-wide blocking.
  - Audit apps that hold "display over other apps" or run hidden in the background, to find adware.
- **Job-postings feed** (the original idea).
