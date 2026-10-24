# PureGrid

Лаунчер для Android TV / Google TV: чёрный главный экран без рекламных строк, док из избранных
приложений, сетка остальных, поиск, погода, темы — плюс инструменты debloat, которые через ADB
отключают встроенный «мусор» прошивки. Ничего не перепрошивается и не удаляется: пакеты только
отключаются, и всё возвращается одной командой.

## Quick start

Нужны: телевизор и компьютер в одной Wi-Fi-сети, JDK 17, Android SDK и Python 3 для
служебных скриптов.

1. **На телевизоре** — Settings → Device Preferences → About → семь раз OK на **Build** →
   назад → Developer options → **USB debugging** (или **Network/ADB debugging**). TV держать
   включённым и подтвердить «Разрешить отладку?» при первом подключении.
2. **На компьютере** — создать venv для инструментов из корня репозитория:
   `python3 -m venv tools/venv` и `tools/venv/bin/pip install -r tools/requirements.txt`
   (на Windows venv кладёт исполняемые файлы в `tools/venv/Scripts/`, скрипты сами это
   подхватывают).
3. **Собрать и установить** одной командой:
   `TV_HOST=<ip телевизора> launcher/build-and-install.sh`
   (на Windows — `launcher\build-and-install.ps1 -TvHost <ip телевизора>`). Скрипт собирает
   release-APK, ставит его, выдаёт разрешения, запускает лаунчер и только потом переключает
   Home; если переключение не удалось — откатывает стоковый лаунчер обратно.

Нажмите Home на пульте. Погода с Яндекс.Погодой включается ключом:
`adb shell settings put secure yandex_weather_key <ключ>` — подробнее в
[docs/sky.md](docs/sky.md).

## Doing it by hand

Если хочется пройти шаги руками (или подправить что-то по дороге):

1. `cd launcher && ./gradlew assembleRelease` — APK появится в
   `app/build/outputs/apk/release/app-release.apk` (нужен `sdk.dir` в `launcher/local.properties`).
2. `adb connect <ip>` и `adb install -r app/build/outputs/apk/release/app-release.apk`.
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
6. После того как новый Home проверен, можно отключить стоковый лаунчер:
   `adb shell pm disable-user --user 0 <имя пакета стокового лаунчера>` — на Android TV это
   обычно `com.google.android.tvlauncher`, на новых Google TV — `com.google.android.apps.tv.launcherx`.
   Откат: `adb shell pm enable --user 0 <пакет>`.

## Debloat: отключаем встроенный «мусор»

```bash
TV_HOST=<tv ip> tools/disable.sh examples/<model>/debloat.txt   # применить
TV_HOST=<tv ip> tools/restore.sh examples/<model>/debloat.txt   # откатить тем же списком
```

Пакеты отключаются для пользователя 0 и никогда не удаляются. Списки валидируются перед
применением: принимаются только имена пакетов, комментарии и пустые строки. Подводные камни —
сервис обновлений, setupwraith, которому принадлежит сохранённая Wi-Fi-сеть, и прочее —
разобраны в [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

### Списки по моделям

Сейчас в индексе 1 TV lists / 1 TV models. Формат папок описан в
[examples/README.md](examples/README.md).

| TV | OS | Folder |
|---|---|---|
| Xiaomi Mi TV 4A | Android TV 10 | [`examples/xiaomi-mi-tv-4a/`](examples/xiaomi-mi-tv-4a/) |

## Структура репозитория

- `launcher/` — приложение (Gradle-модуль `app`), `launcher/build-and-install.sh` / `.ps1` — сборка и установка.
- `tools/` — ADB-обвязка: `tv.py` (шлюз к телевизору), `disable.sh` / `restore.sh` (debloat), `theme.sh` (темы без пересборки).
- `examples/` — debloat-списки по моделям телевизоров.
- `tests/` — Python-тесты списков и скриптов: `python3 -m unittest discover -s tests`.
- `docs/` — troubleshooting, работа пульта, темы и небо, спецификация карточек.

## Лицензия

См. [LICENSE](LICENSE).
