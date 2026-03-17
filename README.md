# BookPeek

An IntelliJ Platform plugin for reading novels and text files inside your IDE.

## Features

- Open and read TXT files directly in a tool window
- Automatic chapter detection with configurable regex patterns
- Chapter navigation and table of contents
- Reading progress persistence across IDE restarts
- Adjustable font size
- Keyboard shortcuts for chapter switching

## Getting Started

1. Go to **Settings → Tools → BookPeek** and select a TXT file
2. Click the **BookPeek** icon in the bottom tool bar to open the reader
3. Use **Alt+A** / **Alt+D** to navigate chapters, **Alt+C** to toggle controls

## Project Structure

```
.
├── .run/                   Predefined Run/Debug Configurations
├── build/                  Output build directory
├── gradle
│   └── wrapper/            Gradle Wrapper
├── src
│   └── main
│       ├── kotlin/         Kotlin production sources
│       └── resources/      Resources - plugin.xml, icons
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
└── settings.gradle.kts     Gradle project settings
```

## Building

```bash
./gradlew buildPlugin
```

## Running (Debug)

```bash
./gradlew runIde
```
