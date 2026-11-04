# PureGrid

PureGrid (`com.gathin.tvhome`) — лаунчер для Android TV и Google TV: главный экран без рекламных
строк, док из избранных приложений, сетка остальных, поиск, погода, темы — плюс инструменты
debloat, которые через ADB отключают встроенный «мусор» прошивки. Ничего не перепрошивается и не
удаляется: пакеты только отключаются, и всё возвращается одной командой.

Версия **2.3** (versionCode 4), Android 8.0+ (minSdk 26), targetSdk 34, лицензия MIT.

## Что умеет

- **Главный экран**: закреплённый док, алфавитная сетка приложений, один «стеклянный» хайлайт,
  который пружиной переезжает между плитками. Док редактируется с пульта: навигация влево от
  первой плитки открывает кнопки редактора, долгое OK берёт приложение и позволяет двигать его
  влево/вправо, плюс и корзина добавляют и убирают плитки.
- **Верхняя панель**: часы, приветствие и дата (опционально солнечная хиджра — Shamsi), погода,
  сетевая пилюля (Wi-Fi/Ethernet, «connected without access», VPN-бейдж и переключение VPN),
  входы, аккаунт Google, поиск и настройки.
- **Поиск**: сначала приложения, ниже — по одной полосе на каждое установленное приложение,
  которое экспортирует Leanback-провайдер глобального поиска (YouTube, Netflix, Kinopoisk и т. п.).
  Провайдер не ответил — приложение просто не попадает в контентные полосы.
- **Меню входов**: HDMI/AV, Live TV и запасные варианты для прошивок без `TvInputManager`
  (TCL, XGIMI и другие — см. комментарии в `config.xml`).
- **Карточки модулей**: стороннее приложение может объявить карточку со своим названием, подписью
  и цветом свечения; показывается не больше 8, решение владельца привязано к сертификату подписи.
  Контракт — [`docs/specs/card.md`](docs/specs/card.md), инструкция — [`docs/modules.md`](docs/modules.md).
- **Темы без пересборки**: `config.json` и необязательный `wallpaper.jpg` кладутся на телевизор,
  Home перезапускается и накладывает их поверх встроенного `config.xml`. Небо-градиент, которое
  следует за часами, включается ключом `sky_intensity` — [`docs/sky.md`](docs/sky.md).
- **Экранная заставка**: тонкие часы с датой на чёрном и медленным ambient-свечением.
- **Клавиши пульта**: опциональные `InputKeyService` и `HomeKeyService` возвращают кнопку Input и
  кнопку Home на пультах, которые шлют нестандартные коды (подробнее ниже).
- **Debloat**: списки пакетов по моделям, отключение для пользователя 0, откат тем же списком,
  проверка имён пакетов и защита от отключения `setupwraith`, пока он владеет сохранённой Wi-Fi-сетью.
  Есть версии скриптов для bash и PowerShell.
- **14 языков** (включая английский), без рекламы и телеметрии в самом лаунчере.

## Quick start

Нужны: телевизор и компьютер в одной Wi-Fi-сети, JDK 17+, Android SDK (проще всего поставить
Android Studio) и Python 3 для служебных скриптов.

1. **На телевизоре** — Settings → Device Preferences → About → семь раз OK на **Build** →
   назад → Developer options → **USB debugging** (на части прошивок — **Network/ADB debugging**).
   Телевизор держать включённым и подтвердить «Разрешить отладку?» при первом подключении.
2. **На компьютере** — создать venv для инструментов из корня репозитория:
   ```bash
   python3 -m venv tools/venv
   tools/venv/bin/pip install -r tools/requirements.txt
   ```
   На Windows:
   ```powershell
   python -m venv tools\venv
   tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
   ```
   Скрипты сами подхватывают `tools/venv/bin/python` или `tools/venv/Scripts/python.exe`.
3. **Собрать и установить** одной командой:
   ```bash
   TV_HOST=192.168.1.50 launcher/build-and-install.sh
   ```
   На Windows:
   ```powershell
   launcher\build-and-install.ps1 -TvHost 192.168.1.50
   ```
4. Нажать Home на пульте.

Проверить связь с телевизором можно без бинарника `adb`:

```bash
TV_HOST=192.168.1.50 tools/tv.py --probe
```

`tools/tv.py` — минимальный ADB-over-TCP клиент на `adb_shell`; он нужен всем скриптам в `tools/`
и умеет `--batch`, `--push` и `--pull`. Если `adb` уже настроен и доверен телевизору, `tv.py`
переиспользует ключ из `~/.android/adbkey`.

### Что делает установщик

