package com.algotrader.domain.fno

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FnoFixtureTest {

    private fun bd(value: String) = BigDecimal(value)

    private fun availableResult(value: String): FnoResult<BigDecimal> =
        FnoResult.derived(
            value = bd(value),
            evidence = Evidence.VERIFIED,
            dependsOn = setOf(InputId.ENTRY_PRICE),
        )

    @Test
    fun `verified fixture preserves verified provenance`() {
        val fixture = FixtureValue.verified(bd("22520"))

        assertEquals(FixtureStatus.VERIFIED, fixture.status)
        assertEquals(bd("22520"), fixture.value)

        assertEquals(
            Input.Known(bd("22520"), Evidence.VERIFIED),
            fixture.toInput(),
        )
    }

    @Test
    fun `supplied earlier fixture remains supplied evidence`() {
        val fixture = FixtureValue.suppliedEarlier(bd("8.83"))

        assertEquals(FixtureStatus.SUPPLIED_EARLIER, fixture.status)
        assertEquals(bd("8.83"), fixture.value)

        assertEquals(
            Input.Known(bd("8.83"), Evidence.SUPPLIED),
            fixture.toInput(),
        )
    }

    @Test
    fun `unknown fixture never becomes zero`() {
        val fixture = FixtureValue.unknown<BigDecimal>()

        assertNull(fixture.value)
        assertEquals(FixtureStatus.UNKNOWN, fixture.status)
        assertEquals(Input.Unknown, fixture.toInput())
    }

    @Test
    fun `invalid fixture becomes invalid input when converted`() {
        val fixture = FixtureValue.invalid<BigDecimal>("failed screenshot capture")

        assertNull(fixture.value)
        assertEquals(FixtureStatus.INVALID, fixture.status)

        assertEquals(
            Input.Invalid(
                "value comes from an INVALID fixture capture: failed screenshot capture"
            ),
            fixture.toInput(),
        )
    }

    @Test
    fun `unknown and invalid cannot carry a value`() {
        assertFailsWith<IllegalArgumentException> {
            FixtureValue(
                value = bd("0"),
                status = FixtureStatus.UNKNOWN,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            FixtureValue(
                value = bd("0"),
                status = FixtureStatus.INVALID,
            )
        }
    }

    @Test
    fun `unknown expected value is not comparable`() {
        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = availableResult("1463800"),
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.unknown(),
            ),
            tolerance = BigDecimal.ZERO,
        )

        assertEquals(ComparisonState.NOT_COMPARABLE, record.state)
        assertNull(record.expectedValue)
        assertEquals(bd("1463800"), record.computedValue)
        assertEquals("displayed_turnover", record.fixtureField)
    }

    @Test
    fun `invalid expected capture is not comparable and is not a failure`() {
        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = availableResult("1463800"),
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.invalid("zero-valued capture rejected"),
            ),
            tolerance = BigDecimal.ZERO,
        )

        assertEquals(ComparisonState.NOT_COMPARABLE, record.state)
        assertNull(record.expectedValue)
    }

    @Test
    fun `equal known values pass`() {
        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = availableResult("1463800"),
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.verified(bd("1463800")),
            ),
            tolerance = BigDecimal.ZERO,
        )

        assertEquals(ComparisonState.PASS, record.state)
        assertEquals(bd("1463800"), record.expectedValue)
        assertEquals(bd("1463800"), record.computedValue)
        assertEquals(
            listOf(InputId.ENTRY_PRICE),
            record.inputIds,
        )
    }

    @Test
    fun `known values outside tolerance fail`() {
        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = availableResult("1463800"),
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.verified(bd("1463801")),
            ),
            tolerance = bd("0.50"),
        )

        assertEquals(ComparisonState.FAIL, record.state)
        assertEquals(
            bd("1"),
            bd("1463800").subtract(record.expectedValue!!).abs(),
        )
    }

    @Test
    fun `known values within tolerance pass`() {
        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = availableResult("1463800.25"),
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.verified(bd("1463800")),
            ),
            tolerance = bd("0.50"),
        )

        assertEquals(ComparisonState.PASS, record.state)
    }

    @Test
    fun `invalid computed result is invalid input rather than not comparable`() {
        val actual = FnoResult.invalid<BigDecimal>(
            dependsOn = setOf(InputId.ENTRY_PRICE),
            invalid = setOf(InputId.ENTRY_PRICE),
            warnings = listOf("I10 ENTRY_PRICE: invalid price"),
        )

        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = actual,
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.verified(bd("1463800")),
            ),
            tolerance = BigDecimal.ZERO,
        )

        assertEquals(ComparisonState.INVALID_INPUT, record.state)
        assertNull(record.computedValue)
        assertEquals(bd("1463800"), record.expectedValue)
    }

    @Test
    fun `missing computed input is not comparable`() {
        val actual = FnoResult.notModelled<BigDecimal>(
            dependsOn = setOf(InputId.ENTRY_PRICE, InputId.EXIT_PRICE),
            missing = setOf(InputId.EXIT_PRICE),
        )

        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = actual,
            expected = FixtureField(
                name = "displayed_turnover",
                captured = FixtureValue.verified(bd("1463800")),
            ),
            tolerance = BigDecimal.ZERO,
        )

        assertEquals(ComparisonState.NOT_COMPARABLE, record.state)
        assertNull(record.computedValue)
    }

    @Test
    fun `negative tolerance is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FnoFixtureComparison.compare(
                computedOutput = "contract_leg_value",
                actual = availableResult("100"),
                expected = FixtureField(
                    name = "displayed_turnover",
                    captured = FixtureValue.verified(bd("100")),
                ),
                tolerance = bd("-0.01"),
            )
        }
    }

    @Test
    fun `comparison record keeps explicit fixture field and tolerance`() {
        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_leg_value",
            actual = availableResult("100"),
            expected = FixtureField(
                name = "exchange_displayed_turnover",
                captured = FixtureValue.verified(bd("100")),
            ),
            tolerance = bd("0.50"),
            inputIds = setOf(
                InputId.ENTRY_PRICE,
                InputId.LOT_SIZE,
                InputId.LOTS,
            ),
        )

        assertEquals("contract_leg_value", record.computedOutput)
        assertEquals("exchange_displayed_turnover", record.fixtureField)
        assertEquals(bd("0.50"), record.tolerance)
        assertEquals(
            listOf(
                InputId.ENTRY_PRICE,
                InputId.LOT_SIZE,
                InputId.LOTS,
            ),
            record.inputIds,
        )
    }
}
