package com.focusguard.ui.compose.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.UserProfile
import com.focusguard.data.UserProfilePolicy
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.layout.FocusGuardSectionHeader

@Composable
fun ProfileScreen(
    profile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onBack: () -> Unit
) {
    var displayName by rememberSaveable(profile.displayName) {
        mutableStateOf(profile.displayName)
    }
    var avatarId by rememberSaveable(profile.avatarId) {
        mutableIntStateOf(profile.avatarId)
    }
    val normalizedName = remember(displayName) {
        UserProfilePolicy.normalizeName(displayName)
    }
    val canSave = normalizedName.isNotBlank()

    val saveProfile = {
        if (canSave) {
            onSave(UserProfile(displayName = normalizedName, avatarId = avatarId))
        }
    }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.profile_title),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            Text(
                text = stringResource(R.string.profile_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileAvatar(
                        avatarId = avatarId,
                        modifier = Modifier.size(112.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = normalizedName.ifBlank {
                            stringResource(R.string.profile_name_preview_placeholder)
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            FocusGuardSectionHeader(stringResource(R.string.profile_name_section))
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = UserProfilePolicy.limitNameInput(it.replace('\n', ' '))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_name_label)) },
                placeholder = { Text(stringResource(R.string.profile_name_placeholder)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.profile_name_counter,
                            displayName.codePointCount(0, displayName.length),
                            UserProfilePolicy.MAX_NAME_LENGTH
                        )
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { saveProfile() })
            )
            AnimatedVisibility(visible = !canSave) {
                Text(
                    text = stringResource(R.string.profile_name_required),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(28.dp))
            FocusGuardSectionHeader(stringResource(R.string.profile_avatar_section))
            Text(
                text = stringResource(R.string.profile_avatar_subtitle),
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ProfileAvatarPresets, key = { it.id }) { preset ->
                    val label = stringResource(preset.labelRes)
                    Column(
                        modifier = Modifier
                            .width(76.dp)
                            .selectable(
                                selected = avatarId == preset.id,
                                onClick = { avatarId = preset.id },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ProfileAvatar(
                            avatarId = preset.id,
                            modifier = Modifier.size(68.dp),
                            selected = avatarId == preset.id
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = saveProfile,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = stringResource(R.string.save),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
