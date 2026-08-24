package com.lambliver.stallpos.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object SyncCloudKeys {
    const val BASE_URL = "sync_base_url"
    const val ACCESS_TOKEN = "sync_access_token"
    const val REFRESH_TOKEN = "sync_refresh_token"
    const val USER_ID = "sync_user_id"
    const val TRANSFER_COMMIT_TOKEN = "sync_transfer_commit_token"
}

internal data class SyncHttpResponse(val status: Int, val body: String)

internal fun interface SyncTransport {
    suspend fun post(url: String, accessToken: String, body: String): SyncHttpResponse
}

internal class UrlConnectionSyncTransport : SyncTransport {
    override suspend fun post(url: String, accessToken: String, body: String): SyncHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                SyncHttpResponse(status, stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("sync response is too large")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

internal enum class SyncRunResult { IDLE, SUCCESS, RETRY, BLOCKED }

internal class SyncEngine(
    private val database: StallPosV2Database,
    private val transport: SyncTransport = UrlConnectionSyncTransport(),
    private val refreshAccessToken: suspend () -> String? = { refreshSyncSession(database) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.v2Dao()

    suspend fun runOnce(): SyncRunResult {
        val baseUrl = dao.cloudValue(SyncCloudKeys.BASE_URL) ?: return SyncRunResult.IDLE
        val accessToken = dao.cloudValue(SyncCloudKeys.ACCESS_TOKEN) ?: return SyncRunResult.IDLE
        val device = dao.deviceState()?.takeIf { it.status == "ACTIVE" } ?: return SyncRunResult.IDLE
        val rows = dao.pendingOutbox(nowMillis(), MAX_BATCH_SIZE)
        if (rows.isEmpty()) return if (dao.pendingOutboxCount() > 0) SyncRunResult.RETRY else SyncRunResult.IDLE

        val requestId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("requestId", requestId)
            .put("deviceId", device.deviceId)
            .put("cloudEpoch", device.cloudEpoch)
            .put("operations", rows.fold(org.json.JSONArray()) { array, row -> array.put(row.toOperationJson()) })
            .toString()
        var response = try {
            transport.post("${baseUrl.trimEnd('/')}/v2/sync/batch", accessToken, body)
        } catch (error: IOException) {
            markTransient(rows, error.message ?: "network error")
            return SyncRunResult.RETRY
        }
        if (response.status == 401) {
            val refreshed = try {
                refreshAccessToken()
            } catch (blocked: CloudLifecycleException) {
                database.withTransaction {
                    rows.forEach { dao.markOutboxBlocked(it.operationId, blocked.code, blocked.message, nowMillis()) }
                }
                return SyncRunResult.BLOCKED
            }
            if (refreshed != null) {
                response = try {
                    transport.post("${baseUrl.trimEnd('/')}/v2/sync/batch", refreshed, body)
                } catch (error: IOException) {
                    markTransient(rows, error.message ?: "network error")
                    return SyncRunResult.RETRY
                }
            }
        }
        if (response.status == 401 || response.status == 408 || response.status == 429 ||
            response.status in 300..399 || response.status >= 500) {
            markTransient(rows, "HTTP ${response.status}")
            return SyncRunResult.RETRY
        }
        if (response.status !in 200..299) {
            val code = response.errorCode().takeIf { it in BLOCKED_CODES } ?: "INVALID_DATA"
            val message = response.errorMessage() ?: "HTTP ${response.status}"
            database.withTransaction {
                rows.forEach { dao.markOutboxBlocked(it.operationId, code, message, nowMillis()) }
            }
            return SyncRunResult.BLOCKED
        }

        val results = runCatching { parseResults(response.body, requestId, rows.map { it.operationId }.toSet()) }
            .getOrElse {
                markTransient(rows, "invalid sync response")
                return SyncRunResult.RETRY
            }
        var retry = false
        var blocked = false
        val now = nowMillis()
        database.withTransaction {
            val acked = results.filterValues { it.status == "ACK" }.keys.toList()
            if (acked.isNotEmpty()) dao.markOutboxSynced(acked, now)
            results.forEach { (operationId, result) ->
                when (result.status) {
                    "ACK" -> Unit
                    "RETRY" -> {
                        retry = true
                        val row = rows.first { it.operationId == operationId }
                        dao.markOutboxPending(listOf(operationId), result.message, nextAttempt(now, row.attemptCount), now)
                    }
                    "BLOCKED" -> {
                        blocked = true
                        val code = result.code?.takeIf { it in BLOCKED_CODES } ?: "INVALID_DATA"
                        dao.markOutboxBlocked(operationId, code, result.message, now)
                    }
                }
            }
        }
        return when {
            retry -> SyncRunResult.RETRY
            blocked -> SyncRunResult.BLOCKED
            else -> SyncRunResult.SUCCESS
        }
    }

    private suspend fun markTransient(rows: List<SyncOutboxEntity>, message: String) {
        val now = nowMillis()
        database.withTransaction {
            rows.forEach { row ->
                dao.markOutboxPending(listOf(row.operationId), message, nextAttempt(now, row.attemptCount), now)
            }
        }
    }

    private fun nextAttempt(now: Long, attemptCount: Int): Long {
        val multiplier = 1L shl attemptCount.coerceIn(0, 10)
        return now + (30_000L * multiplier).coerceAtMost(6 * 60 * 60 * 1_000L)
    }

    private fun parseResults(body: String, requestId: String, expectedIds: Set<String>): Map<String, ServerSyncResult> {
        val root = JSONObject(body)
        require(root.getString("requestId") == requestId)
        val array = root.getJSONArray("results")
        require(array.length() == expectedIds.size)
        val parsed = buildMap {
            repeat(array.length()) { index ->
                val row = array.getJSONObject(index)
                val operationId = row.getString("operationId")
                val status = row.getString("status")
                require(operationId in expectedIds && status in setOf("ACK", "RETRY", "BLOCKED"))
                require(put(operationId, ServerSyncResult(status, row.optNullableString("code"), row.optNullableString("message"))) == null)
            }
        }
        require(parsed.keys == expectedIds)
        return parsed
    }

    private data class ServerSyncResult(val status: String, val code: String?, val message: String?)

    private companion object {
        const val MAX_BATCH_SIZE = 50
        val BLOCKED_CODES = setOf("DEVICE_RETIRED", "CLOUD_EPOCH_REVOKED", "INVALID_DATA", "SERVER_CONFLICT")
    }
}

internal suspend fun configureSyncSession(
    database: StallPosV2Database,
    baseUrl: String,
    accessToken: String,
    deviceId: String,
    cloudEpoch: Long,
    deviceName: String = android.os.Build.MODEL.take(100).ifBlank { "Android" },
    nowMillis: Long = System.currentTimeMillis(),
    refreshToken: String? = null,
    userId: String? = null,
) {
    val uri = URI(baseUrl)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null)
    require(accessToken.isNotBlank() && accessToken.length <= 2048)
    require(UUID.fromString(deviceId).toString() == deviceId)
    require(cloudEpoch >= 0)
    database.withTransaction {
        database.v2Dao().putCloudState(
            buildList {
                add(CloudStateEntity(SyncCloudKeys.BASE_URL, baseUrl.trimEnd('/')))
                add(CloudStateEntity(SyncCloudKeys.ACCESS_TOKEN, accessToken))
                refreshToken?.let { add(CloudStateEntity(SyncCloudKeys.REFRESH_TOKEN, it)) }
                userId?.let { add(CloudStateEntity(SyncCloudKeys.USER_ID, it)) }
            },
        )
        val previous = database.v2Dao().deviceState()
        database.v2Dao().putDeviceState(
            DeviceStateEntity(
                deviceId = deviceId,
                shortCode = previous?.shortCode ?: "A",
                name = deviceName,
                status = "ACTIVE",
                cloudEpoch = cloudEpoch,
                registeredAtMillis = previous?.registeredAtMillis ?: nowMillis,
                lastSeenAtMillis = nowMillis,
                retiredAtMillis = null,
            ),
        )
    }
}

internal object SyncScheduler {
    private const val UNIQUE_WORK = "stallpos-v2-sync"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<StallPosSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }
}

internal class StallPosSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val engine = SyncEngine(StallPosV2Database.get(applicationContext))
        while (true) {
            when (engine.runOnce()) {
                SyncRunResult.IDLE -> return@runCatching Result.success()
                SyncRunResult.RETRY -> return@runCatching Result.retry()
                SyncRunResult.SUCCESS, SyncRunResult.BLOCKED -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.success()
    }.getOrElse {
        Result.retry()
    }
}

private fun SyncOutboxEntity.toOperationJson() = JSONObject()
    .put("operationId", operationId)
    .put("category", category)
    .put("entityType", entityType)
    .put("entityId", entityId)
    .put("operationType", operationType)
    .put("payload", JSONObject(payloadJson))

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun SyncHttpResponse.errorCode(): String? = runCatching { JSONObject(body).optNullableString("code") }.getOrNull()
private fun SyncHttpResponse.errorMessage(): String? = runCatching { JSONObject(body).optNullableString("message") }.getOrNull()
