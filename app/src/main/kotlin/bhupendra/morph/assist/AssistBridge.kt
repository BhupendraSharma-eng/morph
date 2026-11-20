package bhupendra.morph.assist

/**
 * Tiny link between the floating assistant session and the rest of Morph (main thread only).
 * The operator needs the real app underneath, so it asks the panel to step aside first.
 */
object AssistBridge {
    @Volatile var hidePanel: (() -> Unit)? = null
}
