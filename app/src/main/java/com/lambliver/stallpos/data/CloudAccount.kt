package com.lambliver.stallpos.data

import android.content.Context
import com.lambliver.stallpos.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import org.json.JSONObject

internal enum class CloudLoginIntent { SIGN_IN, REENABLE, CREATE_AFTER_DELETE }

internal class CloudLifecycleException(val code: String, message: String) : IOException(message)

internal data class CloudLoginResult(
    val userId: String,
    val cloudEpoch: Long,
    val additionalDevice: Boolean = false,
    val activeDeviceCount: Int = 0,
)

internal fun resetBaselineForSignIn(
    forceDevice: Boolean,
    intent: CloudLoginIntent,
    additionalDevice: Boolean,
    previousUser: String?,
    sessionUserId: String,
): Boolean = forceDevice ||
    intent != CloudLoginIntent.SIGN_IN ||
    (!additionalDevice && previousUser != sessionUserId)

internal class CloudAccountManager(
    context: Context,
    private val store: RoomPosPersistence,
    private val database: StallPosV2Database = StallPosV2Database.get(context),
    private val baseUrl: String = BuildConfig.SYNC_BASE_URL,
) {
    private val dao = database.v2Dao()

    val isConfigured: Boolean get() = validBaseUrl(baseUrl) && BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()

    suspend fun signIn(idToken: String, intent: CloudLoginIntent = CloudLoginIntent.SIGN_IN, forceDevice: Boolean = false): CloudLoginResult {
        require(isConfigured) { "雲端登入尚未設定" }
        val deviceId = store.localDeviceId()
        val previousUser = dao.cloudValue(SyncCloudKeys.USER_ID)
        val response = CloudHttp.request(
            "POST", "${baseUrl.trimEnd('/')}/v2/auth/google", body = JSONObject()
                .put("idToken", idToken)
                .put("deviceId", deviceId)
                .put("deviceName", android.os.Build.MODEL.take(100).ifBlank { "Android" })
                .put("intent", intent.name)
                .put("forceDevice", forceDevice),
        ).requireSuccess()
        val session = response.session()
        store.activateCloudSession(
            baseUrl, session.accessToken, session.refreshToken, session.userId, session.deviceId, session.cloudEpoch,
            resetBaseline = resetBaselineForSignIn(forceDevice, intent, session.additionalDevice, previousUser, session.userId),
            additionalDevice = session.additionalDevice,
            activeDeviceCount = session.activeDeviceCount,
        )
        return CloudLoginResult(session.userId, session.cloudEpoch, session.additionalDevice, session.activeDeviceCount)
    }

    suspend fun claimTransfer(transferToken: String): CloudLoginResult {
        require(validBaseUrl(baseUrl)) { "雲端同步網址尚未設定" }
        val deviceId = store.localDeviceId()
        val claim = CloudHttp.request(
            "POST", "${baseUrl.trimEnd('/')}/v2/devices/transfer/claim", body = JSONObject()
                .put("transferToken", transferToken.trim())
                .put("deviceId", deviceId)
                .put("deviceName", android.os.Build.MODEL.take(100).ifBlank { "Android" }),
        ).requireSuccess()
        val commitToken = claim.getString("commitToken")
        store.restoreCloudBootstrap(decodeCloudBootstrap(claim.getJSONObject("bootstrap")), commitToken)
        return commitPendingTransfer()
    }

    suspend fun commitPendingTransfer(): CloudLoginResult {
        val commitToken = requireNotNull(dao.cloudValue(SyncCloudKeys.TRANSFER_COMMIT_TOKEN)) { "沒有待完成的換機" }
        val response = CloudHttp.request(
            "POST", "${baseUrl.trimEnd('/')}/v2/devices/transfer/commit", body = JSONObject().put("commitToken", commitToken),
        ).requireSuccess()
        val session = response.session()
        store.activateCloudSession(baseUrl, session.accessToken, session.refreshToken, session.userId, session.deviceId,
            session.cloudEpoch, resetBaseline = false)
        dao.deleteCloudState(listOf(SyncCloudKeys.TRANSFER_COMMIT_TOKEN))
        return CloudLoginResult(session.userId, session.cloudEpoch)
    }

    suspend fun createTransferToken(): String {
        val accessToken = requireNotNull(dao.cloudValue(SyncCloudKeys.ACCESS_TOKEN)) { "尚未登入雲端" }
        return CloudHttp.request(
            "POST", "${baseUrl.trimEnd('/')}/v2/devices/transfer", accessToken, JSONObject(),
        ).requireSuccess().getString("transferToken")
    }

    suspend fun delete(scope: String) {
        require(scope == "cloud" || scope == "account")
        val accessToken = requireNotNull(dao.cloudValue(SyncCloudKeys.ACCESS_TOKEN)) { "尚未登入雲端" }
        CloudHttp.request("DELETE", "${baseUrl.trimEnd('/')}/v2/account${if (scope == "cloud") "/cloud" else ""}", accessToken)
            .requireSuccess(allowAccepted = true)
        dao.deleteCloudState(listOf(SyncCloudKeys.ACCESS_TOKEN, SyncCloudKeys.REFRESH_TOKEN, SyncCloudKeys.USER_ID,
            SyncCloudKeys.TRANSFER_COMMIT_TOKEN))
    }
}

