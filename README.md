# Ejudge для CLion

![Скачиваний](https://img.shields.io/github/downloads/vmaiorov-collab/ejudge-clion/total?label=%D1%81%D0%BA%D0%B0%D1%87%D0%B8%D0%B2%D0%B0%D0%BD%D0%B8%D0%B9)

Плагин для CLion: импорт контестов с ejudge.algocode.ru (Яндекс Кружок), условия сбоку, запуск на примерах и на своём вводе, отправка решений и вердикты прямо в IDE.

## Установка

Нужен CLion 2026.2 или новее (сборка 262+).

1. Скачайте `ejudge-clion-0.1.0.zip` со страницы [Releases](https://github.com/vmaiorov-collab/ejudge-clion/releases/latest). Распаковывать не нужно.
2. В CLion откройте **Settings → Plugins**, нажмите ⚙ → **Install Plugin from Disk…** и выберите zip.
3. Нажмите **OK** и перезапустите CLion.
4. Откройте **Settings → Tools → Ejudge**, введите логин и пароль от ejudge. Папка для контестов по умолчанию `~/ejudge-contests`.
5. Справа откроется панель **Ejudge**: нажмите «Скачать контесты», выберите параллель — скачаются все её контесты. Для автодополнения откройте папку `~/ejudge-contests` как проект в CLion.

### Установка одной командой (macOS)

Закройте CLion и выполните в терминале:

```
curl -L -o /tmp/ejudge-clion.zip https://github.com/vmaiorov-collab/ejudge-clion/releases/latest/download/ejudge-clion-0.1.0.zip \
  && unzip -o /tmp/ejudge-clion.zip -d "$HOME/Library/Application Support/JetBrains/CLion2026.2/plugins"
```

Затем запустите CLion. Если у вас другая версия CLion, замените `CLion2026.2` в пути на свою папку из `~/Library/Application Support/JetBrains/`.

### Установка одной командой (Linux)

Закройте CLion и выполните в терминале:

```
curl -L -o /tmp/ejudge-clion.zip https://github.com/vmaiorov-collab/ejudge-clion/releases/latest/download/ejudge-clion-0.1.0.zip \
  && unzip -o /tmp/ejudge-clion.zip -d "$HOME/.local/share/JetBrains/CLion2026.2/plugins"
```

### Установка одной командой (Windows, PowerShell)

Закройте CLion и выполните в PowerShell:

```
Invoke-WebRequest https://github.com/vmaiorov-collab/ejudge-clion/releases/latest/download/ejudge-clion-0.1.0.zip -OutFile $env:TEMP\ejudge-clion.zip
Expand-Archive -Force $env:TEMP\ejudge-clion.zip "$env:APPDATA\JetBrains\CLion2026.2\plugins"
```

Если у вас другая версия CLion, замените `CLion2026.2` в пути на свою папку. На Windows для запуска тестов нужен компилятор `c++` (например, MinGW) в `PATH`, а команда `python3` должна запускать Python.

> **Дальше всё объяснит сам CLion.** После установки и перезапуска при первом открытии панели **Ejudge** справа появится пошаговая инструкция: как войти, скачать контесты, запускать тесты и отправлять решения. Кнопка «?» в панели показывает её снова.

Для запуска тестов на компьютере должны быть `c++` (на macOS ставится командой `xcode-select --install`) и `python3`.

## Статистика

Плагин анонимно считает, сколько людей им пользуется. Он не отправляет ни идентификатор, ни логин, ни данные контестов: раз при первой установке и раз в день при запуске CLion увеличиваются два публичных счётчика (`installs` и `active-<дата>`). Отключить можно в Settings → Tools → Ejudge → «Send anonymous usage statistics».

Посмотреть числа: `https://abacus.jasoncameron.dev/get/ejudge-clion-vmaiorov/installs` и `.../active-ГГГГ-ММ-ДД`.

## Сборка из исходников

```
JAVA_HOME=/path/to/CLion.app/Contents/jbr/Contents/Home ./gradlew buildPlugin
```

Путь к локальному CLion задаётся в `build.gradle.kts` (`intellijPlatform { local(...) }`). Готовый zip появится в `build/distributions/`.
