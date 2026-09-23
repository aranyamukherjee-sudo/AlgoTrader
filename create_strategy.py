from pathlib import Path

base = Path("core/strategy/src/main/kotlin/com/algotrader/strategy")
base.mkdir(parents=True, exist_ok=True)

files = {
    "SignalType.kt": """package com.algotrader.strategy

enum class SignalType {
    BUY,
    SELL,
    HOLD
}
""",

    "Signal.kt": """package com.algotrader.strategy

import com.algotrader.domain.Instrument
import java.time.Instant

data class Signal(
    val instrument: Instrument,
    val type: SignalType,
    val timestamp: Instant,
    val confidence: Double = 0.0,
    val reason: String = ""
)
""",

    "StrategyContext.kt": """package com.algotrader.strategy

import com.algotrader.domain.Candle
import com.algotrader.domain.Portfolio

data class StrategyContext(
    val candles: List<Candle>,
    val portfolio: Portfolio
)
""",

    "Strategy.kt": """package com.algotrader.strategy

interface Strategy {

    val name: String

    fun evaluate(context: StrategyContext): List<Signal>
}
""",
}

for filename, content in files.items():
    path = base / filename
    path.write_text(content)
    print(f"Created: {path}")

print(f"Created {len(files)} strategy files.")
