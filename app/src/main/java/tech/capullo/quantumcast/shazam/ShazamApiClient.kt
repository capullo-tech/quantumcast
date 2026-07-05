package tech.capullo.quantumcast.shazam

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ShazamResponse(@SerializedName("track") val track: ShazamTrack? = null)

data class ShazamTrack(
    @SerializedName("title") val title: String = "",
    @SerializedName("subtitle") val subtitle: String = "",
    @SerializedName("images") val images: ShazamImages? = null,
    @SerializedName("hub") val hub: ShazamHub? = null,
)

data class ShazamImages(
    @SerializedName("coverart") val coverart: String = "",
    @SerializedName("coverarthq") val coverarthq: String = "",
)

data class ShazamHub(
    @SerializedName("type") val type: String = "",
    @SerializedName("actions") val actions: List<ShazamAction> = emptyList(),
    @SerializedName("providers") val providers: List<ShazamProvider> = emptyList(),
)

data class ShazamProvider(
    @SerializedName("type") val type: String = "",
    @SerializedName("actions") val actions: List<ShazamAction> = emptyList(),
)

data class ShazamAction(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("uri") val uri: String = "",
)

object ShazamApiClient {

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun recognize(sig: DecodedMessage): ShazamResponse? {
        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString().uppercase()

        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2" +
            "?sync=true&webv3=true&sampling=true&connected=" +
            "&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"

        val bodyMap = mapOf(
            "timezone" to "America/New_York",
            "signature" to mapOf("uri" to sig.encodeToUri(), "samplems" to sig.sampleMs()),
            "timestamp" to System.currentTimeMillis(),
            "context" to emptyMap<String, Any>(),
            "geolocation" to emptyMap<String, Any>(),
        )

        val req = Request.Builder()
            .url(url)
            .post(gson.toJson(bodyMap).toRequestBody("application/json".toMediaType()))
            .header("X-Shazam-Platform", "IPHONE")
            .header("X-Shazam-AppVersion", "14.1.0")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US")
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 14_7_1 like Mac OS X)")
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Log.d("Shazam", "API status=${resp.code} body=${body.take(1200)}")
                gson.fromJson(body, ShazamResponse::class.java)
            }
        }.onFailure { Log.e("Shazam", "API call failed", it) }.getOrNull()
    }
}
