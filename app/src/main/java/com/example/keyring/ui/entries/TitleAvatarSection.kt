package com.example.keyring.ui.entries

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.keyring.R
import java.io.File
import kotlin.math.abs

private val AvatarBackgroundPalette = listOf(
    Color(0xFF3949AB),
    Color(0xFF00897B),
    Color(0xFF6A1B9A),
    Color(0xFFC62828),
    Color(0xFF2E7D32),
    Color(0xFFAD1457),
    Color(0xFF1565C0),
    Color(0xFFEF6C00)
)

internal fun titleInitialChar(title: String): String {
    val t = title.trim()
    if (t.isEmpty()) return "?"
    return t.first().uppercaseChar().toString()
}

internal fun backgroundColorForTitle(title: String): Color {
    if (title.isBlank()) return Color(0xFF78909C)
    val idx = abs(title.hashCode()) % AvatarBackgroundPalette.size
    return AvatarBackgroundPalette[idx]
}

@Composable
fun TitleAvatarSection(
    title: String,
    avatarImagePath: String?,
    onEditAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    showEditBadge: Boolean = true
) {
    val bg = backgroundColorForTitle(title)
    val circleBg =
        if (avatarImagePath != null) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            bg
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(88.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(circleBg)
                    .then(
                        if (avatarImagePath != null) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (avatarImagePath != null) {
                    AsyncImage(
                        model = File(avatarImagePath),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = titleInitialChar(title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            if (showEditBadge) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onEditAvatarClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.avatar_edit_desc),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun PasswordListRowAvatar(
    title: String,
    avatarImagePath: String?,
    modifier: Modifier = Modifier.size(48.dp)
) {
    val bg = backgroundColorForTitle(title)
    val circleBg =
        if (avatarImagePath != null) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            bg
        }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(circleBg)
            .then(
                if (avatarImagePath != null) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (avatarImagePath != null) {
            AsyncImage(
                model = File(avatarImagePath),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = titleInitialChar(title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}
