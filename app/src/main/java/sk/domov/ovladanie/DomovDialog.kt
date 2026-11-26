@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Jednotný vzhľad vyskakovacích okien appky (okrem výberu dátumu, ten má vlastné okno v WheelPicker.kt).
 * Okno je postavené rovnako ako okno s bubnami na dátum: zaoblená karta uprostred obrazovky, ťuk mimo okna ho zavrie.
 * Navrch má jantárový krúžok s ikonou (pri nebezpečnej akcii červený), hlavná akcia je vyplnené tlačidlo.
 */

/** Je obsah práve vnútri [DomovDialog]? Vtedy napr. [CheckRow] kreslí prepínač namiesto zaškrtávacieho políčka. */
internal val LocalInDomovDialog = staticCompositionLocalOf { false }

/** Nebezpečná akcia (mazanie, odhlásenie): hlavné tlačidlo je červené. */
internal val LocalDialogDanger = staticCompositionLocalOf { false }

/**
 * Základ všetkých okien. [confirmButton] a [dismissButton] sa kreslia vpravo dole v pevnej päte (obsah medzi nimi sa posúva,
 * keď sa nezmestí). Použi [DialogPrimaryButton] a [DialogTextButton].
 */
@Composable
internal fun DomovDialog(
    onDismiss: () -> Unit,
    title: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    danger: Boolean = false,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val noClick = remember { MutableInteractionSource() }
    val outsideClick = remember { MutableInteractionSource() }
    val scroll = rememberScrollState()
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }

    // decorFitsSystemWindows = false: okno je na celú obrazovku a okraje (stavový riadok, navigačná lišta, klávesnica, výrez)
    // si ošetríme sami cez WindowInsets – inak ich dialóg nehlási spoľahlivo a päta s tlačidlami sa mohla dostať pod displej
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // okno uprostred obrazovky (mimo systémových líšt a klávesnice); ťuk mimo okna ho zavrie
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .clickable(interactionSource = outsideClick, indication = null, onClick = onDismiss)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // poistka: aj keby okraje neboli nahlásené, okno nikdy nepresiahne viditeľnú časť displeja
            // (v pevnej päte je tlačidlo, ktoré sa musí dať stlačiť)
            val config = LocalConfiguration.current
            val reserve = if (config.screenHeightDp >= config.screenWidthDp) 112.dp else 56.dp
            val maxH = minOf(maxHeight, (config.screenHeightDp.dp - reserve).coerceAtLeast(240.dp))
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(animationSpec = tween(140)) + scaleIn(animationSpec = tween(180), initialScale = 0.92f),
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                        .clickable(interactionSource = noClick, indication = null, onClick = {}),
                    shape = RoundedCornerShape(28.dp),
                    color = scheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    CompositionLocalProvider(LocalInDomovDialog provides true, LocalDialogDanger provides danger) {
                        Column(modifier = Modifier.heightIn(max = maxH)) {
                            // hlavička: krúžok s ikonou + nadpis (+ podnadpis)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (icon != null) {
                                    DialogBadge(danger, icon)
                                    Spacer(Modifier.width(14.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    if (title != null) {
                                        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                                            ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                                        }
                                    }
                                    if (subtitle != null) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            // obsah (posúva sa, keď sa nezmestí)
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(scroll)
                                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                            ) {
                                CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)) {
                                        content()
                                    }
                                }
                            }
                            // päta s tlačidlami
                            if (confirmButton != null || dismissButton != null) {
                                if (scroll.canScrollForward || scroll.canScrollBackward) {
                                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                                }
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    dismissButton?.invoke()
                                    confirmButton?.invoke()
                                }
                            } else {
                                Spacer(Modifier.height(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Náhrada za Material `AlertDialog` s rovnakými pomenovanými parametrami, ale s vzhľadom appky. */
@Composable
internal fun DomovAlert(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    danger: Boolean = false,
) {
    DomovDialog(
        onDismiss = onDismissRequest,
        title = title,
        icon = icon,
        subtitle = subtitle,
        danger = danger,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    ) {
        text?.invoke()
    }
}

/** Jantárový (pri nebezpečnej akcii červený) krúžok s ikonou na začiatku okna. */
@Composable
private fun DialogBadge(danger: Boolean, icon: @Composable () -> Unit) {
    val bg = if (danger) MaterialTheme.colorScheme.errorContainer else Domov.colors.iconOn
    val fg = if (danger) MaterialTheme.colorScheme.error else Domov.colors.onIconOn
    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides fg) { icon() }
    }
}

/** Vedľajšia akcia v päte okna (Zrušiť, Zavrieť…). */
@Composable
internal fun DialogTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}

/** Hlavná akcia v päte okna: vyplnené tlačidlo (v nebezpečnom okne červené). */
@Composable
internal fun DialogPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (LocalDialogDanger.current) {
        ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, content = content)
}

/** Textové pole v oknách: plné, zaoblené, s jantárovým rámčekom, keď je aktívne. */
@Composable
internal fun DomovTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerHighest
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill,
            unfocusedContainerColor = fill,
            disabledContainerColor = fill,
            errorContainerColor = fill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * Jedna možnosť z viacerých ako karta s fajkou (namiesto bodkového políčka). [caption] je šedý popis pod názvom,
 * [trailing] šedý technický údaj vedľa názvu (napr. kód hodnoty).
 */
@Composable
internal fun DialogOption(
    title: String,
    selected: Boolean,
    caption: String? = null,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerLow)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.7f),
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trailing != null) {
                    Text(
                        trailing,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                Icon(Sym.check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        } else {
            Box(modifier = Modifier.size(24.dp).border(2.dp, scheme.outline.copy(alpha = 0.6f), CircleShape))
        }
    }
}

/** Zaoblené menu (⋮) s ikonami. */
@Composable
internal fun DomovMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 240.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = scheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
        content = content,
    )
}

/** Položka menu s ikonou. */
@Composable
internal fun DomovMenuItem(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = if (icon != null) {
            { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(12.dp)),
    )
}

/** Malý nadpis skupiny v menu. */
@Composable
internal fun DomovMenuLabel(text: String) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** Riadok „vyber hodnotu“ (dátum…) ako plné pole: ikona, malý popis, hodnota a šípka. */
@Composable
internal fun DialogPickRow(icon: ImageVector, caption: String, value: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        }
        Icon(Sym.chevronRight, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(20.dp))
    }
}

/** Karta v okne (nájdené zariadenie, položka zoznamu); [highlight] ju zvýrazní jantárovo (napr. nové zariadenie). */
@Composable
internal fun DialogCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainerLow)
            .border(1.dp, if (highlight) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant.copy(alpha = 0.7f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
