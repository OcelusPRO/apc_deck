package fr.ftnl.apcdeck.core

import kotlinx.coroutines.flow.StateFlow

enum class UpdateStatus { DISABLED, IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

/**
 * Plugin dont la source de mise à jour ([fr.ftnl.apcdeck.api.PluginManifest.repository] ou
 * [fr.ftnl.apcdeck.api.PluginManifest.updateUrl]) publie une version plus récente.
 */
data class PluginUpdate(
    val id: String,
    val name: String,
    val current: String,
    val latest: String,
    val pageUrl: String?,
    /** Ce qui distingue cette mise à jour (version, ou empreinte du jar pour une URL directe) : signalée une fois. */
    val revision: String = latest,
    val installing: Boolean = false,
    val error: String? = null,
)

data class UpdateState(
    val current: String?,
    /** État de l'application elle-même (les plugins sont dans [plugins]). */
    val status: UpdateStatus,
    val latest: String? = null,
    /** Page de la release sur GitHub. */
    val pageUrl: String? = null,
    /** Installeur de l'OS courant (null : la release n'en contient pas, il faut passer par la page). */
    val assetName: String? = null,
    val notes: String? = null,
    /** 0..1 pendant le téléchargement. */
    val progress: Double? = null,
    val error: String? = null,
    val checkedAt: Long? = null,
    val plugins: List<PluginUpdate> = emptyList(),
)

/** Mises à jour de l'application et des plugins (PC uniquement ; absent sur Android, où le store s'en charge). */
interface Updates {
    val state: StateFlow<UpdateState>

    /** Recherche maintenant et renvoie l'état obtenu ; [manual] : demandé par l'utilisateur. */
    fun check(manual: Boolean = false): UpdateState

    /** Télécharge et lance l'installeur de l'application. */
    fun install()

    /** Installe à chaud la mise à jour du plugin [id]. */
    fun installPlugin(id: String)

    fun installPlugins()
}
