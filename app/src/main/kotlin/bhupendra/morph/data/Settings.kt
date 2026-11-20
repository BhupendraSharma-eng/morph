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
    val apiKey: String = "",
    val userName: String = "",
    val themedIcons: Boolean = false,
    val memories: List<String> = emptyList(),
    val onboarded: Boolean = false,
    val autonomy: bhupendra.morph.core.agent.Autonomy = bhupendra.morph.core.agent.Autonomy.Autonomous,
    val loaded: Boolean = false,
) {
    val aiReady: Boolean get() = !brain.needsKey || apiKey.isNotBlank()

    /** [fast] = the background tier: cheaper and quicker, for work nobody is waiting to read. */
    fun provider(fast: Boolean = false): LlmProvider? {
        if (!aiReady) return null
        val m = if (fast) fastModel.ifBlank { model } else model
        return when (brain.wire) {
            Wire.Anthropic -> AnthropicProvider(apiKey, m, baseUrl)
            Wire.OpenAI -> OpenAICompatProvider(baseUrl, apiKey.ifBlank { null }, m, id = brain.name)
        }
    }
}

private val Context.store by preferencesDataStore("morph")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val brain = stringPreferencesKey("brain")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val fastModel = stringPreferencesKey("fast_model")
        val apiKey = stringPreferencesKey("api_key_enc")
        val name = stringPreferencesKey("user_name")
        val themed = booleanPreferencesKey("themed_icons")
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
            apiKey = p[K.apiKey]?.let(KeyCipher::decrypt).orEmpty(),
            userName = p[K.name].orEmpty(),
            themedIcons = p[K.themed] ?: false,
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
