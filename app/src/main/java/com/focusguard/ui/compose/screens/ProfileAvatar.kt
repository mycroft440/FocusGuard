package com.focusguard.ui.compose.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.focusguard.R
import com.focusguard.data.UserProfilePolicy

internal data class ProfileAvatarPreset(
    val id: Int,
    @StringRes val labelRes: Int,
    @DrawableRes val imageRes: Int,
    val colors: List<Color>
)

internal val ProfileAvatarPresets = listOf(
    ProfileAvatarPreset(
        id = 0,
        labelRes = R.string.profile_avatar_ocean,
        imageRes = R.drawable.avatar_focus_guardian,
        colors = listOf(Color(0xFF00BCD4), Color(0xFF1565C0))
    ),
    ProfileAvatarPreset(
        id = 1,
        labelRes = R.string.profile_avatar_sunset,
        imageRes = R.drawable.avatar_solar_inventor,
        colors = listOf(Color(0xFFFFB300), Color(0xFFE91E63))
    ),
    ProfileAvatarPreset(
        id = 2,
        labelRes = R.string.profile_avatar_forest,
        imageRes = R.drawable.avatar_forest_explorer,
        colors = listOf(Color(0xFF66BB6A), Color(0xFF00897B))
    ),
    ProfileAvatarPreset(
        id = 3,
        labelRes = R.string.profile_avatar_galaxy,
        imageRes = R.drawable.avatar_galactic_traveler,
        colors = listOf(Color(0xFFAB47BC), Color(0xFF3949AB))
    ),
    ProfileAvatarPreset(
        id = 4,
        labelRes = R.string.profile_avatar_energy,
        imageRes = R.drawable.avatar_energy_runner,
        colors = listOf(Color(0xFFFF7043), Color(0xFFD32F2F))
    )
)

internal fun profileAvatarPreset(avatarId: Int): ProfileAvatarPreset =
    ProfileAvatarPresets[UserProfilePolicy.normalizeAvatarId(avatarId)]

@Composable
internal fun ProfileAvatar(
    avatarId: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val preset = profileAvatarPreset(avatarId)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .padding(if (selected) 5.dp else 0.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(preset.colors)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(preset.imageRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
            )
        }

        if (selected) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}
