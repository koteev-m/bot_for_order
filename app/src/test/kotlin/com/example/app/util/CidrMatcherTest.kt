package com.example.app.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CidrMatcherTest : StringSpec({
    "matches exact ip" {
        CidrMatcher.isAllowed("10.0.0.5", setOf("10.0.0.5")) shouldBe true
        CidrMatcher.isAllowed("10.0.0.6", setOf("10.0.0.5")) shouldBe false
    }

    "matches ipv6 localhost" {
        CidrMatcher.isAllowed("::1", setOf("::1")) shouldBe true
        CidrMatcher.isAllowed("::2", setOf("::1")) shouldBe false
    }

    "matches cidr range" {
        CidrMatcher.isAllowed("10.1.2.3", setOf("10.0.0.0/8")) shouldBe true
        CidrMatcher.isAllowed("11.0.0.1", setOf("10.0.0.0/8")) shouldBe false
    }

    "ignores malformed entries" {
        CidrMatcher.isAllowed("192.168.1.1", setOf("bad", "192.168.0.0/16")) shouldBe true
        CidrMatcher.isAllowed("192.168.1.1", setOf("bad")) shouldBe false
    }
})
