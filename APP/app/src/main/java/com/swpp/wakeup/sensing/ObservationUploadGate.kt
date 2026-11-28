package com.swpp.wakeup.sensing

import android.content.Context
import android.content.SharedPreferences
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.local.normalizeOwner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by UI, workers and services, including separately constructed queue instances. */
internal object ObservationUploadGate {
    private val uploadMutex = Mutex()
    private val storageLock = Any()
    private val accepted = AtomicLong()

    val acceptedVersion: Long get() = accepted.get()

    suspend fun <T> upload(block: suspend () -> T): T = uploadMutex.withLock { block() }

    fun <T> storage(block: () -> T): T = synchronized(storageLock, block)

    fun recordAccepted(count: Int) {
        if (count > 0) accepted.addAndGet(count.toLong())
    }

    /** An old in-flight response must not drop or restamp the next account's queue. */
    data class Owner(val account: String?, val stored: String?)

    fun owner(context: Context, prefs: SharedPreferences): Owner = Owner(
        normalizeOwner(TokenStore(context).email), normalizeOwner(prefs.getString("_owner", null)),
    )
}
