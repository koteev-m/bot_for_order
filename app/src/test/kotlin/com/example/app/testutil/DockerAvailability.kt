package com.example.app.testutil

import org.testcontainers.DockerClientFactory

fun isDockerAvailable(): Boolean =
    try {
        DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }
