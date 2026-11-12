package com.localai.app

/**
 * Thin Kotlin wrapper around the native (C++) llama.cpp inference engine.
 * Every method below is implemented in native-lib.cpp and calls straight
 * into llama.cpp / llava — nothing here ever touches the network.
 */
object LlamaBridge {
    init {
        System.loadLibrary("localai")
    }

    /** Load a GGUF text model from an absolute file path. Returns a context handle, or 0 on failure. */
    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): Long

    /**
     * Optional: load a multimodal projector (mmproj GGUF) alongside a vision-capable model
     * (e.g. LLaVA, MobileVLM, Moondream2) so the same context can describe images.
     * Call AFTER loadModel. Returns true on success.
     */
    external fun loadMultimodalProjector(contextHandle: Long, mmprojPath: String): Boolean

    /**
     * Run inference. If imagePath is non-null, the image is embedded into the prompt
     * via the loaded mmproj (vision) pipeline. onToken is called once per generated token
     * so the UI can stream text as it's produced.
     */
    external fun generate(
        contextHandle: Long,
        prompt: String,
        imagePath: String?,
        maxTokens: Int,
        onToken: (String) -> Unit
    )

    /** Free native memory. Call when the model is no longer needed. */
    external fun unload(contextHandle: Long)
}
