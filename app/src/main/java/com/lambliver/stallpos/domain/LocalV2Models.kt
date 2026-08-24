package com.lambliver.stallpos.domain

import java.util.UUID

enum class MarketEventType { CONVENTION, MARKET, POPUP, OTHER }
enum class MarketEventStatus { PLANNED, ACTIVE, CLOSED }

data class MarketEvent(
    val id: String,
    val name: String,
    val code: String,
    val type: MarketEventType,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val actualOpenAtMillis: Long? = null,
    val actualCloseAtMillis: Long? = null,
    val timezone: String,
    val location: String = "",
    val status: MarketEventStatus = MarketEventStatus.PLANNED,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long? = null,
) {
    init {
        require(name.isNotBlank())
        require(code.matches(Regex("[A-Z0-9-]{2,12}")))
        require(endAtMillis >= startAtMillis)
        require(timezone.isNotBlank())
    }

    companion object {
        fun create(
            name: String,
            type: MarketEventType,
            startAtMillis: Long,
            endAtMillis: Long,
            timezone: String,
            location: String = "",
            nowMillis: Long = System.currentTimeMillis(),
        ): MarketEvent {
            val id = UUID.randomUUID().toString()
            return MarketEvent(
                id = id,
                name = name.trim(),
                code = "EVT-${id.take(8).uppercase()}",
                type = type,
                startAtMillis = startAtMillis,
                endAtMillis = endAtMillis,
                timezone = timezone,
                location = location.trim(),
                updatedAtMillis = nowMillis,
            )
        }
    }
}

enum class InventoryLocationType { GENERAL, EVENT }

data class InventoryLocation(
    val type: InventoryLocationType,
    val eventId: String? = null,
) {
    init {
        require((type == InventoryLocationType.GENERAL) == (eventId == null))
    }

    val key: String = if (type == InventoryLocationType.GENERAL) GENERAL_KEY else "EVENT:$eventId"

    companion object {
        const val GENERAL_KEY = "GENERAL"
        val General = InventoryLocation(InventoryLocationType.GENERAL)
        fun event(eventId: String) = InventoryLocation(InventoryLocationType.EVENT, eventId)
    }
}

data class InventoryLevel(
    val productId: String,
    val location: InventoryLocation,
    val quantity: Long,
    val updatedAtMillis: Long,
)

enum class InventoryMovementType {
    ALLOCATE_TO_EVENT,
    SALE,
    VOID,
    RETURN_FROM_EVENT,
    DAMAGE,
    ADJUSTMENT,
}

data class InventoryMovement(
    val id: String,
    val productId: String,
    val eventId: String?,
    val from: InventoryLocation?,
    val to: InventoryLocation?,
    val type: InventoryMovementType,
    val quantity: Long,
    val relatedTransactionId: String?,
    val occurredAtMillis: Long,
)
