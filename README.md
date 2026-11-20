# Morph

A home screen with no icon grid. You say what you want; an agent does it and composes
native UI ("surfaces") on the fly. Android 16+ only, Material 3 Expressive, Kotlin + Compose.

## Modules

| Module | What lives there |
|---|---|
| `:core` | Pure Kotlin (JVM-testable). Agent loop, streaming LLM providers (OpenAI-compatible + Anthropic), tool contracts with risk levels, and the generative-UI schema (`Surface`/`Block`/`Action`) + lenient parser. |
| `:app` | The launcher. Compose UI, the surface renderer, Android capabilities as tools, the local fast path, voice, settings (DataStore + Keystore-encrypted key), notification listener. |

## How a request flows

```
input (typed / voice / chip tap)
  → LocalIntents fast path (app names, torch, timers: instant, no model)
  → Agent.run(): model ⇄ tools loop, streaming text
      tools: DeviceTools (open_app, set_timer, compose_message, get_notifications, …)
      render_ui: model composes a Surface → SurfaceCard renders it natively
  → canvas (bottom-anchored, springy entrances, swipe to dismiss)
```

`render_ui` is *terminal*: if a round ends with only UI, the loop stops without a second model call.

## Build & run

```bash
./gradlew :core:test :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed-profile -f bhupendra.morph   # precompile for smoothness
```

Configure a brain from your computer (adb only; guarded by the DUMP permission):

```bash
adb shell am broadcast -n bhupendra.morph/.ConfigReceiver --es provider gemini --es api_key "$KEY"
# providers: gemini | claude | openai | openrouter | zen | local   (+ --es model, --es base_url, --es name)
```

## Adding things
- **New capability:** add a `tool(...)` in `DeviceTools` and include it in `all`. Its schema, risk and label are declared in one place.
- **New UI block:** add it to `Block` + `SurfaceParser` (core), the `render_ui` schema, and `BlockView` (app).
