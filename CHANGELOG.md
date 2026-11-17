# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-21

First release.

- Install attribution: Google Play Install Referrer (with organic and sideload detection), deep-link `click_id`, advertising ID
- Install attribution result: `AffclixCallbacks.onAttribution` and `AffclixSDK.getInstallAttribution()` give how the MMP matched the install (`click_id`, `install_referrer`, `device_id`, `probabilistic`, `view_through`, `ad_network`, `organic`) and the click
- Deep links and deferred deep links through branded App Links; web + app journeys (`af_web_id` → `DeepLinkResult.webVisitorId`)
- In-app events, conversions, validated Google Play purchases
- Impression-level ad revenue: `logAdRevenue(AdRevenue(...))` with mediation platform, network, ad unit, format, placement and precision (AppLovin MAX, LevelPlay, AdMob)
- Short links (`/l/{code}`) resolved through the MMP, and matched deferred deep links for installs without a link of their own
- Google Play Integrity (opt in, `playIntegrityCloudProjectNumber`): the install carries a standard-request token bound to an MMP challenge, waiting at most `integrityTimeoutMs`; the Play Integrity library is the app's own dependency (minSdk 23)
- Durable delivery: every request is saved before the first send and removed once the MMP answers; encrypted offline queue delivered by WorkManager and on every start
- Refusals (`{"success":false,"code":…}`: app key, signature, account access) reach `onError` with the code; a refused install is retried on the next launch
- Reinstall and backup-restore detection: a restore onto a new phone is reported as an install
- Click expiry (`clickExpiryDays`), request signing (HMAC-SHA256), encrypted on-device storage
- Consent: GDPR / CCPA data deletion, Google consent mode, IAB TCF v2
- Uninstall tracking through FCM, session events
- Device signals for fraud and validation rules: `app_version`, `is_rooted`, `is_emulator` on every request
- `trackingDomain` defaults to `mmp.affclix.org`; leave it
- R8 full-mode rule for WorkManager's Room database

[1.1.0]: https://github.com/affclix/affclix-android-sdk/releases/tag/1.1.0
