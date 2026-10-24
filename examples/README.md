# Примеры debloat-списков

Одна папка на модель телевизора, в каждой — `debloat.txt`: список пакетов, которые безопасно
отключать на этой прошивке, сгруппированный и с комментариями. Это отправные точки, а не
догма — всё, чем вы пользуетесь, закомментируйте перед применением.

Применить и откатить:

```bash
TV_HOST=<tv ip> tools/disable.sh examples/<model>/debloat.txt
TV_HOST=<tv ip> tools/restore.sh examples/<model>/debloat.txt
```

Всё отключается (`pm disable-user --user 0`), ничего не удаляется и не перепрошивается; откат —
тем же списком через `tools/restore.sh`. Что ещё делают эти скрипты и какие есть подводные
камни, описано в [docs/TROUBLESHOOTING.md](../docs/TROUBLESHOOTING.md).

| Folder | TV | OS | Notes |
|---|---|---|---|
| `xiaomi-mi-tv-4a/` | Xiaomi Mi TV 4A | Android TV 10 | Телеметрия Xiaomi, PatchWall и контентные строки, фоновые сервисы Xiaomi, предустановленные приложения; отключается и стоковый Google-лаунчер, который заменяет PureGrid. Хороший старт и для других Xiaomi/Redmi с PatchWall. Про firmware, где клавиатура пропадает из-за несуществующего IME, — комментарий в начале списка |
