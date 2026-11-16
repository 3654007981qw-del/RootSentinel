package com.sukisu.nova.ui.viewmodel

import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sukisu.nova.Natives
import com.sukisu.nova.data.repository.SettingsRepositoryImpl
import com.sukisu.nova.getKernelVersion
import com.sukisu.nova.ksuApp
import com.sukisu.nova.ui.screen.home.HomeLoadState
import com.sukisu.nova.ui.screen.home.HomeUiState
import com.sukisu.nova.ui.screen.home.SystemInfo
import com.sukisu.nova.ui.util.HomeCardImageStyle
import com.sukisu.nova.ui.util.HomeLayout
import com.sukisu.nova.ui.util.HomeStatusAlignment
import com.sukisu.nova.ui.util.HomeStatusStyle
import com.sukisu.nova.ui.util.getSELinuxStatusRaw
import com.sukisu.nova.ui.util.module.LatestVersionInfo
import com.sukisu.nova.ui.util.rootAvailable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RootSentinel seam for SukiSU-Nova's HomeViewModel: upstream assembles
 * HomeUiState from the SukiSU kernel (Natives JNI), the settings repository
 * and root-shell probes. RootSentinel has no SukiSU kernel, so the state is
 * assembled from the seam Natives (no-kernel defaults), the verbatim
 * settings repository, and the same device facts upstream reads (uname,
 * Build, SELinux status via the vendored checker). The home screens
 * themselves are upstream verbatim; on this device they render SukiSU's
 * not-installed / preview presentation, which is the honest state.
 * Manager self-update installs are no-ops here (RootSentinel updates
 * through its own verified updater).
 */
class HomeViewModel : ViewModel() {

    private val settingsRepo = SettingsRepositoryImpl()

    private val _uiState = MutableStateFlow(HomeLoadState())
    val uiState: StateFlow<HomeLoadState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onResume(isCurrentPage: Boolean) {
        if (isCurrentPage) refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            // RootSentinel seam hardening (v2.22.72): buildState() reads
            // seam-backed channels (libsu shell, SELinux checker, prefs);
            // a throw must surface as the error state, never crash the app.
            runCatching { withContext(Dispatchers.IO) { buildState() } }
                .onSuccess { content ->
                    _uiState.update { it.copy(content = content, isRefreshing = false) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = e.message ?: e.javaClass.simpleName)
                    }
                }
        }
    }

    fun installCiUpdate() = Unit

    fun installReleaseUpdate() = Unit

    private fun buildState(): HomeUiState {
        val isManager = Natives.isManager
        val isRootAvailable = rootAvailable()
        val managerUapi = Natives.managerUAPIVersion
        return HomeUiState(
            kernelVersion = getKernelVersion(),
            ksuVersion = null,
            managerUAPIVersion = managerUapi,
            kernelUAPIVersion = null,
            lkmMode = null,
            isLkmBundled = false,
            isManager = isManager,
            isManagerPrBuild = false,
            isKernelPrBuild = Natives.isPrBuild,
            requiresNewKernel = false,
            requiresNewManager = false,
            isRootAvailable = isRootAvailable,
            isSafeMode = Natives.isSafeMode,
            isLateLoadMode = Natives.isLateLoadMode,
            checkUpdateEnabled = settingsRepo.checkUpdate,
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = com.rootsentinel.RootSentinelVersion.CODE.toLong(),
            systemInfo = SystemInfo(
                kernelVersion = Os.uname().release,
                managerVersion = "${com.rootsentinel.RootSentinelVersion.NAME} " +
                    "(${com.rootsentinel.RootSentinelVersion.CODE}-$managerUapi)",
                deviceModel = Build.MODEL,
                kernelFullVersion = null,
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = getSELinuxStatusRaw(),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
                zygiskImplementation = null,
            ),
            checkCiUpdateEnabled = settingsRepo.checkUpdate && settingsRepo.checkCiUpdate,
            ciLatestVersionInfo = LatestVersionInfo(),
            ciUpdateBusy = false,
            showFullStatus = ksuApp.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("show_fingerprint", true),
            hideHomeWarnings = ksuApp.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("hide_home_warnings", false),
            isPreviewMode = !isManager && isRootAvailable,
            hideVersionMismatchWarning = false,
            isVersionSpoofSupported = false,
            isVersionSpoofed = false,
            superuserCount = null,
            moduleCount = null,
            kpmCount = null,
            showAiHomeCard = settingsRepo.showAiHomeCard,
            customHomeTitle = settingsRepo.customHomeTitle,
            homeCardImagePath = settingsRepo.homeCardImagePath
                .takeIf { it.isNotEmpty() && File(it).isFile } ?: "",
            hideHomeCardStatusText = settingsRepo.hideHomeCardStatusText,
            hideHomeCardDecoration = settingsRepo.hideHomeCardDecoration,
            homeCardStickers = settingsRepo.homeCardStickers.filter { File(it.path).isFile },
            homeLayout = HomeLayout.fromValue(settingsRepo.homeLayout),
            homeStatusAlignment = HomeStatusAlignment.fromValue(settingsRepo.homeStatusAlignment),
            homeStatusScale = HomeStatusStyle.coerceScale(settingsRepo.homeStatusScale),
            homePillScale = HomeStatusStyle.coercePillScale(settingsRepo.homePillScale),
            homeStatusWidthFraction = HomeStatusStyle.coerceWidth(settingsRepo.homeStatusWidthFraction),
            homeCardImageStyle = HomeCardImageStyle.fromValue(settingsRepo.homeCardImageStyle),
            showHomeCardImageWithoutBackground = settingsRepo.showHomeCardImageWithoutBackground,
        )
    }
}
