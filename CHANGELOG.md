# Changelog

Format — [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning — [SemVer](https://semver.org/).

## [0.2.0]

### Added

- **HTML creatives** for interstitial and banner / MREC: the demand partner's
  `adm` runs as-is in a WebView, so the partner's own impression pixels,
  verification scripts (DoubleVerify etc.) and JS click trackers fire.
  - a click is a navigation within 3 s of a user tap and opens in the external
    browser; navigations without a tap (auto-redirects) are blocked;
  - `intent://` links with `browser_fallback_url` are supported;
  - the creative is fitted to the slot: banners only scale down, fullscreen
    scales to fill the screen.
- **Three kinds of rewarded**, whichever the auction returns:
  - *video* — VAST mp4, as before;
  - *display* — an HTML creative;
  - *playable* — an HTML5 game on MRAID.

  For display/playable the reward is granted after `reward_after` seconds on
  screen (15 by default; time in the background doesn't count). Until then
  the ad can't be dismissed: no close button, Back is blocked,
  `mraid.close()` is ignored.
- **MRAID 3.0** container for all HTML creatives: `mraid.js`, the `ready`,
  `stateChange`, `viewableChange`, `exposureChange` and `sizeChange` events,
  `open`/`close` calls; `expand`/`resize` report an MRAID error.
- Requests carry `caps: ["html", "mraid"]`: the server sends the `html` block
  and offers demand a multi-format rewarded slot (video + MRAID banner,
  `rwdd: 1`) only to clients that declare it. Older SDKs get the previous
  response.

### Changed

- The slot size (`w`/`h`) in the ad request is now sent in dp, as OpenRTB
  expects; `device.w`/`device.h` stay in physical pixels. A 320×50 banner no
  longer goes out as 960×150 on a 3x screen.
- The «Реклама» badge is drawn only when the ad carries an `erid`. Ads that
  aren't registered with the ad-marking operator (e.g. foreign demand on
  non-RU traffic) no longer get an empty «Реклама · » label.

## [0.1.0]

First public release.

### Added

- Native core with no external dependencies:
  - formats: rewarded video, fullscreen interstitial, banner / MREC;
  - creatives are preloaded during `load`, so `show()` renders instantly;
  - tracking: `imp`, `click`, `start`, quartiles, `complete`, `skip`,
    `reward`; critical events are retried;
  - Russian ad marking (38-FZ): the «Реклама · advertiser» badge expanding to
    erid and INN when the server returns `ord.marked = 0`;
  - the advertising id is read via reflection — no dependency on Google Play
    Services.

### Known limitations

- iOS is not supported.
- The native format is parsed from the server response, but there is no
  renderer for it yet.
- No automated tests.

[0.2.0]: https://github.com/adbustr-core/adbustr-android-sdk/releases/tag/v0.2.0
[0.1.0]: https://github.com/adbustr-core/adbustr-android-sdk/releases/tag/v0.1.0
