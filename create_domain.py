from pathlib import Path

base = Path("core/domain/src/main/kotlin/com/algotrader/domain")
base.mkdir(parents=True, exist_ok=True)

files = {
    "Timeframe.kt": """package com.algotrader.domain

enum class Timeframe {
    MINUTE_1,
    MINUTE_5,
    MINUTE_15,
    MINUTE_30,
    HOUR_1,
    HOUR_4,
    DAY_1
}
""",

    "Instrument.kt": """package com.algotrader.domain

data class Instrument(
    val symbol: String,
    val exchange: String,
    val currency: String = "INR"
)
""",

    "Candle.kt": """package com.algotrader.domain

import java.time.Instant

data class Candle(
    val instrument: Instrument,
    val timeframe: Timeframe,
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)
""",

    "Order.kt": """package com.algotrader.domain

import java.time.Instant

enum class OrderSide {
    BUY,
    SELL
}

enum class OrderType {
    MARKET,
    LIMIT,
    STOP
}

enum class OrderStatus {
    PENDING,
    FILLED,
    PARTIALLY_FILLED,
    CANCELLED,
    REJECTED
}

data class Order(
    val id: String,
    val instrument: Instrument,
    val side: OrderSide,
    val type: OrderType,
    val quantity: Double,
    val price: Double? = null,
    val timestamp: Instant = Instant.now(),
    val status: OrderStatus = OrderStatus.PENDING
)
""",

    "Position.kt": """package com.algotrader.domain

data class Position(
    val instrument: Instrument,
    val quantity: Double,
    val averagePrice: Double
) {
    val marketValue: Double
        get() = quantity * averagePrice
}
""",

    "Portfolio.kt": """package com.algotrader.domain

data class Portfolio(
    val cash: Double,
    val positions: List<Position> = emptyList()
) {
    val investedValue: Double
        get() = positions.sumOf { it.marketValue }

    val totalValue: Double
        get() = cash + investedValue
}
""",
}

for filename, content in files.items():
    path = base / filename
    path.write_text(content)
    print(f"Created: {path}")

print(f"Created {len(files)} domain model files.")
