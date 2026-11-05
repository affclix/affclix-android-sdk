# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.4] - 2026-11-05

### Fixed

- `app_version` (the app's `versionName`) is sent with every request. It was collected but never sent, so app-version validation rules and reports had nothing for Android
- In-app events carry `is_rooted` and `is_emulator`, as installs and conversions already did, so validation rules can check events too. The checks run once per process and do not touch the install-velocity counter

### Added

- Web + app journeys: the `af_web_id` of an App Link that opens the app is sent as `web_visitor_id` with the `deep_link` and `app_open` events, and with the install when the link opened the first launch. `DeepLinkResult.webVisitorId` exposes it

## [1.0.3] - 2026-11-03

### Documentation

- `trackingDomain`: leave it at the default, `mmp.affclix.org`. Your Affclix subdomain and custom tracking domains serve clicks and branded links, not SDK requests, so an app pointed at one of them could not report. The sample app no longer suggests a tracking domain. No behaviour change

## [1.0.2] - 2026-11-02

### Fixed

- A reinstall, or a restore onto a new phone, is reported as an install. Android Auto Backup could restore the SDK's first-launch flag, so the install was never sent; data from another install is now dropped on start
- A Google Play referrer with `utm_medium=organic`, which Play also sends for sideloads, no longer shows as media source `google` and non-organic
- `trackingDomain` defaults to `mmp.affclix.org`, which serves the SDK routes

## [1.0.1] - 2026-11-01

### Fixed

- Events, conversions and installs are saved before the first send and removed once the server answers, so a process killed during the retries no longer loses them
- A data deletion now drops events still waiting to be sent, so they cannot bring back erased data

## [1.0.0] - 2026-10-27

First public release.

- Install attribution: Google Play Install Referrer, deep-link `click_id`, advertising ID
- Deep links and deferred deep links through branded App Links
- In-app events, conversions, validated Google Play purchases, ad revenue
- Encrypted offline queue delivered by WorkManager; queued events are also sent on every start
- Click expiry: events stop crediting a click once `clickExpiryDays` has passed
- Request signing (HMAC-SHA256), encrypted on-device storage
- Consent: GDPR / CCPA data deletion, Google consent mode, IAB TCF v2
- Uninstall tracking through FCM, session events
- Client-side fraud signals
- R8 full-mode rule for WorkManager's Room database (fixes a startup crash in minified apps)

[1.0.3]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.0.3
[1.0.2]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.0.2
[1.0.1]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.0.1
[1.0.0]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.0.0
