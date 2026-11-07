# LyricWave independent plugin example

Copy this directory outside the LyricWave checkout. Copy the exported SDK JAR to
`lib/lyricwave-plugin-api-v1.jar`. With JDK 21, Gradle 9.7.1 and Android SDK 37:

```text
gradle pluginZip -PandroidSdk=/absolute/android/sdk
```

Alternatively point `-PlyricwaveSdk=/absolute/lyricwave-plugin-api-v1.jar` at the SDK.
No LyricWave source project dependency is used. Output: `build/dist/basic-plugin.zip`.
Import in LyricWave settings → 插件, enable, then restart the host.

This example observes the platform Activity lifecycle. Its observation does not
write the invocation arguments or result. A plugin implementing host-specific
features must locate and maintain its own targets using the supplied host loader.
