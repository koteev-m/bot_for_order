package com.example.app.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

@Serializable
data class BuildInfo(
    val version: String = "unknown",
    val commit: String = "unknown",
    val branch: String = "unknown",
    val builtAt: String = "unknown",
)

/** Loads build metadata packaged into resources. */
object BuildInfoProvider {
    private val json = Json { ignoreUnknownKeys = true }

    private val cached: BuildInfo by lazy { loadBuildInfo() }

    fun current(): BuildInfo = cached

    private fun loadBuildInfo(): BuildInfo {
        val resource = javaClass.classLoader.getResourceAsStream("build-info.json") ?: return BuildInfo()
        return runCatching {
            resource.use { stream ->
                val payload = stream.readBytes().toString(StandardCharsets.UTF_8)
                json.decodeFromString(BuildInfo.serializer(), payload)
            }
        }.getOrElse { BuildInfo() }
    }
}

/**
 * Registers the build_info gauge with static value `1` and build metadata tags.
 */
fun registerBuildInfoMeter(registry: MeterRegistry?) {
    if (registry == null) return
    val build = BuildInfoProvider.current()
    Gauge.builder("build_info") { 1.0 }
        .description("Build metadata")
        .tags(
            Tags.of(
                "version", build.version,
                "commit", build.commit,
                "branch", build.branch
            )
        )
        .register(registry)
}
