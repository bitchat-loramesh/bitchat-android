package com.bitchat.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.meshtastic.MeshtasticDevice

@Composable
fun MeshtasticManagementScreen(
    devices: List<MeshtasticDevice>,
    onDismiss: () -> Unit,
    onDeviceClick: (MeshtasticDevice) -> Unit,
    onSendHello: (() -> Unit)? = null
) {
    // MARK: - Computed Properties
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val textColor = if (isDark) Color.Green else Color(0f, 0.5f, 0f)
    val secondaryTextColor = textColor.copy(alpha = 0.7f)

    val isAnyConnected = devices.any { it.isConnected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Spacer(modifier = Modifier.height(46.dp))
        // Header
        HeaderView(textColor = textColor, onDismiss = onDismiss)
        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Send HELLO button (visible only when a radio is connected)
            if (isAnyConnected && onSendHello != null) {
                Button(
                    onClick = onSendHello,
                    colors = ButtonDefaults.buttonColors(containerColor = textColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Announce on LoRa (HELLO)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = backgroundColor
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Discovered devices section
            Text(
                text = "Discovered Devices",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = textColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Text(
                    text = "No Meshtastic devices found",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = secondaryTextColor,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devices, key = { it.identifier }) { device ->
                        DeviceRow(
                            device = device,
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor,
                            onClick = { onDeviceClick(device) }
                        )
                    }
                }
            }
        }
    }
}

// MARK: - View Components

@Composable
private fun HeaderView(textColor: Color, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Meshtastic Radios",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            color = textColor
        )

        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
@Composable
private fun DeviceRow(
    device: MeshtasticDevice,
    textColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = if (device.isConnected) Color.Green else Color.Red)
                }

                Text(
                    text = device.name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = textColor
                )
            }

            Text(
                text = device.identifier, // MAC Address on Android
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = secondaryTextColor,
                modifier = Modifier.padding(start = 14.dp) // Align under the text, not the dot
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = secondaryTextColor,
            modifier = Modifier.size(20.dp)
        )
    }
}