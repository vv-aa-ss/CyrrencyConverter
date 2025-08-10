package com.example.currencyconverter


import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext

// ================== Storage keys ==================
private const val PREFS_NAME = "crypto_prefs"
private const val KEY_BYN = "rate_byn_per_usd"     // сколько BYN за 1 USD
private const val KEY_RUB = "rate_rub_per_usd"     // сколько RUB за 1 USD
private const val KEY_MARKUP = "markup_multiplier" // 1.10 = +10%
private const val KEY_LAST_SUCCESS = "last_success_epoch"

private const val KEY_PRICE_BTC = "price_btc_usd"
private const val KEY_PRICE_LTC = "price_ltc_usd"
private const val KEY_PRICE_XMR = "price_xmr_usd"

// ================== Activity ==================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                StaticCryptoScreen()
            }
        }
    }
}

// ================== Persistence helpers ==================
fun saveRates(ctx: Context, byn: Double, rub: Double, markup: Double) {
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(KEY_BYN, byn.toFloat())
        .putFloat(KEY_RUB, rub.toFloat())
        .putFloat(KEY_MARKUP, markup.toFloat())
        .apply()
}

fun loadRates(ctx: Context): Triple<Double, Double, Double> {
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val byn = sp.getFloat(KEY_BYN, 3.0f).toDouble()
    val rub = sp.getFloat(KEY_RUB, 90.0f).toDouble()
    val markup = sp.getFloat(KEY_MARKUP, 1.10f).toDouble()
    return Triple(byn, rub, markup)
}

fun saveLastSuccess(ctx: Context, ts: Long = System.currentTimeMillis()) {
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putLong(KEY_LAST_SUCCESS, ts).apply()
}
fun loadLastSuccess(ctx: Context): Long? {
    val t = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_SUCCESS, 0L)
    return if (t > 0L) t else null
}

fun saveCryptoPrices(ctx: Context, map: Map<String, Double>) {
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
    map["BTC"]?.let { sp.putFloat(KEY_PRICE_BTC, it.toFloat()) }
    map["LTC"]?.let { sp.putFloat(KEY_PRICE_LTC, it.toFloat()) }
    map["XMR"]?.let { sp.putFloat(KEY_PRICE_XMR, it.toFloat()) }
    sp.apply()
}

fun loadCryptoPrices(ctx: Context): Map<String, Double> {
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val btc = sp.getFloat(KEY_PRICE_BTC, 0f).toDouble().takeIf { it > 0 }
    val ltc = sp.getFloat(KEY_PRICE_LTC, 0f).toDouble().takeIf { it > 0 }
    val xmr = sp.getFloat(KEY_PRICE_XMR, 0f).toDouble().takeIf { it > 0 }
    val map = mutableMapOf<String, Double>()
    btc?.let { map["BTC"] = it }
    ltc?.let { map["LTC"] = it }
    xmr?.let { map["XMR"] = it }
    return map
}

// ================== Networking (stub) ==================

suspend fun fetchCryptoPrices(): Map<String, Double> = withContext(Dispatchers.IO) {
    val url = URL(
        "https://api.coingecko.com/api/v3/simple/price" +
                "?ids=bitcoin,litecoin,monero&vs_currencies=usd"
    )
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("Accept", "application/json")
        // опционально: задать user-agent
        setRequestProperty("User-Agent", "CurrencyConverter/1.0")
    }

    try {
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw RuntimeException("HTTP $code")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)

        // Преобразуем к нашему виду
        val btc = json.getJSONObject("bitcoin").getDouble("usd")
        val ltc = json.getJSONObject("litecoin").getDouble("usd")
        val xmr = json.getJSONObject("monero").getDouble("usd")
        mapOf("BTC" to btc, "LTC" to ltc, "XMR" to xmr)
    } finally {
        conn.disconnect()
    }
}

