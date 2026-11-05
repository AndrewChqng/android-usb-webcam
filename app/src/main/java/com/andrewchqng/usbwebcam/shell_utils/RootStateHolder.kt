package com.andrewchqng.usbwebcam.shell_utils

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

enum class RootMethod {
    UNKNOWN,
    UNROOTED,
    MAGISK,
    KERNELSU,
    APATCH
}

data class RootState(
    val missingRootPrivileges: Boolean = false,
)

class RootStateHolder private constructor() {
    private val _uiState = MutableStateFlow(RootState())
    val uiState = _uiState.asStateFlow()

    @Volatile
    private var cachedRootMethod: RootMethod? = null

    private val sepolicyMap: Map<RootMethod, String?> = buildMap {
        put(RootMethod.UNKNOWN, null)
        put(RootMethod.UNROOTED, null)
        put(RootMethod.MAGISK, "magiskpolicy --live")
        put(RootMethod.KERNELSU, "ksud sepolicy patch")
        put(RootMethod.APATCH, "apd sepolicy patch")
    }
    private val rootBinaryMap: Map<String, RootMethod> = buildMap {
        put("magisk", RootMethod.MAGISK)
        put("magiskpolicy", RootMethod.MAGISK)
        put("ksud", RootMethod.KERNELSU)
        put("apd", RootMethod.APATCH)
    }

    val sepolicyCommand: String?
        get() {
            val rootMethod = detectRootMethod()
            return sepolicyMap[rootMethod]
        }

    fun hasRootPermissions(): Boolean {
        val hasRootPermissions = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        _uiState.update { it.copy(missingRootPrivileges = !hasRootPermissions) }
        return hasRootPermissions
    }

    fun detectRootMethod(): RootMethod {
        cachedRootMethod?.let { cached ->
            if (cached != RootMethod.UNKNOWN && cached != RootMethod.UNROOTED) {
                return cached
            }
        }

        if (!hasRootPermissions()) {
            Timber.i("Failed to get root shell. Device is most likely not rooted or hasn't given the app root permissions")
            return RootMethod.UNROOTED
        }

        for ((binary, matchingRootMethod) in rootBinaryMap) {
            val isSuccess = runCatching {
                Shell.cmd("command -v $binary >/dev/null 2>&1").exec().code == 0
            }.getOrDefault(false)
            if (isSuccess) {
                Timber.i("Detected root method as: %s", matchingRootMethod)
                cachedRootMethod = matchingRootMethod
                return matchingRootMethod
            }
        }
        return RootMethod.UNKNOWN
    }

    companion object {
        @Volatile
        private var INSTANCE: RootStateHolder? = null
        fun getInstance(): RootStateHolder {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RootStateHolder().also { INSTANCE = it }
            }
        }
    }
}
