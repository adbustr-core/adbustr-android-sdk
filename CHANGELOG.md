# Changelog

Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/),
версионирование — [SemVer](https://semver.org/lang/ru/).

## [0.1.0]

Первый публичный релиз.

### Добавлено

- **Adbustr Android SDK** — нативное ядро без внешних зависимостей:
  - форматы: rewarded video, fullscreen interstitial, banner / MREC;
  - предзагрузка креатива на этапе `load`, поэтому `show()` рисует мгновенно;
  - трекинг: `imp`, `click`, `start`, квартили, `complete`, `skip`, `reward`;
    критичные события отправляются с ретраями;
  - маркировка рекламы по 38-ФЗ: бейдж «Реклама · рекламодатель» с раскрытием
    erid и ИНН, когда сервер вернул `ord.marked = 0`;
  - advertising id читается рефлексией — зависимости на Google Play Services нет.
- **Адаптер AppLovin MAX** (`com.applovin.mediation.adapters.AdbustrMediationAdapter`)
  — подключение Adbustr как custom SDK network: interstitial, rewarded,
  banner / MREC.

### Известные ограничения

- iOS не поддерживается.
- Bidding недоступен: custom SDK network в MAX работает только по waterfall
  с фиксированным CPM.
- Native-формат парсится из ответа сервера, но рендерера для него нет.
- Автотестов нет.

[0.1.0]: https://github.com/adbustr/adbustr-android-sdk/releases/tag/v0.1.0
