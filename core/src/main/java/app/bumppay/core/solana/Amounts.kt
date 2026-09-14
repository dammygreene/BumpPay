package app.bumppay.core.solana

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Decimal-to-base-unit conversion.
 *
 * This is where a payments app gets sued. `25.00` USDC is `25_000_000` base units and the
 * conversion must be exact — `Double` arithmetic turns 0.1 into 0.1000000000000000055, and
 * a rounding error of one base unit is a real, if tiny, transfer of value.
 *
 * Everything therefore goes through [BigDecimal], and any conversion that would silently
 * drop precision throws instead of rounding.
 */
object Amounts {

    /**
     * Converts a human amount (`25.50`) into base units (`25_500_000`) at the given mint
     * decimals.
     *
     * @throws IllegalArgumentException if the amount has more decimal places than the mint
     *   supports, or is negative. Both are caller bugs, not user-input problems, by the
     *   time we get here.
     */
    fun toBaseUnits(amount: BigDecimal, decimals: Int): Long {
        require(decimals >= 0) { "decimals must not be negative" }
        require(amount.signum() >= 0) { "amount must not be negative, got $amount" }
        require(amount.scale() <= decimals) {
            "$amount has ${amount.scale()} decimal places but the mint only supports $decimals; " +
                "refusing to silently round away value"
        }

        val scaled = amount.movePointRight(decimals)
            .setScale(0, RoundingMode.UNNECESSARY)

        require(scaled <= BigDecimal.valueOf(Long.MAX_VALUE)) { "amount $amount overflows a u64" }
        return scaled.toLong()
    }

    fun fromBaseUnits(baseUnits: Long, decimals: Int): BigDecimal =
        BigDecimal.valueOf(baseUnits).movePointLeft(decimals)

    /** Renders `25.5` as `"25.50 USDC"` for the UI and for log lines. */
    fun format(baseUnits: Long, decimals: Int, symbol: String): String =
        fromBaseUnits(baseUnits, decimals).setScale(decimals, RoundingMode.UNNECESSARY)
            .toPlainString() + " " + symbol

    /** Formats for a receipt: always exactly [decimals] places so columns line up. */
    fun formatPlain(baseUnits: Long, decimals: Int): String =
        fromBaseUnits(baseUnits, decimals).setScale(decimals, RoundingMode.UNNECESSARY).toPlainString()
}
