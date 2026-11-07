![Olauncher](https://repository-images.githubusercontent.com/278638069/db0acb80-661b-11eb-803e-926cae5dccb4)


# Olauncher | Minimal AF Launcher
AF stands for Ad-Free! :D

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
    alt="Get it on Play Store"
    height="80" align="middle">](https://play.google.com/store/apps/details?id=app.olauncher)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80" align="middle">](https://f-droid.org/packages/app.olauncher)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png"
    alt="Get it on IzzyOnDroid"
    height="54" hspace="13" align="middle">](https://apt.izzysoft.de/packages/app.olauncher)

#### Install using [Play Store,](https://play.google.com/store/apps/details?id=app.olauncher) [F-Droid,](https://f-droid.org/packages/app.olauncher) [IzzyOnDroid](https://apt.izzysoft.de/packages/app.olauncher) or the [latest APK.](https://github.com/tanujnotes/Olauncher/releases/)

To maintain the simplicity of the launcher, a few niche features are available but hidden.

Please check out the **[About](https://tanujnotes.substack.com/p/olauncher-minimal-af-launcher?utm_source=github)** page in the Olauncher settings for a complete list of features and **FAQs**.

##

License: [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html)

### ACES search pages and appearance

The home screen has no search bar; search remains available from the ACES menu.
Tap the circle to cycle through time, battery,
and date (for example, `Mon` / `05 Jan`); these displays do not open apps yet.
Hold the circle, drag, and release to place it. Its position is saved and adapts
to screen size and system insets, allowing placement across the home screen
while staying clear of system bars and display cutouts.
Swipe up on the circle to open a compact, single-row slider and Done button
above the navigation bar. Resize
from 56 to 280 dp with proportionally scaled readings, icon, padding, and glow.
The size is saved; dismiss the slider with Done, Back, or a tap outside.
The circle uses large time, weekday, and battery readings with smaller AM/PM,
date, and battery-icon details. While charging, a green halo pulses around the
circle on the visible home screen. With system animations disabled or on an
e-ink display, the charging halo stays steady instead.

Apps, Contacts, Search Engines, and Search Anything use a black page with a
pill-shaped search field, a fixed icon/title/results heading, and rounded charcoal
result tiles. Each result has a type label and a separate three-dot action menu.
Menus open above or below the selected tile within the visible area and scroll
when necessary. Contacts retain normal details taps, two-column quick actions,
long-press details, and command handling. Search engine aliases and ordering are
unchanged; engine menus link to the existing editor and management screen.

The three-line menu remains available at the right of the search field, with
page settings and the saved one/two-column selector. App Search Settings now
contains its appearance controls. Contacts and Search Engines settings have a
**Page appearance** entry; Search Anything settings opens its own appearance editor.
Appearance is also accessible directly from those pages' three-line menus.
There is no Global Appearance entry or global editor.

Each page independently saves its colors, transparency, popup/menu colors,
spacing, and results padding. Color controls retain the wheel, brightness slider,
hex input, and live preview. Colors apply to both column layouts within that page;
spacing and padding are saved separately for each layout. Editing the preview's
layout does not change the actual result column count. Existing saved appearance
values are retained as defaults until a page is customized; edits then affect only
that page. New installations default to the redesigned black/charcoal palette.
Existing text alignment and launcher font scaling preferences are preserved.

Android-resource tests cover all four pages in both layouts, fixed headings,
keyboard insets, dynamic counts and empty states, result menus, popup bounds,
appearance persistence/migration, and command regressions on API 24 and 28.
Run `./gradlew :app:testDebugUnitTest`; build with `./gradlew :app:assembleDebug`.

App-drawer regression tests also cover asynchronous list updates, hiding apps,
recycled rename controls, and normalized filtering on Android API 24 and 28.
Search reuses query normalization across each result set and cancels superseded
filtering work. App-list loading runs once on initial resume rather than twice
during startup; normal resume refreshes are retained.

Developer: [@tanujnotes](https://x.com/tanujnotes)

### Contact Search commands

Only **C — Contact Search** recognizes `p name` (dialer), `m name` (SMS),
`w name` (WhatsApp conversation), and `c name` (contact details). Commands are
case-insensitive and require a space after the letter. Normal searching, tile
layouts, long-press details, and existing shortcut buttons are unchanged.
The Contacts heading shows a compact command indicator beneath its title when enabled.

Type at least two name characters and a unique match opens after 250 ms by
default. Further typing, ambiguous results, loading contacts, composing text,
or leaving the page cancels automatic execution. A command is handled once
until the query changes, including across screen recreation and launcher returns.
Tap a contact in either layout to select it immediately; Enter executes only a
unique result. With automatic execution disabled, typing alone never launches.

**ACES → C - Contact search settings → Contact Commands** controls automatic
execution, remembered numbers, confirmation-protected clearing, the delay
(250/500/750 ms), minimum name characters (2/3/4), and the indicator.
Settings and separate Phone/SMS/WhatsApp number choices use existing preferences.
Numbers are validated against the current contact before use; Android's preferred
number is used unless a valid explicit choice is saved, otherwise multiple numbers
are offered with their labels. Contact records are never modified.
Phone opens the dialer without calling; SMS opens a composer without sending.
WhatsApp uses a direct conversation link, with an explicit contact-details fallback
when no installed WhatsApp app accepts it. WhatsApp itself validates account
registration after opening; Android does not report that outcome back to ACES.
WhatsApp commands require an international number (Android-normalized, `+`, or
`00` country-code format); otherwise ACES offers contact details rather than
guessing a country or silently opening a different action.

Focused command tests cover parser cases, safeguards, intents, both tile layouts,
phone selection and invalidation, merged/deleted contacts, permission and app
failures, persistent settings, cancellation, and recreation on Android API 24/28.
Run `./gradlew :app:testDebugUnitTest`.

Long-press **Home Settings** in the home quick-actions panel to open Android
system settings. A normal tap still opens launcher settings. In the Contact Search
popup, long-press **P**, **M**, **W**, or **C** to open the phone, default messaging,
WhatsApp, or contacts app without selecting a contact. Normal taps still insert
the corresponding search command.

### SMS/MMS group chats

The empty contact-search page offers **Allow SMS access for group chats**.
This permission is optional; declining it does not affect contact search.
SMS/MMS groups appear alongside contacts, with a group icon, a **Group Chat**
label, and no contact shortcut buttons or command actions. Names are built from
available contact names and participant addresses. Tapping opens the conversation
in the default messaging app (or a group composer if that app does not support
thread links). Group chats are also included in Search Anything.

Only SMS/MMS conversations exposed by Android's messaging provider are supported,
not private RCS, WhatsApp, or other app databases. Android or the installer may
restrict SMS permission; this launcher does not request the default-SMS role.
Message bodies are not read, stored, or sent anywhere.

### Battery-conscious updates

Search uses event-driven observers only while its page is visible, coalesces
contact/message change notifications, and loads database metadata off the UI
thread rather than per keystroke. App/package refresh bursts are coalesced and
deferred while the launcher is in the background; Contact Search reuses the
activity's package observer instead of performing duplicate scans. Existing
clock, battery, wallpaper, and search behavior remains enabled. Actual battery
savings depend on the device and require on-device measurement.

##

### Other apps from team Olauncher:

- [Pro Launcher](https://play.google.com/store/apps/details?id=app.prolauncher) - Pro version of Olauncher with extra features like widgets, weather, folders, etc.

- [Note to Self](https://play.google.com/store/apps/details?id=com.makenotetoself) - Free and [open source](https://github.com/jeerovan/ntsapp) notes app with chat like interface and end-to-end encryption.

- [Pentastic](https://play.google.com/store/apps/details?id=app.pentastic) - Free and [open source](https://github.com/tanujnotes/Pentastic). A minimal tasks, notes and reminders app.

- [Supernova](https://github.com/tanujnotes/Supernova) - Free and [open source](https://github.com/tanujnotes/Supernova) alternative of Nova Launcher.

##

Thank you!
