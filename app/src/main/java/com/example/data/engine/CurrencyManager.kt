package com.example.data.engine

import com.example.data.model.GameState
import com.example.data.model.StrategyConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class SupportedCurrency(
    val code: String,
    val symbol: String,
    val displayLabel: String,
    val shortCountry: String,
    val prefixSymbol: Boolean = false,
    val defaultDecimals: Int = 2,
    val defaultPresets: List<Int> = listOf(100, 250, 500, 1500)
) {
    AUTO(
        code = "AUTO",
        symbol = "TZS",
        displayLabel = "Auto-Detect (URL / Balance)",
        shortCountry = "Auto",
        prefixSymbol = false,
        defaultDecimals = 2,
        defaultPresets = listOf(500, 1000, 5000, 15000)
    ),
    TZS(
        code = "TZS",
        symbol = "TZS",
        displayLabel = "Tanzania (TZS — Shilling)",
        shortCountry = "TZ",
        prefixSymbol = false,
        defaultDecimals = 2,
        defaultPresets = listOf(500, 1000, 5000, 15000)
    ),
    ZMW(
        code = "ZMW",
        symbol = "ZMW",
        displayLabel = "Zambia (ZMW — Kwacha)",
        shortCountry = "ZM",
        prefixSymbol = false,
        defaultDecimals = 2,
        defaultPresets = listOf(50, 150, 500, 1000)
    ),
    KES(
        code = "KES",
        symbol = "KES",
        displayLabel = "Kenya (KES — Shilling)",
        shortCountry = "KE",
        prefixSymbol = false,
        defaultDecimals = 2,
        defaultPresets = listOf(100, 250, 500, 1500)
    ),
    UGX(
        code = "UGX",
        symbol = "UGX",
        displayLabel = "Uganda (UGX — Shilling)",
        shortCountry = "UG",
        prefixSymbol = false,
        defaultDecimals = 0,
        defaultPresets = listOf(1000, 2500, 5000, 15000)
    ),
    NGN(
        code = "NGN",
        symbol = "NGN",
        displayLabel = "Nigeria (NGN — Naira)",
        shortCountry = "NG",
        prefixSymbol = false,
        defaultDecimals = 2,
        defaultPresets = listOf(500, 1000, 5000, 10000)
    ),
    GHS(
        code = "GHS",
        symbol = "GHS",
        displayLabel = "Ghana (GHS — Cedi)",
        shortCountry = "GH",
        prefixSymbol = false,
        defaultDecimals = 2,
        defaultPresets = listOf(50, 100, 250, 500)
    ),
    USD(
        code = "USD",
        symbol = "$",
        displayLabel = "US Dollar (USD)",
        shortCountry = "US",
        prefixSymbol = true,
        defaultDecimals = 2,
        defaultPresets = listOf(100, 250, 500, 1500)
    );

    companion object {
        fun fromCode(raw: String?): SupportedCurrency {
            val upper = raw?.trim()?.uppercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.code == upper } ?: AUTO
        }
    }
}

object CurrencyManager {
    private val _activeCurrency = MutableStateFlow(SupportedCurrency.TZS)
    val activeCurrency: StateFlow<SupportedCurrency> = _activeCurrency.asStateFlow()

    fun updateActiveCurrency(currency: SupportedCurrency) {
        val resolved = if (currency == SupportedCurrency.AUTO) SupportedCurrency.TZS else currency
        _activeCurrency.value = resolved
    }

    fun updateFromConfigAndGameState(config: StrategyConfig, gameState: GameState) {
        val resolved = detectCurrency(
            manualPreference = config.currencyPreference,
            detectedUrl = gameState.detectedUrl,
            detectedCurrencyCode = gameState.detectedCurrency,
            pageHints = emptyList()
        )
        _activeCurrency.value = resolved
    }

