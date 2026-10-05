package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun RomsDialog(path: String?, onChoose: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ROM 文件夹") },
        text = {
            Column {
                Text(
                    "此文件夹在每个会话中显示为 /root/ROMs，内部存储位于 /root/Storage。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    path ?: "尚未选择文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (path != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "下次会话生效，支持 SD 卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onChoose) { Text(if (path == null) "选择文件夹" else "更改文件夹") } },
        dismissButton = {
            if (path != null) TextButton(onClick = onClear) { Text("忘记") }
            else TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
