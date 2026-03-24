package org.maocide.undeadwallpaper

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.maocide.undeadwallpaper.model.AppState

// Plain JSON is enough for this state.
object AppStateSerializer : Serializer<AppState> {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    override val defaultValue: AppState = AppState()

    override suspend fun readFrom(input: InputStream): AppState {
        return try {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) {
                defaultValue
            } else {
                json.decodeFromString(AppState.serializer(), bytes.decodeToString())
            }
        } catch (error: SerializationException) {
            throw CorruptionException("Unable to read app state.", error)
        }
    }

    override suspend fun writeTo(t: AppState, output: OutputStream) {
        output.write(json.encodeToString(AppState.serializer(), t).encodeToByteArray())
    }
}
