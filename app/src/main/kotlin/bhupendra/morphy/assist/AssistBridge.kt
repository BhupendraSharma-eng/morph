package bhupendra.morphy.assist

/**
 * Tiny link between the floating assistant session and the rest of Morphy (main thread only).
 * The operator needs the real app underneath, so it asks the panel to step aside first.
 */
object AssistBridge {
    @Volatile var hidePanel: (() -> Unit)? = null
    /** The app screen captured just before the panel appeared (fallback when no AssistStructure is provided). */
    @Volatile var lastScreen: bhupendra.morphy.core.operator.ScreenSnapshot? = null
    /** Morphy's home is on screen: summoning the assistant there means "listen", not "open a second panel". */
    @Volatile var homeInFront = false
    @Volatile var listenOnHome: (() -> Unit)? = null
}
