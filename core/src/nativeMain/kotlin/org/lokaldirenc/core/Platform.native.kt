package org.lokaldirenc.core

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Yerel hedefler şimdilik yalnız ortak kodun taşınabilirliğini denetler. Uygulama için
// platformun şifreleme kitaplığı buraya bağlanmalı ve protokol vektörlerinden geçmelidir.
internal actual val platformCrypto: CryptoProvider
    get() = error("Bu platform için şifreleme uygulaması henüz yok")

@OptIn(ExperimentalTime::class)
internal actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
