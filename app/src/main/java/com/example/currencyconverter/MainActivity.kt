package com.example.currencyconverter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.KeyboardType


//import androidx.compose.ui.text.input.KeyboardOptions
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*

import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "app_prefs"
private const val KEY_BYN = "byn_rate"
private const val KEY_RUB = "rub_rate"
private const val KEY_MARKUP = "markup_rate"
private const val KEY_BTC = "btc_price"
private const val KEY_LTC = "ltc_price"
private const val KEY_XMR = "xmr_price"


fun saveCryptoPrices(context: Context, prices: Map<String, Double>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putFloat(KEY_BTC, prices["BTC"]?.toFloat() ?: 0f)
        .putFloat(KEY_LTC, prices["LTC"]?.toFloat() ?: 0f)
        .putFloat(KEY_XMR, prices["XMR"]?.toFloat() ?: 0f)
        .apply()
}

fun loadCryptoPrices(context: Context): Map<String, Double> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return mapOf(
        "BTC" to prefs.getFloat(KEY_BTC, 0f).toDouble(),
        "LTC" to prefs.getFloat(KEY_LTC, 0f).toDouble(),
        "XMR" to prefs.getFloat(KEY_XMR, 0f).toDouble()
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    StaticCryptoScreen()
                }
            }
        }
    }
}


fun saveRates(context: Context, byn: Double, rub: Double, markup: Double) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putFloat(KEY_BYN, byn.toFloat())
        .putFloat(KEY_RUB, rub.toFloat())
        .putFloat(KEY_MARKUP, markup.toFloat())
        .apply()
}

fun loadRates(context: Context): Triple<Double, Double, Double> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val byn = prefs.getFloat(KEY_BYN, 3.01f).toDouble()
    val rub = prefs.getFloat(KEY_RUB, 78f).toDouble()
    val markup = prefs.getFloat(KEY_MARKUP, 1.1f).toDouble()
    return Triple(byn, rub, markup)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaticCryptoScreen() {
    val context = LocalContext.current
    val (defaultByn, defaultRub, defaultMarkup) = loadRates(context)
    var btcInput by remember { mutableStateOf("") }
    var ltcInput by remember { mutableStateOf("") }
    var xmrInput by remember { mutableStateOf("") }
    var lastUpdatedMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var secondsAgo by remember { mutableStateOf(0L) }
    var bynRate by remember { mutableStateOf(defaultByn) }
    var rubRate by remember { mutableStateOf(defaultRub) }
    var markupRate by remember { mutableStateOf(defaultMarkup) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var prices by remember { mutableStateOf<Map<String, Double>?>(null) }

    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val newPrices = fetchCryptoPrices()
                prices = newPrices
                saveCryptoPrices(context, newPrices)
                lastUpdatedMillis = System.currentTimeMillis()
            } catch (e: Exception) {
                prices = loadCryptoPrices(context)
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Не удалось обновить курсы")
                }
            }
            delay(180_000)
        }
    }
    LaunchedEffect(lastUpdatedMillis) {
        while (true) {
            secondsAgo = (System.currentTimeMillis() - lastUpdatedMillis) / 1000
            delay(1_000)
        }
    }

    val (activeCurrency, amount) = when {
        btcInput.isNotEmpty() -> "BTC" to btcInput.toDoubleOrNull()
        ltcInput.isNotEmpty() -> "LTC" to ltcInput.toDoubleOrNull()
        xmrInput.isNotEmpty() -> "XMR" to xmrInput.toDoubleOrNull()
        else -> null to null
    }

    val usdTotal = if (activeCurrency != null && amount != null) {
        (prices?.get(activeCurrency) ?: 0.0) * amount
    } else 0.0

    val bynTotal = usdTotal * bynRate * markupRate
    val rubTotal = usdTotal * rubRate * markupRate

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Created By Vasch") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Введите количество монет:", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(8.dp))

            CryptoInputField("BTC", btcInput, {
                btcInput = it
                ltcInput = ""
                xmrInput = ""
            }, R.drawable.ic_btc, prices?.get("BTC"))

            CryptoInputField("LTC", ltcInput, {
                ltcInput = it
                btcInput = ""
                xmrInput = ""
            }, R.drawable.ic_ltc, prices?.get("LTC"))

            CryptoInputField("XMR", xmrInput, {
                xmrInput = it
                btcInput = ""
                ltcInput = ""
            }, R.drawable.ic_xmr, prices?.get("XMR"))


            Spacer(Modifier.height(24.dp))

            CurrencyCard("BYN   ", "%.2f".format(bynTotal), R.drawable.flag_byn)
            CurrencyCard("RUB   ", "%.2f".format(rubTotal), R.drawable.flag_rub)
            CurrencyCard("USD   ", "%.2f".format(usdTotal), R.drawable.flag_usd)
        }
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "Курсы обновлены ${secondsAgo} сек. назад",
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (showSettings) {
            SettingsDialog(
                bynRate = bynRate,
                rubRate = rubRate,
                markupRate = markupRate,
                onDismiss = { showSettings = false },
                onSave = { newByn, newRub, newMarkup ->
                    saveRates(context, newByn, newRub, newMarkup)
                    bynRate = newByn
                    rubRate = newRub
                    markupRate = newMarkup
                    showSettings = false
                }
            )
        }
    }
}


@Composable
fun CryptoInputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    iconRes: Int,
    price: Double? // 🆕 добавлен параметр цены
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
                        text = "$${"%.2f".format(price)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}


@Composable
fun CurrencyCard(code: String, value: String, iconRes: Int) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable {
                clipboardManager.setText(AnnotatedString(value))
                Toast.makeText(context, "$code скопировано: $value", Toast.LENGTH_SHORT).show()
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(code, style = MaterialTheme.typography.titleMedium)
            }
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}


@Serializable
data class CryptoPrices(
    val bitcoin: Price,
    val litecoin: Price,
    val monero: Price
)

@Serializable
data class Price(val usd: Double)

suspend fun fetchCryptoPrices(): Map<String, Double> {
    val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    try {
        val response: HttpResponse =
            client.get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,litecoin,monero&vs_currencies=usd")
        val prices = response.body<CryptoPrices>()
        return mapOf(
            "BTC" to prices.bitcoin.usd,
            "LTC" to prices.litecoin.usd,
            "XMR" to prices.monero.usd
        )
    } finally {
        client.close()
    }
}


@Composable
fun SettingsDialog(
    bynRate: Double,
    rubRate: Double,
    markupRate: Double,
    onDismiss: () -> Unit,
    onSave: (Double, Double, Double) -> Unit
) {
    var bynText by remember { mutableStateOf(bynRate.toString()) }
    var rubText by remember { mutableStateOf(rubRate.toString()) }
    var markupText by remember { mutableStateOf(markupRate.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val newByn = bynText.toDoubleOrNull() ?: bynRate
                val newRub = rubText.toDoubleOrNull() ?: rubRate
                val newMarkup = markupText.toDoubleOrNull() ?: markupRate
                onSave(newByn, newRub, newMarkup)
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
        title = { Text("Настройки") },
        text = {
            Column {
                OutlinedTextField(
                    value = bynText,
                    onValueChange = { bynText = it },
                    label = { Text("Курс BYN") },
                    // keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = rubText,
                    onValueChange = { rubText = it },
                    label = { Text("Курс RUB") },
                    // keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = markupText,
                    onValueChange = { markupText = it },
                    label = { Text("Надбавка (1.1 = 10%)") },
                    // keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    )
}

