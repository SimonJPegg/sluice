package org.antipathy.sluice.core.policy

import kotlin.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException

/** provides serialization to ISO-8601 formats */
internal object ISOFormatDurationSerializer : KSerializer<Duration> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("Duration", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Duration) {
    encoder.encodeString(value.toIsoString())
  }

  override fun deserialize(decoder: Decoder): Duration {
    return Duration.parseIsoString(decoder.decodeString())
  }
}

internal object UIntSerializer : KSerializer<UInt> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("UInt", PrimitiveKind.INT)

  override fun serialize(encoder: Encoder, value: UInt) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): UInt {
    val v = decoder.decodeInt()
    if (v < 0) throw InvalidPolicyConfigurationException("limits must be 0 or greater, got $v")
    return v.toUInt()
  }
}

internal object EnumSerializer {
  // reified + inline function grants us access to the type information inside the function call
  inline fun <reified T : Enum<T>> createSerializer(): KSerializer<T> =
      object : KSerializer<T> {
        override val descriptor: SerialDescriptor
          get() = PrimitiveSerialDescriptor(T::class.simpleName!!, PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: T) {
          encoder.encodeString(value.name)
        }

        override fun deserialize(decoder: Decoder): T {
          val name = decoder.decodeString()
          return enumValues<T>().first { it.name.equals(name, ignoreCase = true) }
        }
      }
}

internal object FailTypeSerializer : KSerializer<FailType> by EnumSerializer.createSerializer()

internal object AlgorithmTypeSerializer :
    KSerializer<AlgorithmType> by EnumSerializer.createSerializer()
