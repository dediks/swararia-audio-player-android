package com.swararia.audiolab.brandskin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.swararia.audiolab.CircularProgressIndicatorCompact
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import com.swararia.audiolab.foh.rememberLocalAudioSignal
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BrandSkinStudioScreen(
    onBack: () -> Unit,
    onOpenStageRta: () -> Unit,
    modifier: Modifier = Modifier,
    studioState: BrandSkinStudioUiState = BrandSkinStudioUiState(),
    brandSkinActions: BrandSkinUiActions = BrandSkinUiActions(),
) {
    val foh = FohTheme.colors
    val brand = FohTheme.brand
    val signal by rememberLocalAudioSignal()
    val importingLogo = studioState.importingLogo
    val importError = studioState.importError

    val logoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        brandSkinActions.onImportLogoUri(uri.toString())
    }

    val liveBass = if (!signal.muted && signal.active) {
        ((signal.bands.getOrElse(0) { 0f } + signal.bands.getOrElse(1) { 0f }) / 2f).coerceIn(0f, 1f)
    } else {
        0f
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "studio-topbar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactCircleButton(
                    icon = UiIcons.Back,
                    description = "Kembali",
                    onClick = onBack,
                    size = 36.dp,
                    iconSize = 20.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Text(
                    text = "Brand & Skin",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StageRtaEntryPill(onClick = onOpenStageRta)
            }
        }

        item(key = "studio-preview") {
            val previewBrush = Brush.verticalGradient(
                listOf(
                    foh.safeAccent.copy(alpha = 0.14f),
                    if (brand.oledTrueBlack) Color.Black else foh.surfaceCard,
                ),
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (brand.oledTrueBlack) Color.Black else foh.surfaceCard,
                border = BorderStroke(1.dp, foh.safeBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(previewBrush)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PrestigeBrandEmblem(
                        logoKey = brand.customLogoKey,
                        size = 64.dp,
                        shimmerEnabled = brand.prestigeAnimations,
                        bassEnergy = liveBass,
                        playing = signal.active && !signal.muted,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = BrandSkinRules.effectiveRentalTitle(brand).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.8.sp),
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = BrandSkinRules.effectiveEventSubtitle(brand).uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = foh.safeAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${BrandSkinRules.resolvePreset(brand.presetId).label}${if (brand.oledTrueBlack) " · OLED" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item(key = "studio-logo-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { logoPicker.launch(arrayOf("image/*")) },
                        enabled = !importingLogo,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = foh.safeAccent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (importingLogo) {
                            CircularProgressIndicatorCompact()
                        } else {
                            Icon(UiIcons.Import, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = if (importingLogo) "Memproses…" else "Upload Logo",
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    OutlinedButton(
                        onClick = brandSkinActions.onResetAllBranding,
                        enabled = BrandSkinRules.hasCustomBrand(brand),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, foh.borderSubtle),
                    ) {
                        Text("Reset", fontWeight = FontWeight.SemiBold)
                    }
                }

                importError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "studio-identity-fields") {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = foh.safeAccent,
                unfocusedBorderColor = foh.borderSubtle,
                focusedLabelColor = foh.safeAccent,
                unfocusedContainerColor = foh.surfaceCard,
                focusedContainerColor = foh.surfaceCard,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = brand.rentalName,
                    onValueChange = brandSkinActions.onSetRentalName,
                    label = { Text("Nama Rental") },
                    placeholder = { Text("RAJA SOUND PRO") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brand.eventTag,
                    onValueChange = brandSkinActions.onSetEventTag,
                    label = { Text("Tag Event / Panggung") },
                    placeholder = { Text("MAIN STAGE · FOH") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "studio-skin-swatches") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tema Skin",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FohSkinPreset.entries.forEach { preset ->
                        val active = !brand.useCustomLogoAccent && brand.presetId == preset.id
                        Surface(
                            onClick = { brandSkinActions.onSelectPreset(preset.id) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) foh.safeBg else foh.surfaceCard,
                            border = BorderStroke(
                                width = if (active) 1.6.dp else 1.dp,
                                color = if (active) Color(preset.accentArgb) else foh.borderSubtle,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color(preset.accentArgb)),
                                )
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }

                if (brand.customAccentArgb != null) {
                    Surface(
                        onClick = brandSkinActions.onSelectLogoAccent,
                        shape = RoundedCornerShape(14.dp),
                        color = if (brand.useCustomLogoAccent) foh.safeBg else foh.surfaceCard,
                        border = BorderStroke(
                            1.dp,
                            if (brand.useCustomLogoAccent) Color(brand.customAccentArgb) else foh.borderSubtle,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(brand.customAccentArgb)),
                            )
                            Text(
                                text = "Ambil Warna Dominan Logo",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (brand.useCustomLogoAccent) {
                                Icon(UiIcons.Check, null, tint = Color(brand.customAccentArgb), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        item(key = "studio-toggles") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.borderSubtle),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    StudioSwitchRow(
                        label = "True Black OLED",
                        checked = brand.oledTrueBlack,
                        onCheckedChange = brandSkinActions.onSetOledTrueBlack,
                    )
                    StudioSwitchRow(
                        label = "Kilau Metallic & Aura Bass",
                        checked = brand.prestigeAnimations,
                        onCheckedChange = brandSkinActions.onSetPrestigeAnimations,
                    )
                }
            }
        }

        item(key = "studio-launch-rta") {
            Button(
                onClick = onOpenStageRta,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = foh.safeBg,
                    contentColor = foh.safeAccent,
                ),
                border = BorderStroke(1.dp, foh.safeBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(UiIcons.Rta, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Buka Layar Stage RTA", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StudioSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val foh = FohTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = foh.safeAccent,
            ),
        )
    }
}
