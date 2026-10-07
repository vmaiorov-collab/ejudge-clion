# Ejudge для CLion

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

Для запуска тестов на компьютере должны быть `c++` (на macOS ставится командой `xcode-select --install`) и `python3`.

## Сборка из исходников

```
JAVA_HOME=/path/to/CLion.app/Contents/jbr/Contents/Home ./gradlew buildPlugin
```

Путь к локальному CLion задаётся в `build.gradle.kts` (`intellijPlatform { local(...) }`). Готовый zip появится в `build/distributions/`.
