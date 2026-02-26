package com.github.damontecres.wholphin.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.github.damontecres.wholphin.services.syncplay.SyncPlayManager
import com.github.damontecres.wholphin.services.syncplay.SyncPlayState
import com.github.damontecres.wholphin.ui.components.TextButton

@Composable
fun SyncPlayDialog(
    syncPlayManager: SyncPlayManager,
    onDismissRequest: () -> Unit
) {
    val state by syncPlayManager.state.collectAsState()
    val availableGroups by syncPlayManager.availableGroups.collectAsState()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(16.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SyncPlay",
                    style = MaterialTheme.typography.headlineMedium
                )

                when (val currentState = state) {
                    is SyncPlayState.None -> {
                        Text(text = "You are not in a SyncPlay group.")

                        TextButton(
                            onClick = {
                                syncPlayManager.createGroup()
                            }
                        ) {
                            Text("Create Group")
                        }

                        // NEW: Loop through all active groups and draw a "Join" button for each!
                        if (availableGroups.isNotEmpty()) {
                            Text(
                                text = "Available Groups:",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                items(availableGroups) { group ->
                                    TextButton(
                                        onClick = {
                                            syncPlayManager.joinGroup(group.groupId.toString())
                                        }
                                    ) {
                                        Text("Join ${group.groupName}")
                                    }
                                }
                            }
                        }
                    }
                    is SyncPlayState.Joining -> {
                        Text(text = "Joining group...")
                    }
                    is SyncPlayState.InGroup -> {
                        Text(text = "Joined group: ${currentState.groupId}")
                        Text(text = "Participants: ${currentState.userCount}")
                        TextButton(
                            onClick = {
                                syncPlayManager.leaveGroup()
                            }
                        ) {
                            Text("Leave Group")
                        }
                    }
                }

                TextButton(onClick = onDismissRequest) {
                    Text("Close Menu")
                }
            }
        }
    }
}