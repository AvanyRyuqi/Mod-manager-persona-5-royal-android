package app.compendium.android

/** The C++ engine (source/android/engine.cpp). Strings cross as UTF-8 byte arrays. */
object Native {
    init {
        System.loadLibrary("compendium")
    }

    @JvmStatic external fun call(method: ByteArray, args: ByteArray): ByteArray

    @JvmStatic external fun cancel(task: ByteArray)

    /** Used by [Bridge.httpDownload]: reports bytes done; false means the user cancelled. */
    @JvmStatic external fun progress(handle: Long, done: Long, total: Long): Boolean
}