internal suspend fun refreshSyncSession(database: StallPosV2Database): String? {
    val dao = database.v2Dao()
    val baseUrl = dao.cloudValue(SyncCloudKeys.BASE_URL) ?: return null
    val refreshToken = dao.cloudValue(SyncCloudKeys.REFRESH_TOKEN) ?: return null
    val device = dao.deviceState() ?: return null
    val response = CloudHttp.request(
        "POST", "${baseUrl.trimEnd('/')}/v2/auth/refresh", body = JSONObject()
            .put("refreshToken", refreshToken)
            .put("deviceId", device.deviceId),
    ).requireSuccess()
    val session = response.session()
    configureSyncSession(database, baseUrl, session.accessToken, session.deviceId, session.cloudEpoch,
        refreshToken = session.refreshToken, userId = session.userId)
    if (session.activeDeviceCount > 0) {
        database.v2Dao().putCloudState(
            listOf(CloudStateEntity(SyncCloudKeys.ACTIVE_DEVICE_COUNT, session.activeDeviceCount.toString())),
        )
    }
    return session.accessToken
}

private data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val deviceId: String,
    val cloudEpoch: Long,
    val additionalDevice: Boolean,
    val activeDeviceCount: Int,
)

private fun JSONObject.session() = CloudSession(
    getString("accessToken"),
    getString("refreshToken"),
    getString("userId"),
    getString("deviceId"),
    getLong("cloudEpoch"),
    optBoolean("additionalDevice", false),
    optInt("activeDeviceCount", 0),
)

private data class CloudResponse(val status: Int, val json: JSONObject)

private fun CloudResponse.requireSuccess(allowAccepted: Boolean = false): JSONObject {
    if (status in 200..299 && (allowAccepted || status != 202)) return json
    val code = json.optString("code", "HTTP_$status")
    val message = json.optString("message", "雲端請求失敗 ($status)")
    if (code in setOf("DEVICE_RETIRED", "CLOUD_EPOCH_REVOKED", "ACCOUNT_DELETED", "DEVICE_LIMIT")) {
        throw CloudLifecycleException(code, message)
    }
    throw IOException("$code: $message")
}

private object CloudHttp {
    suspend fun request(method: String, url: String, bearer: String? = null, body: JSONObject? = null): CloudResponse =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
                bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                body?.let {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = ByteArrayOutputStream()
                input?.use { stream ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (bytes.size() + read > 1_048_576) throw IOException("cloud response is too large")
                        bytes.write(buffer, 0, read)
                    }
                }
                CloudResponse(status, JSONObject(bytes.toString(Charsets.UTF_8.name()).ifBlank { "{}" }))
            } finally {
                connection.disconnect()
            }
        }
}

private fun validBaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)
