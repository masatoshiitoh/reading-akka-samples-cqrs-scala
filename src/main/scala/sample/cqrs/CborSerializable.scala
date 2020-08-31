package sample.cqrs

/**
 * Marker trait for serialization with Jackson CBOR
 *
 * CborSerializableトレイトを継承することで、Jackson CBORによるシリアライズをおこなう、という印にしている、ということか。
 * でも、Jackson CBORと直接関連付けているところはどこだ？？？
 */
trait CborSerializable
