package app.familyhub.contract.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Decodes an enum from its serial name and maps unknown names to [fallback].
 *
 * A newer server may send values an older client does not know yet; decoding must never fail because of that.
 * Serial names (including `@SerialName`) are taken from [generated], the plugin-generated serializer of the enum.
 * Without a [fallback], a single unknown value fails to decode; use such a serializer only as the element of a
 * [LenientEnumSetSerializer], which drops unknown values instead.
 */
open class LenientEnumSerializer<E : Enum<E>>(
    generated: KSerializer<E>,
    private val entries: List<E>,
    private val fallback: E?,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("${generated.descriptor.serialName}.Lenient", PrimitiveKind.STRING)

    private val nameOf: Map<E, String> =
        entries.associateWith { entry -> generated.descriptor.getElementName(entry.ordinal) }
    private val byName: Map<String, E> = nameOf.entries.associate { (entry, name) -> name to entry }

    /** Returns the entry with this serial name, or `null` if this version does not know it. */
    fun decodeOrNull(name: String): E? = byName[name]

    fun serialName(value: E): String = nameOf.getValue(value)

    override fun serialize(
        encoder: Encoder,
        value: E,
    ) = encoder.encodeString(serialName(value))

    override fun deserialize(decoder: Decoder): E {
        val name = decoder.decodeString()
        return decodeOrNull(name) ?: fallback ?: throw SerializationException("Unknown value '$name' for ${descriptor.serialName}")
    }
}

/** Decodes a list of enum serial names into a set, dropping values this version does not know. */
open class LenientEnumSetSerializer<E : Enum<E>>(
    private val element: LenientEnumSerializer<E>,
) : KSerializer<Set<E>> {
    private val names = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Set<E>,
    ) = names.serialize(encoder, value.map(element::serialName))

    override fun deserialize(decoder: Decoder): Set<E> = names.deserialize(decoder).mapNotNull(element::decodeOrNull).toSet()
}
