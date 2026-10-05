package com.alaisa.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

val client = OkHttpClient()

fun callClaude(key: String, msgs: List<Pair<String, String>>): String {
    val arr = JSONArray()
    msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
    val sys = "You are Alaisa, a warm, sharp, efficient personal assistant for drafting emails and documents, " +
        "planning, reminders and answering questions. Be concise. Current local time: ${LocalDateTime.now()}. " +
        "To set a reminder, include on its own line exactly: REMIND|yyyy-MM-ddTHH:mm|reminder text"
    val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 1024)
        .put("system", sys).put("messages", arr)
    val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", key).addHeader("anthropic-version", "2023-06-01")
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    client.newCall(req).execute().use { r ->
        val s = r.body?.string() ?: ""
        if (!r.isSuccessful) return "Error ${r.code}: $s"
        return JSONObject(s).getJSONArray("content").getJSONObject(0).getString("text")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AlaisaApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlaisaApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("alaisa", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var keyInput by remember { mutableStateOf("") }
    val chat = remember { mutableStateListOf<Pair<String, String>>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun send(text: String) {
        if (text.isBlank() || busy || key.isBlank()) return
        chat.add("user" to text); input = ""; busy = true
        val history = chat.toList()
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                try { callClaude(key, history) } catch (e: Exception) { "Network error: ${e.message}" }
            }
            val shown = StringBuilder()
            reply.lines().forEach { line ->
                if (line.startsWith("REMIND|")) {
                    val p = line.split("|", limit = 3)
                    try {
                        val at = LocalDateTime.parse(p[1]).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        scheduleReminder(ctx, p[2], at)
                        shown.appendLine("⏰ Reminder set: ${p[2]} (${p[1].replace('T', ' ')})")
                    } catch (e: Exception) { shown.appendLine(line) }
                } else shown.appendLine(line)
            }
            chat.add("assistant" to shown.toString().trim()); busy = false
            listState.animateScrollToItem(chat.size - 1)
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK)
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
        Text("Alaisa", style = MaterialTheme.typography.headlineSmall)
        Text("Your personal assistant", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (key.isBlank()) {
            Text("Paste your Anthropic API key to start:")
            OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API key") })
            Button({ key = keyInput.trim(); prefs.edit().putString("key", key).apply() }) { Text("Save") }
        } else {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat.isEmpty()) item { Text("Hi, I'm Alaisa. Ask me to draft an email, plan your day, or remind you of something.") }
                items(chat) { (role, text) ->
                    val me = role == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(shape = MaterialTheme.shapes.large, tonalElevation = if (me) 6.dp else 1.dp,
                            color = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(text, Modifier.padding(12.dp).widthIn(max = 300.dp))
                        }
                    }
                }
                if (busy) item { Text("Thinking…") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask Alaisa…") }, maxLines = 4)
                TextButton({
                    try { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } catch (e: Exception) {}
                }) { Text("🎤") }
                Button({ send(input) }, enabled = !busy) { Text("Send") }
            }
            TextButton({ key = ""; prefs.edit().remove("key").apply() }) { Text("Change API key") }
        }
    }
}
