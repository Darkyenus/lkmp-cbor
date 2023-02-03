package com.darkyen.ucbor


object CborSerializers {
    object IntSerializer : CborSerializer<Int> {
        override fun CborWrite.serialize(value: Int) {
            int(value.toLong())
        }

        override fun CborReadSingle.deserialize(): Int {
            return int32()
        }
    }

    object LongSerializer : CborSerializer<Long> {
        override fun CborWrite.serialize(value: Long) {
            int(value)
        }

        override fun CborReadSingle.deserialize(): Long {
            return int()
        }
    }

    object StringSerializer : CborSerializer<String> {
        override fun CborWrite.serialize(value: String) {
            string(value)
        }

        override fun CborReadSingle.deserialize(): String {
            return string().toString()
        }
    }

    @Suppress("BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER")
    class MapSerializer<K, V, M, MM> (
        private val keySerializer: CborSerializer<K>,
        private val valueSerializer: CborSerializer<V>,
        private val newMap: (capacity: Int) -> MM
    ) : CborSerializer<M> where MM:MutableMap<K,V>, MM:M, M:Map<K, V> {

        override fun CborWrite.serialize(value: M) {
            map(value, { value(it, keySerializer) }, { value(it, valueSerializer) })
        }

        override fun CborReadSingle.deserialize(): MM {
            return map(newMap, { value(keySerializer) }, { value(valueSerializer) })
        }
    }

    class EnumSerializer<E : Enum<E>>(val values: Array<E>): CborSerializer<E> {
        override fun CborWrite.serialize(value: E) {
            int(value.ordinal)
        }

        override fun CborReadSingle.deserialize(): E {
            val ordinal = int32()
            return if (ordinal !in values.indices) {
                values[0]
            } else {
                values[ordinal]
            }
        }
    }
}