- собирает release-APK (R8 + shrinkResources; подпись debug-ключом — это осознанно, APK ставится
  sideload'ом и в магазин не публикуется);
- ставит APK через `tools/b64push.py` и `tools/tv.py`;
- сразу назначает новый лаунчер Home и запускает его, проверяя `Status: ok`;
- затем выдаёт четыре разрешения (`ACCESS_FINE_LOCATION`, `WRITE_SECURE_SETTINGS`, `GET_ACCOUNTS`,
  `READ_CONTACTS`), компилирует пакет в режиме `speed`, отключает стоковый лаунчер и ещё раз
  проверяет, куда резолвится Home;
- если лаунчер не стартовал, стоковый вообще не трогается; если Home остался не за новым
  лаунчером — установщик возвращает стоковый обратно, а когда откат не подтвердился, пишет файл
  `puregrid-handover-<ip>.txt` с командами для ручного завершения;
- по желанию включает сервисы клавиш: `INPUT_KEY_SERVICE=1` и/или `HOME_KEY_SERVICE=1`
  (на Windows — `-InputKeyService` / `-HomeKeyService`).

Стоковый лаунчер установщик определяет сам среди известных пакетов
(`com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx`,
`com.google.android.leanbacklauncher`, `com.mitv.tvhome`). Если у модели свой лаунчер, передайте
`STOCK_LAUNCHER=<package>` (или `-StockLauncher <package>`); `STOCK_LAUNCHER=none` оставляет
стоковый включённым.

Если Home не переключился, чаще всего виноват `com.google.android.tungsten.setupwraith` на Google TV:
его нужно добавить в debloat-список и применить. Разбор — в
[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md).

### Погода

Погодный слайд работает и без ключа: без `yandex_weather_key` лаунчер обращается к Open-Meteo
(бесплатно, без регистрации). С ключом Яндекс.Погоды данные идут от Яндекса:

```bash
adb shell settings put secure yandex_weather_key <ключ>
# или без adb:
TV_HOST=192.168.1.50 tools/tv.py 'settings put secure yandex_weather_key <ключ>'
```

Без сети слайд не прячется, а показывает перечёркнутое облако и повторяет попытку раз в минуту.
Подробности, координаты по IP и отладочные флаги — в [`docs/sky.md`](docs/sky.md).

## Ручная установка

Если хочется пройти шаги руками (или подправить что-то по дороге):

1. `cd launcher && ./gradlew assembleRelease` — APK появится в
   `app/build/outputs/apk/release/app-release.apk`. Нужен `sdk.dir` в `launcher/local.properties`
   (образец — `launcher/local.properties.example`).
2. `adb connect <ip>` и `adb install -r app/build/outputs/apk/release/app-release.apk`.
   Без `adb`: `tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/tvhome.apk`,
   затем `tools/tv.py 'pm install -r /data/local/tmp/tvhome.apk'`.
3. Запустить и проверить, что лаунчер стартовал:
   `adb shell am start -W -n com.gathin.tvhome/.MainActivity`.
4. Выдать разрешения (нужны сети, настройкам, аккаунту и карточкам):
   ```bash
   adb shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
   adb shell pm grant com.gathin.tvhome android.permission.GET_ACCOUNTS
   adb shell pm grant com.gathin.tvhome android.permission.READ_CONTACTS
   ```
5. Назначить Home: `adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity`.
6. Убедиться, что новый лаунчер действительно работает и держит Home (шаги 3 и 5), и только
   потом отключить стоковый: `adb shell pm disable-user --user 0 <имя пакета стокового лаунчера>`.
   На Android TV это обычно `com.google.android.tvlauncher`, на новых Google TV —
   `com.google.android.apps.tv.launcherx`, на старых Sony — `com.google.android.leanbacklauncher`,
   на Xiaomi — `com.mitv.tvhome` (это же имя знает установщик). Отключать надо строго после
   проверки: на Google TV 14 порядок «сначала отключить, потом назначить» приводит к
   crash-loop'у мастера настройки (см. [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)).
   Откат: `adb shell pm enable --user 0 <пакет>`.

## Debloat: отключаем встроенный «мусор»

```bash
TV_HOST=<tv ip> tools/disable.sh examples/<model>/debloat.txt   # применить
TV_HOST=<tv ip> tools/restore.sh examples/<model>/debloat.txt   # откатить тем же списком
```

На Windows — `tools\disable.ps1 -TvHost <ip> examples\<model>\debloat.txt` и
`tools\restore.ps1 -TvHost <ip> examples\<model>\debloat.txt`.

Пакеты отключаются для пользователя 0 и никогда не удаляются. Списки валидируются перед
применением: принимаются только имена пакетов, комментарии и пустые строки. `disable.sh` также
делает `am force-stop` каждому отключённому пакету: `pm disable-user` выключает компоненты, но уже
запущенный процесс сам не умирает.

`restore.sh` возвращает Home стоковому лаунчеру, чьё имя по умолчанию —
`com.google.android.tvlauncher`; если сток у вашей модели другой (например, `com.mitv.tvhome`
у Xiaomi), передайте его вторым аргументом:
`TV_HOST=<tv ip> tools/restore.sh examples/<model>/debloat.txt com.mitv.tvhome`.
Он же сбрасывает три шкалы анимации в 1.0.

`disable.sh` не отключит `com.google.android.tungsten.setupwraith`, пока сохранённая Wi-Fi-сеть
принадлежит ему: Android удаляет такие сети вместе с пакетом, и телевизор теряет Wi-Fi вместе с
ADB. Обойти защиту можно только осознанно — `FORCE_SETUPWRAITH=1` (например, на Ethernet).
Подводные камни — сервис обновлений, setupwraith, клавиатура и прочее — разобраны в
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

### Списки по моделям

Сейчас в индексе 1 TV lists / 1 TV models — одна модель, один список. Формат папок описан в
[examples/README.md](examples/README.md).

| TV | OS | Folder |
|---|---|---|
| Xiaomi Mi TV 4A | Android TV 10 | [`examples/xiaomi-mi-tv-4a/`](examples/xiaomi-mi-tv-4a/) |

## Темы и небо

Тема — папка с `config.json` и необязательным `wallpaper.jpg`. Отдельного каталога `themes/` в
репозитории нет: подойдёт любая папка.

```bash
TV_HOST=<tv ip> tools/theme.sh path/to/theme     # применить
TV_HOST=<tv ip> tools/theme.sh --reset           # убрать тему
```

На Windows: `tools\theme.ps1 -TvHost <ip> path\to\theme` и `tools\theme.ps1 -TvHost <ip> -Reset`.

Ключи темы — те же, что закомментированы в
[`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
док, скрытые приложения, колонки, анимации, `reduced_motion`, `sky_intensity`, лимиты поиска и т. д.
Например, небо:

```json
{ "sky_intensity": 60 }
```

Пуш темы (и `--reset`) также очищает выборы, сделанные на телевизоре в настройках лаунчера, —
чтобы тема снова стала источником истины. Небо рисуется одним битмапом 480×270 раз в минуту и по
умолчанию выключено; подробнее — [`docs/sky.md`](docs/sky.md).

## Клавиши пульта

На части пультов кнопка Home шлёт `KEYCODE_ALL_APPS`, а кнопка Input — `KEYCODE_TV`. После
отключения стокового лаунчера эти кнопки может оказаться некому обрабатывать. Два опциональных
accessibility-сервиса возвращают поведение:

```bash
TV_HOST=<tv ip> INPUT_KEY_SERVICE=1 HOME_KEY_SERVICE=1 launcher/build-and-install.sh
```

```powershell
launcher\build-and-install.ps1 -TvHost <ip> -InputKeyService -HomeKeyService
```

Флаги нужно передавать при каждой переустановке: Android снимает accessibility-сервисы при
переустановке приложения. `InputKeyService` перехватывает только `KEYCODE_TV`, `HomeKeyService` —
только `KEYCODE_ALL_APPS` и только пока PureGrid владеет Home; остальные клавиши проходят
нетронутыми. Если кнопка всё ещё не работает — смотрите
[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md), раздел про включённый, но не привязанный
аксессуар-хелпер.

## Карточки модулей

Приложение для Android TV может объявить карточку: своё название, необязательную подпись и цвет
свечения. Лаунчер спрашивает владельца и запоминает ответ за сертификатом подписи; без ответа
«Показать» карточка не появляется. Показывается до 8 карточек, остальные остаются обычными
плитками. Список и переключатели — OK на «Настройках» → **Module cards**.

Начать: [`docs/modules.md`](docs/modules.md). Полный контракт:
[`docs/specs/card.md`](docs/specs/card.md).

## Экранная заставка

В системных настройках телевизора выберите заставку **Clock** либо назначьте её командой:

```bash
adb shell settings put secure screensaver_components com.gathin.tvhome/.ClockDream
```

Часы раз в минуту меняют положение и оттенок свечения, чтобы не выжигать матрицу.

## Язык

Лаунчер переведён на 14 языков: английский, немецкий, каталанский, испанский, французский,
русский, китайский (упрощённый), тайский, сербский (латиница), польский, арабский, иврит, турецкий
и португальский. На Android 13+ язык можно задать только для лаунчера:

```bash
adb shell cmd locale set-app-locales com.gathin.tvhome --user 0 --locales ru-RU
```

На более старых версиях лаунчер следует системному языку.

## Настройки лаунчера

OK на пилюле «Настройки» открывает меню: **System settings**, **Launcher settings** и
**Module cards**. В настройках лаунчера — акцент (авто или фиксированный цвет), 4–7 колонок,
анимации, приветствие, погода, видимость кнопок Clear/Search/Account, сброс раскладки и анимаций
к текущей теме и переход к выбору домашнего приложения.

## Структура репозитория

- `launcher/` — приложение (Gradle-модуль `app`, Java 17, `minSdk 26`, `targetSdk 34`),
  `build-and-install.sh` / `.ps1` — сборка и установка, `FONT-LICENSE-OFL.txt` — лицензия шрифта
  Outfit.
- `tools/` — ADB-обвязка: `tv.py` (шлюз к телевизору), `b64push.py` (большие файлы), `disable.sh` /
  `restore.sh` (debloat), `theme.sh` (темы без пересборки) и их PowerShell-версии `*.ps1`,
  `requirements.txt`.
- `examples/` — debloat-списки по моделям телевизоров.
- `tests/` — Python-тесты списков, скриптов и передачи Home:
  `python3 -m unittest discover -s tests -v`.
- `docs/` — [`TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md), [`REMOTE.md`](docs/REMOTE.md)
  (помощь телевизору в другом доме через Tailscale), [`modules.md`](docs/modules.md),
  [`sky.md`](docs/sky.md), [`specs/card.md`](docs/specs/card.md), `devices/` (дампы моделей).

## Сборка и тесты

```bash
cd launcher
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
./gradlew test              # 82 JVM-теста
```

На Windows путь к проекту не должен содержать не-ASCII символов: иначе `gradlew test` не может
загрузить тестовые классы (проблема кодировки arg-file в Gradle), и все тесты падают с
`ClassNotFoundException`. Запускайте из ASCII-пути или из IDE. Инструментальный тест (1 шт.) идёт
на самом телевизоре: `./gradlew connectedDebugAndroidTest`.

Python-набор — 25 тестов, один пропускается:

```bash
python3 -m unittest discover -s tests -v
PUREGRID_STRICT=1 python3 -m unittest discover -s tests -v   # + проверка счётчиков в README
```

## FAQ

**Как вернуть стоковый лаунчер?** Проще всего `tools/restore.sh` с тем же списком, что применяли:
он включает пакеты, возвращает Home и сбрасывает анимации. Без списка:

```bash
adb shell pm enable --user 0 <пакет стокового лаунчера>
adb shell cmd package set-home-activity --user 0 <пакет>/<activity>
```

**Почему кнопка Home или Input ничего не делает?** Скорее всего, не включён соответствующий
accessibility-сервис. Переустановите с `HOME_KEY_SERVICE=1` / `INPUT_KEY_SERVICE=1` (или
`-HomeKeyService` / `-InputKeyService`) и проверьте, что компонент не только `enabled`, но и
`bound` — см. [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md).

**Где настройки лаунчера?** OK на пилюле «Настройки» в верхней панели → **Launcher settings**.
Обычный клик туда же открывает меню, а не системные настройки; системные — первая строка меню.

**Как закрыть порт 5555 после отладки?** Открытый ADB-порт — это шелл на телевизоре для любого
устройства в локальной сети, поэтому закрывайте его, когда закончили:

1. Отключите беспроводную отладку на телевизоре: Settings → Developer options →
   **Network/ADB debugging** (или **Wireless debugging**) → выключить. Это закрывает порт
   сразу и навсегда (до следующего включения).
2. Если пункт в настройках недоступен, а порт надо закрыть с компьютера:
   `TV_HOST=<tv ip> tools/tv.py 'settings put global adb_enabled 0'` — включает ADB обратно
   только с самого телевизора (Settings → Device Preferences → About → OK на Build →
   Developer options), поэтому используйте этот путь с осторожностью.
3. На компьютере разорвите сессию: `adb disconnect` (для adb-клиента; инструменты `tools/`
   соединяются по требованию и держать сессию не должны).

Никогда не пробрасывайте порт 5555 за пределы локальной сети — см.
[docs/REMOTE.md](docs/REMOTE.md).

## Лицензия

См. [LICENSE](LICENSE) (MIT). Шрифт Outfit распространяется по SIL Open Font License — см.
[`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt).
