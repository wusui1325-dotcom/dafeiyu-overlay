package com.dafeiyu.overlay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ShortText
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.data.InputInjectionMethod
import com.dafeiyu.overlay.data.ReasoningEffort
import com.dafeiyu.overlay.network.ApiResult
import com.dafeiyu.overlay.network.DeepSeekClient
import com.dafeiyu.overlay.ui.components.MonoCard
import com.dafeiyu.overlay.ui.components.MonoChipButton
import com.dafeiyu.overlay.ui.components.MonoOutlineButton
import com.dafeiyu.overlay.ui.components.MonoSectionHeader
import com.dafeiyu.overlay.ui.components.MonoSliderRow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSettingsTab(
    config: AppConfig,
    configStore: ConfigStore
) {
    val scope = rememberCoroutineScope()
    var showApiKey by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // BYOK API Credentials
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Key,
                title = "DeepSeek BYOK 憑證",
                subtitle = "完全無後端，金鑰僅儲存在本機加密空間"
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = config.apiKey,
                onValueChange = { v -> configStore.update { it.copy(apiKey = v.trim()) } },
                label = { Text("API Key (sk-...)") },
                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null
                        )
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { v -> configStore.update { it.copy(baseUrl = v) } },
                label = { Text("Base URL") },
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            MonoOutlineButton(
                text = if (fetchingModels) "拉取模型列表中…" else "驗證連線並拉取最新模型 (/models)",
                icon = Icons.Rounded.Refresh,
                enabled = !fetchingModels && config.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    fetchingModels = true
                    fetchMessage = null
                    scope.launch {
                        when (val res = DeepSeekClient.fetchModels(config.apiKey, config.baseUrl)) {
                            is ApiResult.Success -> {
                                configStore.update { it.copy(cachedModels = res.data) }
                                fetchMessage = "已成功載入 ${res.data.size} 個模型"
                            }
                            is ApiResult.Error -> {
                                fetchMessage = res.message
                            }
                        }
                        fetchingModels = false
                    }
                }
            )
            if (fetchMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = fetchMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Model Selector & Parameters
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.AutoAwesome,
                title = "模型型號選單與參數",
                subtitle = "支援視覺截圖建議選用 deepseek-flash"
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.cachedModels.forEach { model ->
                    MonoChipButton(
                        text = model,
                        icon = Icons.Rounded.AutoAwesome,
                        selected = config.modelId == model,
                        onClick = { configStore.update { it.copy(modelId = model) } }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = config.modelId,
                onValueChange = { v -> configStore.update { it.copy(modelId = v.trim()) } },
                label = { Text("自訂模型 ID") },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            MonoSliderRow(
                icon = Icons.Rounded.Thermostat,
                title = "Temperature (溫度)",
                valueLabel = String.format(Locale.US, "%.2f", config.temperature),
                value = config.temperature,
                valueRange = 0f..2f,
                steps = 39,
                onValueChange = { v -> configStore.update { it.copy(temperature = v) } }
            )
            MonoSliderRow(
                icon = Icons.Rounded.ShortText,
                title = "Max Tokens 上限",
                valueLabel = "${config.maxTokens}",
                value = config.maxTokens.toFloat(),
                valueRange = 128f..4096f,
                onValueChange = { v -> configStore.update { it.copy(maxTokens = v.roundToInt()) } }
            )
            Spacer(modifier = Modifier.height(6.dp))
            MonoSectionHeader(
                icon = Icons.Rounded.Tune,
                title = "思考強度 (Reasoning Effort)",
                subtitle = "所有檔位皆強制關閉深度思考 (thinking: disabled)"
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReasoningEffort.entries.forEach { effort ->
                    MonoChipButton(
                        text = effort.label,
                        icon = Icons.Rounded.Tune,
                        selected = config.reasoningEffort == effort,
                        onClick = { configStore.update { it.copy(reasoningEffort = effort) } }
                    )
                }
            }
        }

        // Input Injection Strategy
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.Keyboard,
                title = "文字輸入方式",
                subtitle = "選擇自動填入輸入框的底層策略"
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InputInjectionMethod.entries.forEach { method ->
                    MonoChipButton(
                        text = method.label,
                        icon = Icons.Rounded.Keyboard,
                        selected = config.inputMethod == method,
                        onClick = { configStore.update { it.copy(inputMethod = method) } }
                    )
                }
            }
        }

        // Custom Prompts
        MonoCard {
            MonoSectionHeader(
                icon = Icons.Rounded.EditNote,
                title = "進階系統提示詞 (System Prompt)",
                subtitle = "選擇類提示詞可用 {COUNT} 代入選項總數"
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = config.inputSystemPrompt,
                onValueChange = { v -> configStore.update { it.copy(inputSystemPrompt = v) } },
                label = { Text("輸入類 System Prompt") },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = config.choiceSystemPrompt,
                onValueChange = { v -> configStore.update { it.copy(choiceSystemPrompt = v) } },
                label = { Text("選擇類 System Prompt") },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            MonoOutlineButton(
                text = "還原預設提示詞",
                icon = Icons.Rounded.RestartAlt,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    configStore.update {
                        it.copy(
                            inputSystemPrompt = AppConfig.DEFAULT_INPUT_SYSTEM_PROMPT,
                            choiceSystemPrompt = AppConfig.DEFAULT_CHOICE_SYSTEM_PROMPT
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
