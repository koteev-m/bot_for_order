package com.example.app.testutil

import org.testcontainers.DockerClientFactory

fun isDockerAvailable(): Boolean = DockerClientFactory.instance().isDockerAvailable
