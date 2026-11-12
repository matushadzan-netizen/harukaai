package com.localai.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(val role: String, val text: String, val imagePath: String? = null)

data class RoleplayCharacter(
    val id: String,
    val name: String,
    val gender: String,
    val description: String,
    val appearance: String,
    val personality: String,
    val backstory: String,
    val relationship: String,
    val speech: String,
    val habits: String,
    val tags: String,
    val greeting: String,
    val scene: String,
    val portraitPath: String = ""
) {
    fun systemPrompt(): String = """
        Ты играешь роль персонажа "$name". Не выходи из роли без прямой просьбы пользователя.
        Пол: $gender. Описание: $description. Внешность: $appearance.
        Характер: $personality. Предыстория: $backstory. Отношение к собеседнику: $relationship.
        Манера речи: $speech. Привычки: $habits. Жанровые метки: $tags.
        Начальная сцена: $scene.
        Отвечай естественно, последовательно соблюдай характер и не описывай действия или мысли пользователя за него.
    """.trimIndent()

    fun toJson(): JSONObject = JSONObject().apply {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("name", name)
        put("id", id)
        put("gender", gender)
        put("description", description)
        put("appearance", appearance)
        put("personality", personality)
        put("backstory", backstory)
        put("relationship", relationship)
        put("speech", speech)
        put("habits", habits)
        put("tags", tags)
        put("first_mes", greeting)
        put("scenario", scene)
        put("portrait", portraitPath)
    }

    companion object {
        fun fromJson(o: JSONObject) = RoleplayCharacter(
            id = o.optString("id", System.currentTimeMillis().toString()),
            name = o.optString("name", "Без имени"),
            gender = o.optString("gender", "Не указан"),
            description = o.optString("description"),
            appearance = o.optString("appearance"),
            personality = o.optString("personality"),
            backstory = o.optString("backstory"),
            relationship = o.optString("relationship"),
            speech = o.optString("speech"),
            habits = o.optString("habits"),
            tags = o.optString("tags"),
            greeting = o.optString("first_mes", o.optString("greeting")),
            scene = o.optString("scenario", o.optString("scene")),
            portraitPath = o.optString("portrait")
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HarukaApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarukaApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("harukaai", 0) }

    var contextHandle by remember { mutableStateOf(0L) }
    var modelPath by remember { mutableStateOf<String?>(null) }
    var mmprojPath by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Модель не загружена") }
    var isLoading by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var visionReady by remember { mutableStateOf(false) }
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    fun loadSavedCharacters(): List<RoleplayCharacter> = try {
        val arr = JSONArray(prefs.getString("characters", "[]"))
        (0 until arr.length()).map { RoleplayCharacter.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
    val characters = remember { mutableStateListOf<RoleplayCharacter>().apply { addAll(loadSavedCharacters()) } }
    var page by remember { mutableStateOf("chat") } // chat, roleplay, editor
    var editing by remember { mutableStateOf<RoleplayCharacter?>(null) }
    var activeCharacter by remember { mutableStateOf<RoleplayCharacter?>(null) }
    var editorName by remember { mutableStateOf("") }
    var editorGender by remember { mutableStateOf("Не указан") }
    var editorDescription by remember { mutableStateOf("") }
    var editorAppearance by remember { mutableStateOf("") }
    var editorPersonality by remember { mutableStateOf("") }
    var editorBackstory by remember { mutableStateOf("") }
    var editorRelationship by remember { mutableStateOf("") }
    var editorSpeech by remember { mutableStateOf("") }
    var editorHabits by remember { mutableStateOf("") }
    var editorTags by remember { mutableStateOf("") }
    var editorGreeting by remember { mutableStateOf("") }
    var editorScene by remember { mutableStateOf("") }
    var editorPortrait by remember { mutableStateOf("") }
    var editorStatus by remember { mutableStateOf("") }

    fun persistCharacters() {
        val arr = JSONArray()
        characters.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("characters", arr.toString()).apply()
    }
    fun openEditor(character: RoleplayCharacter? = null) {
        editing = character
        editorName = character?.name ?: ""
        editorGender = character?.gender ?: "Не указан"
        editorDescription = character?.description ?: ""
        editorAppearance = character?.appearance ?: ""
        editorPersonality = character?.personality ?: ""
        editorBackstory = character?.backstory ?: ""
        editorRelationship = character?.relationship ?: ""
        editorSpeech = character?.speech ?: ""
        editorHabits = character?.habits ?: ""
        editorTags = character?.tags ?: ""
        editorGreeting = character?.greeting ?: ""
        editorScene = character?.scene ?: ""
        editorPortrait = character?.portraitPath ?: ""
        editorStatus = ""
        page = "editor"
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { copyUriToCache(context, it, "model.gguf") { path -> modelPath = path } }
    }
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { copyUriToCache(context, it, "mmproj.gguf") { path -> mmprojPath = path } }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { copyUriToCache(context, it, "attach_${System.currentTimeMillis()}.jpg") { path -> pendingImagePath = path } }
    }
    val portraitPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { copyUriToCache(context, it, "portrait_${System.currentTimeMillis()}.jpg") { path -> editorPortrait = path } }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val raw = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
                val root = JSONObject(raw)
                val data = root.optJSONObject("data") ?: root
                val imported = RoleplayCharacter.fromJson(data)
                characters.add(imported.copy(id = System.currentTimeMillis().toString()))
                persistCharacters()
                editorStatus = "Персонаж импортирован"
            } catch (e: Exception) { editorStatus = "Не удалось импортировать JSON: ${e.message}" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("HarukaAI" + (activeCharacter?.let { " · ${it.name}" } ?: "")) },
            actions = {
                TextButton(onClick = { page = if (page == "roleplay" || page == "editor") "chat" else "roleplay" }) {
                    Text(if (page == "roleplay" || page == "editor") "Чат" else "Ролка")
                }
            }
        )
    }) { padding ->
        when (page) {
            "roleplay" -> {
                Column(Modifier.padding(padding).fillMaxSize().padding(14.dp)) {
                    Text("Ролевая", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Создавай персонажей и начинай отдельные сюжетные диалоги.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openEditor() }) { Text("＋ Создать персонажа") }
                        OutlinedButton(onClick = { importPicker.launch("application/json") }) { Text("Импорт JSON") }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (characters.isEmpty()) Text("Пока нет персонажей. Нажми «Создать персонажа».")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(characters, key = { it.id }) { c ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(c.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                    if (c.description.isNotBlank()) Text(c.description, maxLines = 3)
                                    if (c.tags.isNotBlank()) Text("# ${c.tags}", style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            activeCharacter = c
                                            messages.clear()
                                            if (c.greeting.isNotBlank()) messages.add(ChatMessage("assistant", c.greeting))
                                            page = "chat"
                                        }) { Text("Начать ролку") }
                                        OutlinedButton(onClick = { openEditor(c) }) { Text("Изменить") }
                                        TextButton(onClick = { characters.remove(c); persistCharacters() }) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                    }
                    if (editorStatus.isNotBlank()) Text(editorStatus, color = MaterialTheme.colorScheme.primary)
                }
            }
            "editor" -> {
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                    Text(if (editing == null) "Создание персонажа" else "Редактирование персонажа", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    EditorField("Имя персонажа *", editorName) { editorName = it }
                    EditorField("Пол", editorGender) { editorGender = it }
                    EditorField("Краткое описание", editorDescription, 2) { editorDescription = it }
                    Text("Внешность и образ", fontWeight = FontWeight.Bold)
                    EditorField("Опиши внешность, одежду, особенности", editorAppearance, 3) { editorAppearance = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { portraitPicker.launch("image/*") }) { Text("Выбрать портрет") }
                        if (editorPortrait.isNotBlank()) Text("Портрет добавлен", modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Характер и предыстория", fontWeight = FontWeight.Bold)
                    EditorField("Черты характера", editorPersonality, 3) { editorPersonality = it }
                    EditorField("Предыстория", editorBackstory, 3) { editorBackstory = it }
                    EditorField("Отношение к пользователю", editorRelationship, 2) { editorRelationship = it }
                    EditorField("Манера речи", editorSpeech, 2) { editorSpeech = it }
                    EditorField("Привычки", editorHabits, 2) { editorHabits = it }
                    EditorField("Метки / теги (романтика, фэнтези, цундере…)", editorTags, 2) { editorTags = it }
                    Text("Начало истории", fontWeight = FontWeight.Bold)
                    EditorField("Первая реплика персонажа", editorGreeting, 3) { editorGreeting = it }
                    EditorField("Вводная сцена / контекст", editorScene, 3) { editorScene = it }
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = editorName.isNotBlank(), onClick = {
                        val c = RoleplayCharacter(
                            id = editing?.id ?: System.currentTimeMillis().toString(), name = editorName.trim(), gender = editorGender,
                            description = editorDescription, appearance = editorAppearance, personality = editorPersonality,
                            backstory = editorBackstory, relationship = editorRelationship, speech = editorSpeech,
                            habits = editorHabits, tags = editorTags, greeting = editorGreeting, scene = editorScene,
                            portraitPath = editorPortrait
                        )
                        val old = characters.indexOfFirst { it.id == c.id }
                        if (old >= 0) characters[old] = c else characters.add(c)
                        persistCharacters()
                        page = "roleplay"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить персонажа") }
                    OutlinedButton(onClick = { page = "roleplay" }, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
                    Text("Карточки хранятся локально на устройстве. Импорт поддерживает JSON-карточки с полями name, description, personality, first_mes и scenario.", style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> {
                Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (activeCharacter != null) "Ролевая сессия" else "Локальная модель", fontWeight = FontWeight.Bold)
                            if (activeCharacter != null) {
                                Text("Персонаж: ${activeCharacter!!.name}")
                                TextButton(onClick = { activeCharacter = null; messages.clear() }) { Text("Завершить ролку") }
                            }
                            Text(statusText, style = MaterialTheme.typography.bodySmall)
                            Text("Huihui AI / Dolphin 3: выбери совместимую GGUF-сборку через «Выбрать GGUF». Команда ollama run предназначена для Ollama на компьютере и напрямую в Android-приложении не запускается.", style = MaterialTheme.typography.bodySmall)
                            Text(if (visionReady) "📷 Анализ изображений готов" else "📷 Для анализа фото нужна vision-модель и совместимый mmproj", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { modelPicker.launch("*/*") }) { Text("Выбрать GGUF") }
                                OutlinedButton(onClick = { mmprojPicker.launch("*/*") }) { Text("Выбрать mmproj") }
                            }
                            Spacer(Modifier.height(6.dp))
                            Button(enabled = modelPath != null && !isLoading, onClick = {
                                isLoading = true
                                scope.launch {
                                    val handle = withContext(Dispatchers.Default) { LlamaBridge.loadModel(modelPath!!, 4, 2048) }
                                    val projectorLoaded = if (handle != 0L && mmprojPath != null) withContext(Dispatchers.Default) { LlamaBridge.loadMultimodalProjector(handle, mmprojPath!!) } else false
                                    contextHandle = handle
                                    visionReady = projectorLoaded
                                    statusText = when { handle == 0L -> "Не удалось загрузить модель"; projectorLoaded -> "Модель и vision готовы (офлайн)"; else -> "Текстовая модель загружена; для фото выбери парный mmproj" }
                                    isLoading = false
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (isLoading) "Загрузка…" else "Загрузить модель") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(messages) { msg ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(if (msg.role == "user") "Вы" else (activeCharacter?.name ?: "HarukaAI"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                if (msg.imagePath != null) Text("[изображение: ${File(msg.imagePath).name}]", style = MaterialTheme.typography.labelSmall)
                                Text(msg.text)
                            }
                            Divider()
                        }
                    }
                    if (pendingImagePath != null) Text("Фото прикреплено: ${File(pendingImagePath!!).name}", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), placeholder = { Text("Напиши сообщение…") })
                        Spacer(Modifier.width(5.dp))
                        OutlinedButton(onClick = { imagePicker.launch("image/*") }) { Text("📷") }
                        Spacer(Modifier.width(5.dp))
                        Button(enabled = contextHandle != 0L && inputText.isNotBlank() && !isGenerating && (pendingImagePath == null || visionReady), onClick = {
                            val userText = inputText
                            val img = pendingImagePath
                            inputText = ""
                            pendingImagePath = null
                            messages.add(ChatMessage("user", userText, img))
                            val aiIndex = messages.size
                            messages.add(ChatMessage("assistant", ""))
                            isGenerating = true
                            val character = activeCharacter
                            val prompt = if (character != null) "${character.systemPrompt()}\n\nИстория диалога:\n${messages.dropLast(2).takeLast(12).joinToString("\n") { (if (it.role == "user") "Пользователь" else character.name) + ": " + it.text }}\nПользователь: $userText\n${character.name}:" else userText
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    LlamaBridge.generate(contextHandle, prompt, img, 512) { token ->
                                        scope.launch(Dispatchers.Main) { messages[aiIndex] = messages[aiIndex].copy(text = messages[aiIndex].text + token) }
                                    }
                                }
                                isGenerating = false
                            }
                        }) { Text("Отправить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorField(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), minLines = minLines)
}

/** Copies a selected local document or image into app cache for native file access. */
fun copyUriToCache(context: android.content.Context, uri: Uri, fileName: String, onDone: (String) -> Unit) {
    val outFile = File(context.cacheDir, fileName)
    context.contentResolver.openInputStream(uri)?.use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
    onDone(outFile.absolutePath)
}
