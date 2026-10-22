package app.familyhub.contract.serialization

import app.familyhub.contract.api.FamilyHubJson
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
        assertEquals("\"red\"", FamilyHubJson.encodeToString(ColorSerializer, Color.RED))
        assertEquals(Color.RED, FamilyHubJson.decodeFromString(ColorSerializer, "\"red\""))
        assertEquals(Color.GREEN, FamilyHubJson.decodeFromString(ColorSerializer, "\"GREEN\""))
    }

    @Test
    fun unknownValueDecodesToFallback() {
        assertEquals(Color.OTHER, FamilyHubJson.decodeFromString(ColorSerializer, "\"SOMETHING_NEW\""))
    }

    @Test
    fun setDropsUnknownValues() {
        val palette = FamilyHubJson.decodeFromString<Palette>("""{"colors":["red","SOMETHING_NEW","GREEN"]}""")
        assertEquals(setOf(Color.RED, Color.GREEN), palette.colors)
    }

    @Test
    fun setEncodesSerialNames() {
        assertEquals("""{"colors":["red"]}""", FamilyHubJson.encodeToString(Palette(setOf(Color.RED))))
    }
}
