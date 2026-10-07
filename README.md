# ejudge-clion

CLion plugin for ejudge (ejudge.algocode.ru): import contests, read statements, run examples and custom input, submit and see verdicts.

## Build

```
JAVA_HOME=/path/to/CLion.app/Contents/jbr/Contents/Home ./gradlew buildPlugin
```

The plugin zip appears in `build/distributions/`. Install it via Settings → Plugins → Install Plugin from Disk.
Set the local CLion path in `build.gradle.kts` (`intellijPlatform { local(...) }`).
