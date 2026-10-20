package com.app.nuri.remote

/**
 * The seam between the shared sync engine and the FLAVOR's shadow
 * store (design §13.1: "private flavor only, via FlavorHooks").
 *
 * The private flavor's FlavorHooks registers a handler at app
 * start: incoming remote shadow values are written into the same
 * shadow store the on-device caregiver editor uses, so the
 * effective-value rules (safety applies, comfort suggests) are the
 * flavor's own, unchanged. The public flavor registers NOTHING —
 * [handler] stays null, the engine never applies shadow, and no
 * shadow-application code exists in the public build at all.
 */
object RemoteShadowBridge {

    interface Handler {
        /** Apply a complete remote shadow document (full-replace semantics). */
        fun applyRemoteShadow(values: Map<String, String>)

        /** The shadow values this phone currently holds, in protocol keys (non-null entries only). */
        fun currentShadowValues(): Map<String, String>
    }

    @Volatile
    var handler: Handler? = null
}
