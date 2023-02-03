# Ultralight CBOR
*Full [CBOR](https://cbor.io/) implementation in multiplatform Kotlin that attempts to be robust, easy to use and fast, with minimum allocations*

Reading and writing bytes is based on custom `ByteRead` + `ByteWrite` = `ByteData` abstraction.
These provide flexible methods to write different binary primitives.