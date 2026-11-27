package com.dafeiyu.overlay.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.data.EnvCard
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoChipButton
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoPrimaryButton
import com.dafeiyu.overlay.ui.components.MonoSectionHeader
import java.util.UUID

private data class QuickPreset(
    val name: String,
    val icon: ImageVector
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataVaultTab(
    config: AppConfig,
    configStore: ConfigStore
) {
    val context = LocalContext.current
    val revealedMap = remember { mutableStateMapOf<String, Boolean>() }

    var showEditor by remember { mutableStateOf(false) }
    var editingCardId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftValue by remember { mutableStateOf("") }

    val quickPresets = remember {
        listOf(
            QuickPreset("電郵", Icons.Rounded.Email),
            QuickPreset("手機", Icons.Rounded.Phone),
            QuickPreset("姓名", Icons.Rounded.Person),
            QuickPreset("地址", Icons.Rounded.LocationOn),
            QuickPreset("學號", Icons.Rounded.Badge)
        )
    }

    fun openNewCard(presetName: String = "") {
        editingCardId = null
        draftName = presetName
        draftValue = ""
        showEditor = true
    }

    fun openEditCard(card: EnvCard) {
        editingCardId = card.id
        draftName = card.name
        draftValue = card.value
        showEditor = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Privacy Architecture Header Card
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Shield,
                title = "儲存資料・本機加密卡片",
                subtitle = "零洩漏替換：實際資料絕不傳送給 DeepSeek"
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "每張卡片以本機 Android Keystore 加密保存。執行「輸入類」流程時，僅將卡片名稱（如 [電郵]）附在提示詞末尾，模型回覆佔位符後由本機自動替換成真實內容並填入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            MonoPrimaryButton(
                text = "新增資料卡片",
                icon = Icons.Rounded.Add,
                onClick = { openNewCard() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "快速建立範本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPresets.forEach { preset ->
                    MonoChipButton(
                        text = preset.name,
                        icon = preset.icon,
                        selected = false,
                        onClick = { openNewCard(preset.name) }
                    )
                }
            }
        }

        // Empty State or Card List
        if (config.envCards.isEmpty()) {
            MonoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "尚未新增任何資料卡片",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "點擊上方「新增資料卡片」或選擇快速範本建立",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            config.envCards.forEach { card ->
                val revealed = revealedMap[card.id] == true
                MonoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Label,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "[${card.name}]",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (revealed) card.value else "••••••••••••",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MonoChipButton(
                            text = if (revealed) "隱藏" else "檢視",
                            icon = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            selected = false,
                            onClick = { revealedMap[card.id] = !revealed }
                        )
                        MonoChipButton(
                            text = "複製",
                            icon = Icons.Rounded.ContentCopy,
                            selected = false,
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText(card.name, card.value))
                                Toast.makeText(context, "已複製 [${card.name}]", Toast.LENGTH_SHORT).show()
                            }
                        )
                        MonoChipButton(
                            text = "編輯",
                            icon = Icons.Rounded.Edit,
                            selected = false,
                            onClick = { openEditCard(card) }
                        )
                        MonoChipButton(
                            text = "刪除",
                            icon = Icons.Rounded.DeleteOutline,
                            selected = false,
                            onClick = {
                                configStore.update { cfg ->
                                    cfg.copy(envCards = cfg.envCards.filterNot { it.id == card.id })
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(86.dp))
    }

    if (showEditor) {
        val cleanName = draftName.trim().removePrefix("[").removeSuffix("]")
        val canSave = cleanName.isNotEmpty() && draftValue.isNotEmpty()

        AlertDialog(
            onDismissRequest = { showEditor = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (editingCardId == null) Icons.Rounded.Add else Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (editingCardId == null) "新增加密資料卡片" else "編輯資料卡片",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("卡片名字 (例如：電郵、手機)") },
                        leadingIcon = { Icon(Icons.Rounded.Label, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draftValue,
                        onValueChange = { draftValue = it },
                        label = { Text("實際儲存內容 (僅留本機)") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        minLines = 1,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (cleanName.isNotEmpty()) {
                                "提示詞佔位符：[$cleanName]（模型回覆此標籤時自動替換）"
                            } else {
                                "輸入卡片名字後，系統會以 [卡片名字] 作為佔位符"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                MonoPrimaryButton(
                    text = "儲存",
                    icon = Icons.Rounded.Check,
                    enabled = canSave,
                    onClick = {
                        configStore.update { cfg ->
                            val existingId = editingCardId
                            val updatedList = if (existingId != null) {
                                cfg.envCards.map { c ->
                                    if (c.id == existingId) c.copy(name = cleanName, value = draftValue)
                                    else c
                                }
                            } else {
                                val sameName = cfg.envCards.firstOrNull { it.name == cleanName }
                                if (sameName != null) {
                                    cfg.envCards.map { c ->
                                        if (c.id == sameName.id) c.copy(value = draftValue) else c
                                    }
                                } else {
                                    cfg.envCards + EnvCard(
                                        id = UUID.randomUUID().toString(),
                                        name = cleanName,
                                        value = draftValue
                                    )
                                }
                            }
                            cfg.copy(envCards = updatedList)
                        }
                        showEditor = false
                    }
                )
            },
            dismissButton = {
                MonoOutlineButton(
                    text = "取消",
                    icon = Icons.Rounded.Close,
                    onClick = { showEditor = false }
                )
            }
        )
    }
}
