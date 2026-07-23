# Adbustr Android SDK

[![Version](https://img.shields.io/badge/version-0.1.0-blue)](CHANGELOG.md)
[![minSdk](https://img.shields.io/badge/minSdk-21-green)](#требования)
[![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)](LICENSE)

SDK для показа рекламы Adbustr в Android-приложениях и играх.
Работает как самостоятельно, так и внутри медиации **AppLovin MAX**.

| Формат | Поддержка |
|---|:---:|
| Rewarded video | ✅ |
| Interstitial (fullscreen image) | ✅ |
| Banner / MREC | ✅ |
| Native | ⛔ (в планах) |

**Без внешних зависимостей.** Сеть — `HttpURLConnection`, JSON — `org.json`,
видео — `VideoView`. SDK попадает в приложения, где уже есть свои OkHttp, Glide
и AndroidX, поэтому он не тянет ни одной сторонней библиотеки и не может
поломать вам версии.

---

## Содержание

- [Требования](#требования)
- [Установка](#установка)
- [Быстрый старт](#быстрый-старт)
- [Форматы](#форматы)
- [Обработка ошибок](#обработка-ошибок)
- [Подключение к AppLovin MAX](#подключение-к-applovin-max)
- [Маркировка рекламы](#маркировка-рекламы)
- [Разрешения и приватность](#разрешения-и-приватность)
- [ProGuard / R8](#proguard--r8)
- [Сборка из исходников](#сборка-из-исходников)
- [Протокол ad-сервера](#протокол-ad-сервера)

---

## Требования

| | |
|---|---|
| minSdk | 21 (Android 5.0) |
| compileSdk / targetSdk | 34 |
| Java | 8+ (desugaring не нужен) |
| Kotlin | не требуется, но совместим |

Для интеграции нужны три значения, которые выдаёт Adbustr при подключении:

| Параметр | Что это |
|---|---|
| **Base URL** | адрес ad-сервера, например `https://rtb.adbustr.com` |
| **API Key** | токен приложения, уходит в заголовке `X-Adbustr-Key` |
| **Site ID** | идентификатор площадки |

Плюс **zone id** на каждое рекламное место (меню, конец уровня и т.д.).

---

## Установка

### Вариант 1 — готовые AAR

Положите файлы в `app/libs/`:

```gradle
dependencies {
    implementation files('libs/adbustr-sdk-release.aar')

    // только если используете медиацию AppLovin MAX
    implementation files('libs/adbustr-applovin-adapter-release.aar')
}
```

### Вариант 2 — как модули проекта

```gradle
// settings.gradle
include ':adbustr-sdk'
project(':adbustr-sdk').projectDir = file('../adbustr-android-sdk/adbustr-sdk')
```

```gradle
// app/build.gradle
dependencies {
    implementation project(':adbustr-sdk')
}
```

Ничего больше делать не нужно: `Activity` для полноэкранных форматов и все
разрешения приезжают в ваш манифест через manifest merger.

---

## Быстрый старт

### 1. Инициализация

Один раз за запуск процесса, например в `Application.onCreate()`:

```java
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();

        AdbustrSdk.initialize(this, new AdbustrConfig.Builder()
                .baseUrl("https://rtb.adbustr.com")
                .apiKey(BuildConfig.ADBUSTR_API_KEY)
                .siteId("1234")
                .timeoutSeconds(2)      // необязательно, по умолчанию 2
                .testMode(BuildConfig.DEBUG)   // подробные логи с тегом AdbustrSdk
                .build());
    }
}
```

> **Не храните API-ключ в коде.** Пробросьте его через `buildConfigField` из
> `local.properties` или CI-переменной.

`testMode` влияет только на логирование — на запросы и показы он не влияет.

### 2. Rewarded video

```java
AdbustrSdk.loadRewarded(activity, "menu_reward", new AdLoadCallback<RewardedAd>() {
    @Override public void onAdLoaded(RewardedAd ad) {
        ad.setListener(new FullscreenAdListener.Adapter() {
            @Override public void onUserRewarded() {
                grantCoins(100);          // награда заработана
            }
            @Override public void onAdClosed() {
                resumeGame();
            }
        });
        ad.show(activity);
    }

    @Override public void onAdFailedToLoad(AdError error) {
        Log.w("Ads", "нет рекламы: " + error.getMessage());
        resumeGame();
    }
});
```

Награда выдаётся **только за досмотр до конца**. Если пользователь нажал
«Пропустить» — отправляется пиксель `skip`, `onUserRewarded()` не вызывается.

### 3. Interstitial

```java
AdbustrSdk.loadInterstitial(activity, "level_end", new AdLoadCallback<InterstitialAd>() {
    @Override public void onAdLoaded(InterstitialAd ad) {
        ad.setListener(new FullscreenAdListener.Adapter() {
            @Override public void onAdClosed() { startNextLevel(); }
        });
        ad.show(activity);
    }

    @Override public void onAdFailedToLoad(AdError error) {
        startNextLevel();
    }
});
```

Кнопка закрытия появляется через 3 секунды. До этого момента системная кнопка
«назад» тоже заблокирована — иначе показ закрывался бы бесплатно.

### 4. Banner / MREC

```java
final FrameLayout slot = findViewById(R.id.ad_slot);

AdbustrSdk.loadBanner(this, "main_menu_banner",
        slot.getWidth(), slot.getHeight(),
        new AdLoadCallback<BannerAd>() {
            @Override public void onAdLoaded(BannerAd ad) {
                banner = ad;
                slot.addView(ad.getView(MainActivity.this));
            }

            @Override public void onAdFailedToLoad(AdError error) {
                slot.setVisibility(View.GONE);
            }
        });
```

Не забудьте освободить баннер:

```java
@Override protected void onDestroy() {
    if (banner != null) banner.destroy();
    super.onDestroy();
}
```

Показ засчитывается не в момент загрузки, а когда `View` реально появилась
на экране.

---

## Форматы

### Жизненный цикл полноэкранной рекламы

```
load ──► onAdLoaded ──► show() ──► onAdShown
                                      │
                                      ├──► onAdClicked        (переход на лендинг)
                                      ├──► onUserRewarded     (только rewarded, досмотр)
                                      └──► onAdClosed         ← всегда последний
```

Гарантии, на которые можно опираться:

- `onAdShown` вызывается ровно один раз;
- `onAdClosed` вызывается ровно один раз и всегда последним;
- `onUserRewarded` — строго до `onAdClosed`;
- один объект рекламы показывается один раз; повторный `show()` вернёт
  `AdError.ALREADY_SHOWN`;
- все колбэки приходят в main-потоке;
- SDK не бросает исключений наружу — любая проблема приходит как `AdError`.

### Время жизни

У каждого объявления есть `ttl`, который приходит от сервера. Проверить:

```java
if (!ad.isExpired()) {
    ad.show(activity);
}
```

Показ протухшего объявления вернёт `AdError.EXPIRED`.

### Предзагрузка

Креатив (картинка или mp4) скачивается на этапе `load`, поэтому `show()`
рисует мгновенно, без буферизации. Грузите рекламу заранее — например, в начале
уровня, а показывайте в конце.

---

## Обработка ошибок

| `AdError` | Когда | Что делать |
|---|---|---|
| `NO_FILL` | сервер ответил `nobid` | нормальная ситуация, продолжить игру |
| `TIMEOUT` | запрос не уложился в таймаут | продолжить, попробовать позже |
| `NETWORK_ERROR` | сеть недоступна | продолжить |
| `INVALID_ZONE` | zone пустой или отклонён сервером | проверить zone id |
| `NOT_INITIALIZED` | `initialize()` не вызывался или конфиг неполный | проверить Base URL / API Key / Site ID |
| `CREATIVE_LOAD_FAILED` | креатив не скачался | продолжить |
| `PARSE_ERROR`, `INVALID_RESPONSE` | сервер вернул неожиданный ответ | сообщить в поддержку |
| `EXPIRED` | истёк `ttl` | запросить новое объявление |
| `ALREADY_SHOWN` | повторный `show()` | запросить новое объявление |
| `DISPLAY_FAILED` | не удалось отрисовать | проверить, что манифест смёржился |

У каждого значения есть `getMessage()` с коротким описанием.

---

## Подключение к AppLovin MAX

Adbustr подключается к MAX как **custom SDK network** — то есть становится
одной из строк waterfall наравне с другими сетями.

### Как это работает

```
Игрок открывает экран с рекламой
        │
        ▼
MAX SDK перебирает waterfall по убыванию eCPM
        │
        ▼  дошёл до строки Adbustr — вызывает адаптер
   AdbustrMediationAdapter
        │
        ▼  запрос на ad-сервер Adbustr
        │
        ├── есть реклама ──► onAdLoaded ──► MAX останавливает waterfall и показывает
        │
        └── нет рекламы ───► NO_FILL ─────► MAX идёт к следующей сети
```

### Шаг 1. Создать сеть в дашборде

**Manage ▸ Networks ▸ Create Custom Network**

| Поле | Значение |
|---|---|
| Network Type | `SDK` |
| Custom Network Name | `Adbustr` |
| Android / Fire OS Adapter Class Name | `com.applovin.mediation.adapters.AdbustrMediationAdapter` |
| iOS Adapter Class Name | оставить пустым — iOS пока не поддерживается |

Имя класса MAX резолвит через рефлексию, поэтому оно должно совпадать
посимвольно.

### Шаг 2. Настроить ad unit

Включите Adbustr на нужном ad unit и заполните:

**Custom Network Parameters**

```json
{
  "base_url": "https://rtb.adbustr.com",
  "api_key": "<ваш API key>",
  "sid": "<ваш site id>",
  "timeout_seconds": "2"
}
```

`base_url` и `timeout_seconds` необязательны — без них берутся значения по
умолчанию.

**Placement ID** = zone id в Adbustr.

**CPM** проставляется вручную по странам. Custom SDK network в MAX работает
только по waterfall: ставка определяет позицию в очереди, а ответ ad-сервера —
только наличие рекламы.

### Шаг 3. Подключить адаптер к приложению

```gradle
dependencies {
    implementation 'com.applovin:applovin-sdk:13.0.0'
    implementation files('libs/adbustr-sdk-release.aar')
    implementation files('libs/adbustr-applovin-adapter-release.aar')
}
```

Отдельная инициализация Adbustr не нужна — адаптер поднимает SDK сам из
параметров ad unit. Дальше вы просто пользуетесь обычным API MAX
(`MaxRewardedAd`, `MaxInterstitialAd`, `MaxAdView`).

### Шаг 4. Проверить

1. Поставьте на тестовом ad unit заведомо высокий CPM, чтобы Adbustr гарантированно
   оказался первой строкой waterfall.
2. Откройте **MAX Mediation Debugger** — Adbustr должен появиться в списке сетей
   с версией SDK и версией адаптера.
3. Запустите тестовый показ и посмотрите logcat по тегу `AdbustrSdk`.

### Частые проблемы

| Симптом | Причина |
|---|---|
| Сети нет в Mediation Debugger | имя класса в дашборде не совпадает с классом в приложении, либо адаптер не попал в сборку |
| Всегда `INVALID_CONFIGURATION` | не заполнены `api_key` или `sid` в параметрах ad unit |
| Всегда `NO_FILL` | нет спроса на эту зону/гео, либо неверный Placement ID |
| Загружается, но не показывается | `AdbustrAdActivity` не попала в итоговый манифест — проверьте merged manifest |

---

## Маркировка рекламы

Если ad-сервер вернул `ord.marked = 0`, SDK сам рисует поверх креатива бейдж
**«Реклама · {рекламодатель}»**. По тапу он разворачивается и показывает `erid`
и ИНН. Тап по бейджу не засчитывается как клик по рекламе.

Убирать или перекрывать бейдж нельзя — это требование закона «О рекламе».

---

## Разрешения и приватность

SDK добавляет в манифест:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="com.google.android.gms.permission.AD_ID" />
```

Что уходит на сервер в запросе рекламы:

- **устройство** — производитель, модель, версия Android, разрешение экрана,
  язык, тип соединения, уровень заряда;
- **реклама** — advertising id (GAID) и флаг Limit Ad Tracking;
- **приложение** — package name, название, версия;
- **пользователь** — анонимный идентификатор, сгенерированный SDK и хранящийся
  в `SharedPreferences`, плюс id сессии.

Advertising id читается рефлексией: если приложение подключает
`play-services-ads-identifier`, придёт настоящий GAID, если нет — пустая строка,
и запрос всё равно уйдёт. Зависимости на Google Play Services у SDK нет.

При заполнении **Data safety** в Google Play учитывайте GAID и сетевые запросы.

---

## ProGuard / R8

Правила поставляются вместе с AAR (`consumer-rules.pro`) и применяются
автоматически. Отдельно ничего добавлять не нужно.

---

## Сборка из исходников

```bash
git clone https://github.com/adbustr/adbustr-android-sdk.git
cd adbustr-android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Артефакты:

```
adbustr-sdk/build/outputs/aar/adbustr-sdk-release.aar
adbustr-applovin-adapter/build/outputs/aar/adbustr-applovin-adapter-release.aar
```

Требуется JDK 17. Адаптер собирается против `com.applovin:applovin-sdk:13.0.0`
в режиме `compileOnly` — в приложение он не попадает, MAX SDK туда приносит
хост-приложение.

### Структура

```
adbustr-sdk/                     ядро SDK
  com.adbustr.sdk/
    AdbustrSdk                   точка входа
    AdbustrConfig, AdError
    core/                        сеть, парсинг, устройство, трекинг, кеш креативов
    ads/                         Interstitial, Rewarded, Banner
    ui/                          полноэкранная Activity, бейдж маркировки

adbustr-applovin-adapter/        адаптер AppLovin MAX
  com.applovin.mediation.adapters.AdbustrMediationAdapter
```

---

## Протокол ad-сервера

`POST {base_url}/sdk/v1/ad`, заголовок `X-Adbustr-Key: <api key>`.

<details>
<summary>Запрос</summary>

```json
{
  "api": 1,
  "sid": "1234",
  "zone": "menu_reward",
  "format": "rewarded",
  "w": 1080,
  "h": 1920,
  "app":     { "bundle": "com.example.game", "name": "Game", "ver": "1.4.0", "sdk": "0.1.0" },
  "device":  { "ifa": "…", "lmt": 0, "os": "android", "osv": "14",
               "make": "Samsung", "model": "SM-S911B", "w": 1080, "h": 1920,
               "lang": "ru", "con": 2, "battery": 87 },
  "user":    { "uid": "…" },
  "session": { "id": "…", "depth": 3 }
}
```

`con` — тип соединения: `0` неизвестно, `2` wi-fi/ethernet, `3` мобильный.

</details>

<details>
<summary>Ответ</summary>

```json
{
  "status": "ok",
  "req_id": "…",
  "format": "rewarded",
  "ttl": 3600,
  "video": { "url": "…", "w": 1080, "h": 1920,
             "duration": 30, "skip_after": 5, "click_url": "…" },
  "tracking": {
    "imp": ["…"], "click": ["…"], "start": ["…"],
    "q1": ["…"], "mid": ["…"], "q3": ["…"],
    "complete": ["…"], "skip": ["…"], "reward": ["…"]
  },
  "ord": { "erid": "…", "advertiser": "…", "inn": "…", "marked": 0 }
}
```

`status: "nobid"` — рекламы нет, SDK отдаёт `AdError.NO_FILL`.
Вместо `video` может прийти `image` (interstitial, banner) или `native`.

</details>

---

## Версионирование

[SemVer](https://semver.org/lang/ru/). До `1.0.0` публичный API может меняться —
см. [CHANGELOG.md](CHANGELOG.md).

Версия адаптера — `<версия SDK>.<ревизия адаптера>`, например `0.1.0.0`:
так правка в адаптере не выглядит как релиз SDK.

## Поддержка

Вопросы по интеграции и доступам — через менеджера Adbustr.
Баги и предложения — в [issues](https://github.com/adbustr/adbustr-android-sdk/issues).

## Лицензия

[Apache License 2.0](LICENSE) © Adbustr
