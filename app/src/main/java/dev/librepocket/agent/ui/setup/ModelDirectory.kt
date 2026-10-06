package dev.librepocket.agent.ui.setup

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * models.dev directory transport (P1 SPEC §10.3): plain GET, never sends
 * Authorization (reading the directory carries no key). Failures surface as
 * exceptions; callers fall back to [dev.librepocket.models.ModelsDevSnapshot.bundledSnapshot].
 */
object ModelDirectory {
    suspend fun fetchBody(client: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MODELS_DIRECTORY_HTTP_${response.code}")
            response.body?.string() ?: throw IOException("MODELS_DIRECTORY_EMPTY")
        }
    }
}
