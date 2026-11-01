# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

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

[1.0.1]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.0.1
[1.0.0]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.0.0
