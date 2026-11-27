package com.dafeiyu.overlay.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.overlay.DaFeiYuApp
import com.dafeiyu.overlay.network.ApiResult
import com.dafeiyu.overlay.network.DeepSeekClient
import com.dafeiyu.overlay.network.WebPageFetcher
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoChipButton
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoPrimaryButton
import com.dafeiyu.overlay.ui.theme.DaFeiYuTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class BilingualParagraph(
    val original: String,
    val translated: String
)

class WebTranslateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialUrl = extractInitialUrl(intent)
        val fallbackParagraphs = intent.getStringArrayListExtra(EXTRA_FALLBACK_PARAGRAPHS)?.toList().orEmpty()
        val app = application as DaFeiYuApp

        setContent {
            val config by app.configStore.config.collectAsState()
            DaFeiYuTheme(darkTheme = config.appDarkTheme) {
                WebTranslateScreen(
                    initialUrl = initialUrl,
                    fallbackParagraphs = fallbackParagraphs,
                    onBack = { finish() }
                )
            }
        }
    }

    private fun extractInitialUrl(intent: Intent?): String {
        if (intent == null) return ""
        val extra = intent.getStringExtra(EXTRA_URL)
        if (!extra.isNullOrBlank()) return extra
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            return WebPageFetcher.normalizeUrl(shared) ?: shared.trim()
        }
        return ""
    }

    companion object {
        const val EXTRA_URL = "extra_web_url"
        const val EXTRA_FALLBACK_PARAGRAPHS = "extra_fallback_paragraphs"

        fun launch(context: Context, url: String = "", fallbackParagraphs: List<String> = emptyList()) {
            val intent = Intent(context, WebTranslateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url.isNotBlank()) putExtra(EXTRA_URL, url)
                if (fallbackParagraphs.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_FALLBACK_PARAGRAPHS, ArrayList(fallbackParagraphs))
                }
            }
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebTranslateScreen(
    initialUrl: String,
    fallbackParagraphs: List<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as DaFeiYuApp
    val config by app.configStore.config.collectAsState()
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("自然流暢") }
    var bilingualMode by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val results = remember { mutableStateListOf<BilingualParagraph>() }
    val styles = remember { listOf("自然流暢", "專業術語", "口語白話") }

    fun startTranslation(useFallbackDirect: Boolean = false) {
        if (config.apiKey.isBlank()) {
            statusText = "請先在「模型」分頁設定 DeepSeek API Key"
            return
        }
        activeJob?.cancel()
        results.clear()
        isRunning = true
        statusText = if (useFallbackDirect) "準備翻譯畫面擷取正文…" else "正在讀取網頁正文…"

        activeJob = scope.launch {
            val t0 = System.currentTimeMillis()
            val paragraphsToTranslate: List<String>
            val title: String

            if (useFallbackDirect && fallbackParagraphs.isNotEmpty()) {
                title = "當前頁面正文精譯"
                paragraphsToTranslate = fallbackParagraphs
            } else {
                when (val fetched = WebPageFetcher.fetchReadablePage(urlText)) {
                    is ApiResult.Success -> {
                        title = fetched.data.title
                        paragraphsToTranslate = fetched.data.paragraphs
                    }
                    is ApiResult.Error -> {
                        if (fallbackParagraphs.isNotEmpty()) {
                            title = "當前頁面正文精譯"
                            paragraphsToTranslate = fallbackParagraphs
                        } else {
                            statusText = fetched.message
                            isRunning = false
                            return@launch
                        }
                    }
                }
            }

            pageTitle = title
            val chunks = WebPageFetcher.chunkParagraphs(paragraphsToTranslate)
            var translatedCount = 0

            for ((idx, chunk) in chunks.withIndex()) {
                statusText = "DeepSeek 精譯中（第 ${idx + 1} / ${chunks.size} 批・$selectedStyle）…"
                when (
                    val tr = DeepSeekClient.translateWebParagraphs(
                        config = config,
                        paragraphs = chunk,
                        styleLabel = selectedStyle,
                        pageTitle = title
                    )
                ) {
                    is ApiResult.Success -> {
                        val out = tr.data
                        chunk.forEachIndexed { pIdx, orig ->
                            val trans = out.getOrNull(pIdx).orEmpty().ifBlank { orig }
                            results.add(BilingualParagraph(original = orig, translated = trans))
                        }
                        translatedCount += chunk.size
                    }
                    is ApiResult.Error -> {
                        statusText = "翻譯中斷：${tr.message}"
                        isRunning = false
                        return@launch
                    }
                }
            }

            val totalMs = System.currentTimeMillis() - t0
            app.historyStore.record("網頁精譯", config.modelId, totalMs, "$title（共 $translatedCount 段）", true)
            statusText = "精譯完成 · 共 $translatedCount 段 (${totalMs / 1000.0}s)"
            isRunning = false
        }
    }

    LaunchedEffect(initialUrl, fallbackParagraphs) {
        if (initialUrl.isNotBlank()) {
            startTranslation(useFallbackDirect = false)
        } else if (fallbackParagraphs.isNotEmpty()) {
            startTranslation(useFallbackDirect = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Monochrome Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DeepSeek 網頁精譯",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "拒絕生硬機翻・支援瀏覽器分享與懸浮一鍵擷取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (results.isNotEmpty()) {
                MonoChipButton(
                    text = "複製全文",
                    icon = Icons.Rounded.ContentCopy,
                    selected = false,
                    onClick = {
                        val fullText = results.joinToString("\n\n") { it.translated }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText(pageTitle.ifBlank { "網頁譯文" }, fullText))
                        Toast.makeText(context, "已複製全部譯文至剪貼簿", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // URL Input + Controls Card
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MonoCard {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("貼上網頁網址 (或由瀏覽器分享)") },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styles.forEach { st ->
                        MonoChipButton(
                            text = st,
                            icon = Icons.Rounded.AutoAwesome,
                            selected = selectedStyle == st,
                            onClick = { selectedStyle = st }
                        )
                    }
                    MonoChipButton(
                        text = if (bilingualMode) "雙語對照" else "純繁中",
                        icon = Icons.Rounded.ViewAgenda,
                        selected = bilingualMode,
                        onClick = { bilingualMode = !bilingualMode }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isRunning) {
                        MonoOutlineButton(
                            text = "停止翻譯",
                            icon = Icons.Rounded.Stop,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                activeJob?.cancel()
                                isRunning = false
                                statusText = "已停止翻譯"
                            }
                        )
                    } else {
                        MonoPrimaryButton(
                            text = if (results.isEmpty()) "開始 DeepSeek 精譯" else "重新精譯",
                            icon = Icons.Rounded.Translate,
                            enabled = urlText.isNotBlank() || fallbackParagraphs.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { startTranslation(useFallbackDirect = urlText.isBlank()) }
                        )
                    }
                }
                if (statusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Translated Article Reader
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (pageTitle.isNotBlank()) {
                item {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            itemsIndexed(results) { _, item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("譯文段落", item.translated))
                            Toast.makeText(context, "已複製此段譯文", Toast.LENGTH_SHORT).show()
                        }
                        .padding(14.dp)
                ) {
                    if (bilingualMode) {
                        Text(
                            text = item.original,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = item.translated,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 23.sp
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