    /**
     * Resolves active currency either from an explicit manual user selection or automatically
     * from balance text (e.g. "10.18 TZS") and domain (e.g. "betpawa.co.tz").
     */
    fun detectCurrency(
        manualPreference: String = "AUTO",
        detectedUrl: String? = null,
        detectedCurrencyCode: String? = null,
        pageHints: List<String> = emptyList()
    ): SupportedCurrency {
        val pref = SupportedCurrency.fromCode(manualPreference)
        if (pref != SupportedCurrency.AUTO) {
            return pref
        }

        // 1. Direct balance readout currency (e.g., "10.18 TZS", "250 NGN")
        parseCurrencyCodeFromText(detectedCurrencyCode)?.let { return it }

        // 2. Active browser URL / TLD / path country code
        val urlLower = detectedUrl?.lowercase(Locale.US).orEmpty()
        if (urlLower.isNotBlank()) {
            when {
                urlLower.contains(".co.tz") || urlLower.contains("/tz") || urlLower.contains(".tz") -> return SupportedCurrency.TZS
                urlLower.contains(".co.zm") || urlLower.contains("/zm") || urlLower.contains(".zm") -> return SupportedCurrency.ZMW
                urlLower.contains(".co.ke") || urlLower.contains("/ke") || urlLower.contains(".ke") -> return SupportedCurrency.KES
                urlLower.contains(".co.ug") || urlLower.contains("/ug") || urlLower.contains(".ug") -> return SupportedCurrency.UGX
                urlLower.contains(".com.ng") || urlLower.contains(".ng") || urlLower.contains("/ng") -> return SupportedCurrency.NGN
                urlLower.contains(".com.gh") || urlLower.contains(".gh") || urlLower.contains("/gh") -> return SupportedCurrency.GHS
            }
        }

        // 3. Page text hints fallback
        val hintsCombined = pageHints.joinToString(" ")
        parseCurrencyCodeFromText(hintsCombined)?.let { return it }

        return SupportedCurrency.TZS
    }

    fun parseCurrencyCodeFromText(text: String?): SupportedCurrency? {
        if (text.isNullOrBlank()) return null
        val upper = text.uppercase(Locale.US)
        return when {
            upper.contains("TZS") || upper.contains("TSH") -> SupportedCurrency.TZS
            upper.contains("ZMW") || upper.contains("KWA") -> SupportedCurrency.ZMW
            upper.contains("KES") || upper.contains("KSH") -> SupportedCurrency.KES
            upper.contains("UGX") || upper.contains("USH") -> SupportedCurrency.UGX
            upper.contains("NGN") || upper.contains("₦") -> SupportedCurrency.NGN
            upper.contains("GHS") || upper.contains("GH₵") -> SupportedCurrency.GHS
            upper.contains("USD") || (upper.contains("$") && !upper.contains("GH")) -> SupportedCurrency.USD
            else -> null
        }
    }

    fun formatAmount(
        amount: Double,
        currency: SupportedCurrency = _activeCurrency.value,
        forceDecimals: Int? = null
    ): String {
        val eff = if (currency == SupportedCurrency.AUTO) SupportedCurrency.TZS else currency
        val safe = if (amount.isFinite()) amount else 0.0
        val decimals = forceDecimals ?: eff.defaultDecimals
        val formattedNumber = if (decimals <= 0) {
            String.format(Locale.US, "%.0f", safe)
        } else {
            String.format(Locale.US, "%.${decimals}f", safe)
        }
        return if (eff.prefixSymbol) {
            "${eff.symbol}$formattedNumber"
        } else {
            "$formattedNumber ${eff.symbol}"
        }
    }

    fun formatCompact(
        amount: Double,
        currency: SupportedCurrency = _activeCurrency.value
    ): String {
        val decimals = if (amount % 1.0 == 0.0) 0 else 2
        return formatAmount(amount, currency, forceDecimals = decimals)
    }

    fun formatSignedAmount(
        amount: Double,
        currency: SupportedCurrency = _activeCurrency.value
    ): String {
        val eff = if (currency == SupportedCurrency.AUTO) SupportedCurrency.TZS else currency
        val safe = if (amount.isFinite()) amount else 0.0
        val sign = when {
            safe > 0.004 -> "+"
            safe < -0.004 -> "-"
            else -> ""
        }
        val absVal = kotlin.math.abs(safe)
        val formattedNumber = if (eff.defaultDecimals <= 0) {
            String.format(Locale.US, "%.0f", absVal)
        } else {
            String.format(Locale.US, "%.${eff.defaultDecimals}f", absVal)
        }
        return if (eff.prefixSymbol) {
            "$sign${eff.symbol}$formattedNumber"
        } else {
            "$sign$formattedNumber ${eff.symbol}"
        }
    }
}
