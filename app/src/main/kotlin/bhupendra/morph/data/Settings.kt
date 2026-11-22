package bhupendra.morph.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import bhupendra.morph.core.llm.AnthropicProvider
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.llm.OpenAICompatProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Wire { OpenAI, Anthropic }

/** Brain presets. Every field stays editable — these are just good defaults. */
/** [fastModel]: the cheap, quick tier for background work (auto-reply verdicts, triage, digests). */
enum class Brain(val label: String, val wire: Wire, val baseUrl: String, val model: String, val needsKey: Boolean = true, val fastModel: String = model) {
    Gemini("Gemini", Wire.OpenAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest"),
    Claude("Claude", Wire.Anthropic, "https://api.anthropic.com/v1", "claude-haiku-5-5"),
    OpenAI("OpenAI", Wire.OpenAI, "https://api.openai.com/v1", "gpt-5-mini"),
    // GLM-5.3 Flash: 7/7 on the emergency-verdict eval, 1–5 s, ~6x cheaper output than DeepSeek Flash.
    OpenRouter("OpenRouter", Wire.OpenAI, "https://openrouter.ai/api/v1", "~deepseek/deepseek-flash-latest", fastModel = "z-ai/glm-5.3-flash"),
    Zen("OpenCode Zen", Wire.OpenAI, "https://opencode.ai/zen/v1", "big-pickle"),
    Local("Local / custom", Wire.OpenAI, "http://192.168.1.10:11434/v1", "llama3.2", needsKey = false);

    companion object {
        fun parse(s: String?): Brain? = entries.firstOrNull { it.name.equals(s, true) || it.label.equals(s, true) }
            ?: when (s?.lowercase()) {
                "anthropic" -> Claude
                "opencode_zen", "opencode", "zen" -> Zen
                "ollama", "custom" -> Local
                else -> null
            }
    }
}

data class MorphSettings(
    val brain: Brain = Brain.Gemini,
    val baseUrl: String = brain.baseUrl,
    val model: String = brain.model,
    val fastModel: String = brain.fastModel,
    /** Decision model ("System One") for fast typed choices; used on OpenRouter brains. */
    val decisionModel: String = bhupendra.morph.core.decide.JevDecisions.DEFAULT_MODEL,
    /** Self-hosted System One server (e.g. Kev via `llama serve` on your PC), base URL like http://192.168.1.5:8080. */
    val localDecisionUrl: String = "",
    /** Thinking effort for the main model: "low" | "medium" | "high", or null = let the model decide. */
    val mainEffort: String? = null,
    val apiKey: String = "",
    /** Google AI key for voice (Gemini TTS now, Gemini Live next). Stored encrypted, like [apiKey]. */
    val googleApiKey: String = "",
    /** Gemini TTS prebuilt voice. */
    val ttsVoice: String = "Kore",
    /** Read answers aloud when you asked by voice. */
    val speakReplies: Boolean = true,
    /** Monthly hands-free budget in USD (estimate); 0 = no cap. */
    val liveCapUsd: Double = 5.0,
    /** Master switch. Off: no model is ever called — Morph is a plain, fully offline launcher. */
    val aiEnabled: Boolean = true,
    val userName: String = "",
    val themedIcons: Boolean = false,
    val doubleTapSleep: Boolean = true,
    val memories: List<String> = emptyList(),
    val onboarded: Boolean = false,
    val autonomy: bhupendra.morph.core.agent.Autonomy = bhupendra.morph.core.agent.Autonomy.Autonomous,
    val loaded: Boolean = false,
) {
    val aiReady: Boolean get() = !brain.needsKey || apiKey.isNotBlank()
    /** AI is configured *and* switched on. Everything that would call a model goes through [provider]/[decider]. */
    val aiOn: Boolean get() = aiEnabled && aiReady

    /**
     * Fast typed decisions (yes/no, pick-one, rate). Jev on OpenRouter; any other brain answers the same typed
     * questions through its fast LLM, so every call site works everywhere.
     *
     * [preferLocal]: high-volume, low-stakes judgements (ranking notifications, "for you", operator wrap-up) go to
     * the local model when one is set, falling back to the cloud one if your PC doesn't answer. Safety calls
     * (emergency triage) pass false and always use the strongest model.
     */
    fun decider(preferLocal: Boolean = false): bhupendra.morph.core.decide.DecisionModel? {
        if (!aiEnabled) return null
        val cloud = when {
            !aiReady -> null
            brain == Brain.OpenRouter && apiKey.isNotBlank() && decisionModel.isNotBlank() ->
                bhupendra.morph.core.decide.JevDecisions(apiKey, decisionModel)
            else -> provider(fast = true)?.let { bhupendra.morph.core.decide.LlmDecisions(it) }
        }
        if (!preferLocal || localDecisionUrl.isBlank()) return cloud
        return bhupendra.morph.core.decide.FallbackDecisions(bhupendra.morph.core.decide.JevDecisions.local(localDecisionUrl), cloud,
            onPrimaryFailed = { android.util.Log.w("MorphDecide", "local decision model unreachable, using ${cloud?.id}: ${it.message}") })
    }

    /** [fast] = the background tier: cheaper and quicker, for work nobody is waiting to read. */
    fun provider(fast: Boolean = false): LlmProvider? {
        if (!aiOn) return null
        val m = if (fast) fastModel.ifBlank { model } else model
        return when (brain.wire) {
            Wire.Anthropic -> AnthropicProvider(apiKey, m, baseUrl)
            Wire.OpenAI -> OpenAICompatProvider(baseUrl, apiKey.ifBlank { null }, m, id = brain.name)
        }
    }
}

private val Context.store by preferencesDataStore("morph")
val EFFORTS = listOf("low", "medium", "high")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val brain = stringPreferencesKey("brain")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val fastModel = stringPreferencesKey("fast_model")
        val mainEffort = stringPreferencesKey("main_effort")
        val decisionModel = stringPreferencesKey("decision_model")
        val localDecisionUrl = stringPreferencesKey("local_decision_url")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val googleKey = stringPreferencesKey("google_key_enc")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val liveCap = androidx.datastore.preferences.core.doublePreferencesKey("live_cap_usd")
        val apiKey = stringPreferencesKey("api_key_enc")
        val name = stringPreferencesKey("user_name")
        val themed = booleanPreferencesKey("themed_icons")
        val doubleTapSleep = booleanPreferencesKey("double_tap_sleep")
        val memories = stringSetPreferencesKey("memories")
        val onboarded = booleanPreferencesKey("onboarded")
        val autonomy = stringPreferencesKey("autonomy")
    }

    val state: StateFlow<MorphSettings> = context.store.data.map(::read)
        .stateIn(scope, SharingStarted.Eagerly, MorphSettings())

    private fun read(p: Preferences): MorphSettings {
        val brain = Brain.parse(p[K.brain]) ?: Brain.Gemini
        return MorphSettings(
            brain = brain,
            baseUrl = p[K.baseUrl] ?: brain.baseUrl,
            model = p[K.model] ?: brain.model,
            fastModel = p[K.fastModel] ?: brain.fastModel,
            mainEffort = p[K.mainEffort]?.takeIf { it in EFFORTS },
            decisionModel = p[K.decisionModel] ?: bhupendra.morph.core.decide.JevDecisions.DEFAULT_MODEL,
            localDecisionUrl = p[K.localDecisionUrl].orEmpty(),
            aiEnabled = p[K.aiEnabled] ?: true,
            googleApiKey = p[K.googleKey]?.let(KeyCipher::decrypt).orEmpty(),
            ttsVoice = p[K.ttsVoice] ?: "Kore",
            speakReplies = p[K.speakReplies] ?: true,
            liveCapUsd = p[K.liveCap] ?: 5.0,
            apiKey = p[K.apiKey]?.let(KeyCipher::decrypt).orEmpty(),
            userName = p[K.name].orEmpty(),
            themedIcons = p[K.themed] ?: false,
            doubleTapSleep = p[K.doubleTapSleep] ?: true,
            memories = p[K.memories].orEmpty().sorted(),
            onboarded = p[K.onboarded] ?: false,
            autonomy = bhupendra.morph.core.agent.Autonomy.parse(p[K.autonomy]),
            loaded = true,
        )
    }

    suspend fun saveBrain(brain: Brain, baseUrl: String, model: String, apiKey: String?, fastModel: String? = null) {
        context.store.edit {
            if (fastModel != null) it[K.fastModel] = fastModel.ifBlank { brain.fastModel }.trim()
            else if (it[K.brain] != brain.name) it.remove(K.fastModel)
            it[K.brain] = brain.name
            it[K.baseUrl] = baseUrl.ifBlank { brain.baseUrl }.trim()
            it[K.model] = model.ifBlank { brain.model }.trim()
            if (apiKey != null) {
                if (apiKey.isBlank()) it.remove(K.apiKey) else it[K.apiKey] = KeyCipher.encrypt(apiKey.trim())
            }
        }
    }

    suspend fun setName(name: String) = context.store.edit { it[K.name] = name.trim() }
    suspend fun setThemedIcons(on: Boolean) = context.store.edit { it[K.themed] = on }
    suspend fun setDoubleTapSleep(on: Boolean) = context.store.edit { it[K.doubleTapSleep] = on }
    suspend fun setMainEffort(effort: String?) = context.store.edit {
        if (effort in EFFORTS) it[K.mainEffort] = effort!! else it.remove(K.mainEffort)
    }
    suspend fun setDecisionModel(m: String) = context.store.edit { it[K.decisionModel] = m.trim() }
    suspend fun setGoogleApiKey(key: String) = context.store.edit {
        if (key.isBlank()) it.remove(K.googleKey) else it[K.googleKey] = KeyCipher.encrypt(key.trim())
    }
    suspend fun setTtsVoice(v: String) = context.store.edit { it[K.ttsVoice] = v }
    suspend fun setSpeakReplies(on: Boolean) = context.store.edit { it[K.speakReplies] = on }
    suspend fun setLiveCap(usd: Double) = context.store.edit { it[K.liveCap] = usd }
    suspend fun setAiEnabled(on: Boolean) = context.store.edit { it[K.aiEnabled] = on }
    suspend fun setLocalDecisionUrl(u: String) = context.store.edit { it[K.localDecisionUrl] = u.trim() }
    suspend fun setOnboarded() = context.store.edit { it[K.onboarded] = true }
    suspend fun setAutonomy(a: bhupendra.morph.core.agent.Autonomy) = context.store.edit { it[K.autonomy] = a.name }
    suspend fun remember(fact: String) = context.store.edit {
        it[K.memories] = (it[K.memories].orEmpty() + fact.trim()).toList().takeLast(40).toSet()
    }
    suspend fun forget(fact: String) = context.store.edit { it[K.memories] = it[K.memories].orEmpty() - fact }
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
object KeyCipher {
    private const val ALIAS = "morph_secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = runCatching {
        val bytes = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}