// ================== UI ==================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaticCryptoScreen() {
    val context = LocalContext.current
    val (defaultByn, defaultRub, defaultMarkup) = loadRates(context)

    var btcInput by remember { mutableStateOf("") }
    var ltcInput by remember { mutableStateOf("") }
    var xmrInput by remember { mutableStateOf("") }
    var bynInput by remember { mutableStateOf("") }
    var rubInput by remember { mutableStateOf("") }

    var lastUpdatedMillis by remember { mutableStateOf(loadLastSuccess(context) ?: 0L) }
    var secondsAgo by remember { mutableStateOf<Long?>(null) }

    var bynRate by remember { mutableStateOf(defaultByn) }
    var rubRate by remember { mutableStateOf(defaultRub) }
    var markupRate by remember { mutableStateOf(defaultMarkup) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var prices by remember { mutableStateOf<Map<String, Double>?>(loadCryptoPrices(context).ifEmpty { null }) }
    var showSettings by remember { mutableStateOf(false) }

    // периодическое обновление каждые 3 минуты
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val newPrices = fetchCryptoPrices()
                prices = newPrices
                saveCryptoPrices(context, newPrices)

                val now = System.currentTimeMillis()
                saveLastSuccess(context, now)
                lastUpdatedMillis = now
            } catch (e: Exception) {
                // при ошибке — грузим кеш; время не трогаем
                prices = loadCryptoPrices(context).ifEmpty { prices }
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Не удалось обновить курсы")
                }
            }
            delay(180_000)
        }
    }

    // таймер "сколько прошло"
    LaunchedEffect(lastUpdatedMillis) {
        secondsAgo = if (lastUpdatedMillis > 0L)
            (System.currentTimeMillis() - lastUpdatedMillis) / 1000
        else null

        while (true) {
            delay(1_000)
            secondsAgo = if (lastUpdatedMillis > 0L)
                (System.currentTimeMillis() - lastUpdatedMillis) / 1000
            else null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CryptoChange") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Ввод
            CryptoInputField(
                label = "BTC",
                value = btcInput,
                onChange = {
                    btcInput = it
                    ltcInput = ""; xmrInput = ""; bynInput = ""; rubInput = ""
                },
                iconRes = R.drawable.ic_btc,
                price = prices?.get("BTC")
            )
            CryptoInputField(
                label = "LTC",
                value = ltcInput,
                onChange = {
                    ltcInput = it
                    btcInput = ""; xmrInput = ""; bynInput = ""; rubInput = ""
                },
                iconRes = R.drawable.ic_ltc,
                price = prices?.get("LTC")
            )
            CryptoInputField(
                label = "XMR",
                value = xmrInput,
                onChange = {
                    xmrInput = it
                    btcInput = ""; ltcInput = ""; bynInput = ""; rubInput = ""
                },
                iconRes = R.drawable.ic_xmr,
                price = prices?.get("XMR")
            )
            CryptoInputField(
                label = "BYN",
                value = bynInput,
                onChange = {
                    bynInput = it.replace(',', '.') // на всякий: запятая -> точка
                    btcInput = ""; ltcInput = ""; xmrInput = ""; rubInput = ""
                },
                iconRes = R.drawable.flag_byn,
                price = null
            )
            CryptoInputField(
                label = "RUB",
                value = rubInput,
                onChange = {
                    rubInput = it.replace(',', '.')
                    btcInput = ""; ltcInput = ""; xmrInput = ""; bynInput = ""
                },
                iconRes = R.drawable.flag_rub,
                price = null
            )

            Spacer(Modifier.height(24.dp))

            // Определяем активную валюту и режим
            val (activeCurrency, amount) = remember(btcInput, ltcInput, xmrInput, bynInput, rubInput) {
                when {
                    btcInput.isNotEmpty() -> "BTC" to btcInput.toDoubleOrNull()
                    ltcInput.isNotEmpty() -> "LTC" to ltcInput.toDoubleOrNull()
                    xmrInput.isNotEmpty() -> "XMR" to xmrInput.toDoubleOrNull()
                    bynInput.isNotEmpty() -> "BYN" to bynInput.toDoubleOrNull()
                    rubInput.isNotEmpty() -> "RUB" to rubInput.toDoubleOrNull()
                    else -> null to null
                }
            }
            val isCryptoMode = activeCurrency in listOf("BTC", "LTC", "XMR")

            // Итоги
            if (isCryptoMode) {
                val usdTotal = when (activeCurrency) {
                    "BTC" -> (prices?.get("BTC") ?: 0.0) * (amount ?: 0.0)
                    "LTC" -> (prices?.get("LTC") ?: 0.0) * (amount ?: 0.0)
                    "XMR" -> (prices?.get("XMR") ?: 0.0) * (amount ?: 0.0)
                    else -> 0.0
                }
                val bynTotal = usdTotal * bynRate * markupRate
                val rubTotal = usdTotal * rubRate * markupRate

                ResultField("BYN", bynTotal, R.drawable.flag_byn, fractionDigits = 2)
                ResultField("RUB", rubTotal, R.drawable.flag_rub, fractionDigits = 2)
                ResultField("USD", usdTotal, R.drawable.flag_usd, fractionDigits = 2)
            } else {
                val usdTotal = when (activeCurrency) {
                    "BYN" -> (amount ?: 0.0) / (bynRate * markupRate)
                    "RUB" -> (amount ?: 0.0) / (rubRate * markupRate)
                    else -> 0.0
                }
                val btcTotal = usdTotal / (prices?.get("BTC") ?: Double.NaN)
                val ltcTotal = usdTotal / (prices?.get("LTC") ?: Double.NaN)
                val xmrTotal = usdTotal / (prices?.get("XMR") ?: Double.NaN)

                ResultField("BTC", btcTotal, R.drawable.ic_btc, fractionDigits = 8)
                ResultField("LTC", ltcTotal, R.drawable.ic_ltc, fractionDigits = 6)
                ResultField("XMR", xmrTotal, R.drawable.ic_xmr, fractionDigits = 6)
            }

            Spacer(modifier = Modifier.weight(1f)) // растягивает до низа

            // Футер о последнем успешном обновлении
            val footer = when (val s = secondsAgo) {
                null -> "Курсы ещё не обновлялись"
                in 0..59 -> "Попытка обновления: ${s} сек. назад"
                in 60..3599 -> "Попытка обновления: ${s / 60} мин. назад"
                in 3600..86_399 -> "Попытка обновления: ${s / 3600} ч. назад"
                else -> "Попытка обновления: ${s / 86_400} дн. назад"
            }
            Text(
                text = footer,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp), // небольшой отступ от края
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            bynRate = bynRate,
            rubRate = rubRate,
            markupRate = markupRate,
            onSave = { newByn, newRub, newMarkup ->
                bynRate = newByn
                rubRate = newRub
                markupRate = newMarkup
                saveRates(context, newByn, newRub, newMarkup)
                Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ================== Reusable UI ==================
@Composable
fun CryptoInputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    iconRes: Int,
    price: Double?
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(label)
                if (price != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$" + String.format("%.2f", price),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        )
    )
}

@Composable
fun ResultField(
    label: String,
    value: Double,
    iconRes: Int,
    fractionDigits: Int = 2
) {
    val text = if (value.isFinite()) String.format("%.${fractionDigits}f", value) else "—"
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = value.isFinite()) {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Скопировано: $text", Toast.LENGTH_SHORT).show()
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}


@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    bynRate: Double,
    rubRate: Double,
    markupRate: Double,
    onSave: (Double, Double, Double) -> Unit
) {
    var bynText by remember { mutableStateOf(bynRate.toString()) }
    var rubText by remember { mutableStateOf(rubRate.toString()) }
    var markupText by remember { mutableStateOf(markupRate.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bynText,
                    onValueChange = { bynText = it.replace(',', '.') },
                    label = { Text("Курс BYN за 1 USD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    )
                )
                OutlinedTextField(
                    value = rubText,
                    onValueChange = { rubText = it.replace(',', '.') },
                    label = { Text("Курс RUB за 1 USD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    )
                )
                OutlinedTextField(
                    value = markupText,
                    onValueChange = { markupText = it.replace(',', '.') },
                    label = { Text("Надбавка (1.10 = +10%)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val b = bynText.toDoubleOrNull()
                val r = rubText.toDoubleOrNull()
                val m = markupText.toDoubleOrNull()
                if (b != null && r != null && m != null && b > 0 && r > 0 && m > 0) {
                    onSave(b, r, m)
                    onDismiss()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
