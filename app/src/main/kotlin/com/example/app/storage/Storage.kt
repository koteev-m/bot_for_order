package com.example.app.storage

import java.io.InputStream
import java.time.Duration

interface Storage {
    fun putObject(stream: InputStream, key: String, contentType: String, size: Long)
    fun presignGet(key: String, ttl: Duration): String
}
