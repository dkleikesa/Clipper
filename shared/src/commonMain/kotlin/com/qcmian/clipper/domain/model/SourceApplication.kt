package com.qcmian.clipper.domain.model

import kotlinx.serialization.Serializable

/**
 * The application a clipboard entry was copied from, the counterpart of Maccy's
 * `HistoryItem.application` + `NSWorkspace.frontmostApplication`.
 *
 * The icon is not persisted: it is looked up from the bundle on demand and cached, the same
 * way Maccy's `ApplicationImageCache` does it.
 */
@Serializable
data class SourceApplication(
    /** Localised application name, shown in the preview. */
    val name: String,
    /** Bundle identifier, used for the ignore list and for looking the icon up. */
    val bundleId: String? = null,
)
