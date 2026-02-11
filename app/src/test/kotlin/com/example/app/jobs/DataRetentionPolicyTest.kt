package com.example.app.jobs

import com.example.app.baseTestConfig
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DataRetentionPolicyTest {
    @Test
    fun `buildCutoffs uses configured retention days`() {
        val base = baseTestConfig()
        val cfg = base.copy(
            retention = base.retention.copy(
                pii = base.retention.pii.copy(
                    auditLogDays = 11,
                    orderDeliveryDays = 22,
                ),
                technical = base.retention.technical.copy(
                    outboxDays = 33,
                    webhookDedupDays = 44,
                    idempotencyDays = 55,
                )
            )
        )
        val now = Instant.parse("2025-01-15T12:00:00Z")

        val cutoffs = DataRetentionPolicy.buildCutoffs(cfg, now)

        assertEquals(Instant.parse("2025-01-04T12:00:00Z"), cutoffs.auditLogBefore)
        assertEquals(Instant.parse("2024-12-24T12:00:00Z"), cutoffs.orderDeliveryBefore)
        assertEquals(Instant.parse("2024-12-13T12:00:00Z"), cutoffs.outboxBefore)
        assertEquals(Instant.parse("2024-12-02T12:00:00Z"), cutoffs.webhookDedupBefore)
        assertEquals(Instant.parse("2024-11-21T12:00:00Z"), cutoffs.idempotencyBefore)
    }

    @Test
    fun `completed statuses for order pii purge are stable`() {
        assertEquals(
            listOf("delivered", "canceled", "PAID_CONFIRMED"),
            DataRetentionPolicy.completedOrderStatuses.map { it.name }
        )
    }
}
