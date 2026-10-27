# DeepSeek-AI-Helper - IntelliJ IDEA Plugin

DeepSeek AI intelligent assistant plugin for IntelliJ IDEA, integrating code completion, chat conversation, and intelligent code operation features.

## Features

### 1. Code Completion
- AI-powered inline code suggestions
- FIM (Fill-in-the-Middle) completion mode
- Supports multiple programming languages
- Configurable trigger delay and context lines

### 2. Chat Panel
- Sidebar chat window
- Streaming response display
- File attachment support
- Selected code preview
- Session management

### 3. Agent Actions
Right-click code to quickly execute:
- **Ask DeepSeek** - Ask any question about the selected code
- **Explain Code** - Explain the selected code in detail
- **Generate Code** - Generate code based on selection or description
- **Review Code** - Code review with suggestions
- **Optimize Code** - Optimize code performance and readability

### 4. Settings Configuration
- API Key configuration
- Model selection
- Token limit
- Temperature parameter adjustment
- Code completion toggle
- Separate completion model configuration

## Tech Stack

- **Language**: Kotlin
- **Build Tool**: Gradle
- **Target Platform**: IntelliJ IDEA 2025.1+ (IC-2025.1)
- **Network Library**: OkHttp 4.12.0 (with SSE streaming support)
- **JSON Processing**: Gson 2.11.0
- **Minimum Java Version**: 21

## Installation &amp; Configuration

### Prerequisites
- IntelliJ IDEA 2025.1 or later
- DeepSeek API Key (from https://platform.deepseek.com)
- Java 17

### Installation Steps

#### Build from Source
1. Clone the repository
   ```bash
   git clone &lt;repository-url&gt;
   cd dp-connection
   ```

2. Build the plugin using Gradle
   ```bash
   # Windows
   .\gradlew.bat buildPlugin

   # Linux/Mac
   ./gradlew buildPlugin
   ```

3. After building, the plugin file is located in `build/distributions/` directory

#### Run in IDE
```bash
# Windows
.\gradlew.bat runIde

# Linux/Mac
./gradlew runIde
```

### Configure Plugin

1. Open IntelliJ IDEA Settings
2. Navigate to **Tools → DeepSeek-AI-Helper**
3. Configure the following:
   - **API Key**: Enter your DeepSeek API Key
   - **Model**: Select model (default: deepseek-chat)
   - **Max Tokens**: Maximum generated tokens (default: 4096)
   - **Temperature**: Temperature parameter (default: 0.7)
   - **Completion Enabled**: Enable/disable code completion
   - **Completion Model**: Dedicated completion model (optional)
   - **Completion Max Tokens**: Completion max tokens (default: 256)
   - **Completion Delay (ms)**: Completion trigger delay (default: 500ms)
   - **Completion Min Prefix**: Minimum prefix characters to trigger (default: 2)
   - **Max Context Lines**: Maximum context lines for completion (default: 30)

## Project Structure

```
dp-connection/
├── src/main/
│   ├── java/com/deepseek/plugin/
│   │   ├── agent/              # Agent action implementations
│   │   │   ├── AgentActions.kt
│   │   │   └── BaseAgentAction.kt
│   │   ├── api/                # API client
│   │   │   ├── DeepSeekApiClient.kt
│   │   │   └── Models.kt
│   │   ├── chat/               # Chat panel
│   │   │   ├── ChatPanel.kt
│   │   │   └── ChatToolWindowFactory.kt
│   │   ├── completion/         # Code completion
│   │   │   └── DeepSeekCompletionContributor.kt
│   │   ├── context/            # Project context provider
│   │   │   └── ProjectContextProvider.kt
│   │   ├── icons/              # Icon resources
│   │   │   └── PluginIcons.kt
│   │   ├── settings/           # Settings configuration
│   │   │   ├── DeepSeekSettings.kt
│   │   │   └── DeepSeekSettingsConfigurable.kt
│   │   ├── store/              # Session storage
│   │   │   └── SessionStore.kt
│   │   └── ui/                 # UI components
│   │       ├── AttachedFile.kt
│   │       ├── CodeBlockCard.kt
│   │       ├── FileAttachmentPreview.kt
│   │       └── SelectedCodePreview.kt
│   └── resources/
│       ├── META-INF/
│       │   ├── plugin.xml      # Plugin configuration file
│       │   └── pluginIcon.*
│       └── icons/
├── gradle/                     # Gradle wrapper
├── build.gradle.kts            # Gradle build script
├── settings.gradle.kts         # Gradle settings
└── gradle.properties           # Gradle property configuration
```

## Core Module Description

### DeepSeekApiClient (`api/DeepSeekApiClient.kt`)
- Responsible for communicating with DeepSeek API
- Supports both synchronous and streaming call modes
- Implements FIM (Fill-in-the-Middle) code completion
- Manages API requests and responses

### ChatPanel (`chat/ChatPanel.kt`)
- Implements chat interface
- Supports streaming response display
- Handles file attachments
- Displays selected code preview

### Agent Actions (`agent/`)
- Implements right-click menu operations
- Includes Ask, Explain, Generate, Review, Optimize features
- Built on BaseAgentAction base class

### DeepSeekSettings (`settings/DeepSeekSettings.kt`)
- Manages plugin configuration
- Persistent storage
- Singleton pattern access

### DeepSeekCompletionContributor (`completion/DeepSeekCompletionContributor.kt`)
- Integrates with IDE's code completion system
- Calls FIM API for suggestions
- Manages completion trigger timing

## Version History

### v1.0.4
- Added file upload button in chat input
- Selected code preview card
- Modular code block card component (CodeBlockCard)
- Clear button moved to session bar

### v1.0.3
- Fixed duplicate AI responses consuming double tokens in chat
- Fixed oversized right-click menu icon
- Fixed oversized agent result dialog
- Improved code completion: single-suggestion mode, better FIM prompt

### v1.0.0
- AI-powered inline code completion
- Streaming chat Q&amp;A panel
- Agent mode: Explain, Generate, Review, Optimize
- Right-click context menu integration
- API Key configuration page

## Development Guide

### Environment Setup
1. Install IntelliJ IDEA 2025.1+
2. Install Kotlin plugin
3. Install Gradle
4. Clone project and open in IDE

### Common Gradle Tasks

```bash
# Build plugin
.\gradlew.bat buildPlugin

# Run IDE test instance
.\gradlew.bat runIde

# Clean build
.\gradlew.bat clean

# Compile Kotlin
.\gradlew.bat compileKotlin
```

### Debug Plugin
Use IntelliJ IDEA's `Run IDE with Plugin` run configuration for debugging.

## License

Copyright 2026 DeepSeek

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Contact

- Official Website: https://deepseek.com
- Plugin ID: com.deepseek.plugin
- Plugin Name: DeepSeek-AI-Helper

## Notes

- Requires valid DeepSeek API Key to use
- Code completion feature sends code context to DeepSeek server
- It is recommended to read DeepSeek's privacy policy before use
- Plugin persists configuration information (not including API Key)
