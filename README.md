# Light Kotlin Multiplatform CBOR
*Full [CBOR](https://cbor.io/) implementation in multiplatform Kotlin that attempts to be robust, easy to use and fast, with minimum allocations*

[![](https://jitpack.io/v/com.darkyen/lkmp-cbor.svg)](https://jitpack.io/#com.darkyen/lkmp-cbor)

Reading and writing bytes is based on custom `ByteRead` + `ByteWrite` = `ByteData` abstraction.
These provide flexible methods to write different binary primitives.

CBOR reading and writing is done through `CborRead` and `CborWrite`.