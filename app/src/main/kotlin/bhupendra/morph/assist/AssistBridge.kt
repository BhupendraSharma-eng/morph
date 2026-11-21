package bhupendra.morph.assist

/**
 * Tiny link between the floating assistant session and the rest of Morph (main thread only).
 * The operator needs the real app underneath, so it asks the panel to step aside first.
 */
object AssistBridge {
    @Volatile var hidePanel: (() -> Unit)? = null
    /** The app screen captured just before the panel appeared (fallback when no AssistStructure is provided). */
    @Volatile var lastScreen: bhupendra.morph.core.operator.ScreenSnapshot? = null
    /** Morph's home is on screen: summoning the assistant there means "listen", not "open a second panel". */
    @Volatile var homeInFront = false
    @Volatile var listenOnHome: (() -> Unit)? = null
}
