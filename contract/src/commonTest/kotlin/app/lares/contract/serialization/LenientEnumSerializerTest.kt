package app.lares.contract.serialization

import app.lares.contract.api.LaresJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ColorSerializer::class)
private enum class Color {
    @SerialName("red")
    RED,
    GREEN,
    OTHER,
}

@OptIn(ExperimentalSerializationApi::class)
private object ColorSerializer : LenientEnumSerializer<Color>(Color.generatedSerializer(), Color.entries, Color.OTHER)

private object ColorSetSerializer : LenientEnumSetSerializer<Color>(ColorSerializer)

@Serializable
private data class Palette(
    @Serializable(with = ColorSetSerializer::class) val colors: Set<Color>,
)

class LenientEnumSerializerTest {
    @Test
    fun usesSerialNames() {
        assertEquals("\"red\"", LaresJson.encodeToString(ColorSerializer, Color.RED))
        assertEquals(Color.RED, LaresJson.decodeFromString(ColorSerializer, "\"red\""))
        assertEquals(Color.GREEN, LaresJson.decodeFromString(ColorSerializer, "\"GREEN\""))
    }

    @Test
    fun unknownValueDecodesToFallback() {
        assertEquals(Color.OTHER, LaresJson.decodeFromString(ColorSerializer, "\"SOMETHING_NEW\""))
    }

    @Test
    fun setDropsUnknownValues() {
        val palette = LaresJson.decodeFromString<Palette>("""{"colors":["red","SOMETHING_NEW","GREEN"]}""")
        assertEquals(setOf(Color.RED, Color.GREEN), palette.colors)
    }

    @Test
    fun setEncodesSerialNames() {
        assertEquals("""{"colors":["red"]}""", LaresJson.encodeToString(Palette(setOf(Color.RED))))
    }
}
