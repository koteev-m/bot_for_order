package com.example.app.storage

import com.example.app.config.StorageConfig
import java.io.InputStream
import java.net.URI
import java.time.Duration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

class S3Storage(
    private val config: StorageConfig
) : Storage {
    private val keyPrefix = config.pathPrefix?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.accessKey, config.secretKey)
    )
    private val region = Region.of(config.region)
    private val endpoint = URI(config.endpoint)
    private val s3Configuration = S3Configuration.builder()
        .pathStyleAccessEnabled(true)
        .build()

    private val s3Client: S3Client = S3Client.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(credentials)
        .region(region)
        .serviceConfiguration(s3Configuration)
        .build()

    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(credentials)
        .region(region)
        .serviceConfiguration(s3Configuration)
        .build()

    override fun putObject(stream: InputStream, key: String, contentType: String, size: Long) {
        val normalizedKey = normalizeKey(key)
        val request = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(normalizedKey)
            .contentType(contentType)
            .contentLength(size)
            .build()
        s3Client.putObject(request, RequestBody.fromInputStream(stream, size))
    }

    override fun presignGet(key: String, ttl: Duration): String {
        val normalizedKey = normalizeKey(key)
        val getRequest = GetObjectRequest.builder()
            .bucket(config.bucket)
            .key(normalizedKey)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getRequest)
            .build()
        return presigner.presignGetObject(presignRequest).url().toString()
    }

    private fun normalizeKey(key: String): String {
        val trimmed = key.trim().trimStart('/')
        return keyPrefix?.let { "$it/$trimmed" } ?: trimmed
    }
}
