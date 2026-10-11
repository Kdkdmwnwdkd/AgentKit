package com.example.agentkit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppNav(vm: ChatViewModel) {
    var route by remember { mutableStateOf("chat") }
    if (route == "chat") ChatScreen(vm) { route = "config" }
    else ModelConfigScreen(vm) { route = "chat" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenConfig: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val running by vm.isRunning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AgentKit") },
                actions = { TextButton(onClick = onOpenConfig) { Text("模型") } }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { vm.input.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.send() }, enabled = !running) {
                    Text(if (running) "…" else "发送")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { MessageRow(it) }
        }
    }
}

@Composable
private fun MessageRow(item: UiItem) {
    when (item) {
        is UiItem.User -> MessageBubble(item.text, MaterialTheme.colorScheme.primaryContainer, Alignment.End)
        is UiItem.Assistant -> MessageBubble(item.text, MaterialTheme.colorScheme.surfaceVariant, Alignment.Start)
        is UiItem.Tool -> ToolCard(item)
    }
}

@Composable
private fun MessageBubble(text: String, color: androidx.compose.ui.graphics.Color, alignment: Alignment.Horizontal) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignment == Alignment.End) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            SelectionContainer { Text(text, Modifier.padding(10.dp)) }
        }
    }
}

/** 工具调用用折叠卡片展示：默认只显示工具名，点「详情」展开入参与结果。 */
@Composable
private fun ToolCard(item: UiItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧 ${item.call.name}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "详情")
                }
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        "入参：${item.call.arguments}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        item.result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isError) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") })
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key") })
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名") })
            Button(onClick = {
                vm.updateConfig(ModelConfig(baseUrl, apiKey, model))
                onBack()
            }) { Text("保存") }
        }
    }
}
