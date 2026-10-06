package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointGate

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    gate: EndpointGate = EndpointGate.Loading,
    onEditEndpoint: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        when (val g = gate) {
            is EndpointGate.Ready -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(g.config.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            g.config.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (g.config.model.isNotBlank()) {
                            Text(
                                "模型：${g.config.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "金鑰：●●●● 已設定",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onEditEndpoint, modifier = Modifier.fillMaxWidth()) {
                    Text("編輯端點")
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (!confirmLogout) {
                    OutlinedButton(
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("登出（清除金鑰）")
                    }
                } else {
                    Text(
                        "確定清除本機金鑰與端點設定？此動作無法復原。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            confirmLogout = false
                            onLogout()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("確定清除")
                    }
                }
            }
            EndpointGate.NoEndpoint -> {
                Text(
                    "尚未設定 API 端點。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onEditEndpoint, modifier = Modifier.fillMaxWidth()) {
                    Text("前往設定")
                }
            }
            EndpointGate.Loading -> {
                Text(
                    "讀取設定中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
