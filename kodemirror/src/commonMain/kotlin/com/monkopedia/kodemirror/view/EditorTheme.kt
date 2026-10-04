/*
 * Copyright 2026 Jason Monk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Originally based on CodeMirror 6 by Marijn Haverbeke, licensed under MIT.
 * See NOTICE file for details.
 */
@file:OptIn(ExperimentalTextApi::class)

package com.monkopedia.kodemirror.view

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkopedia.kodemirror.state.Facet

/**
 * Default font family for the editor.
 *
 * Uses [FontFamily.Monospace], which maps to the platform's default monospace
 * font on JVM, Android, iOS, and macOS.
 *
 * ## wasmJs / CanvasKit limitation
 *
 * On **wasmJs**, Compose renders via CanvasKit (Skiko), which maintains its
 * own internal font registry separate from the OS font system. Generic family
 * names such as `"Roboto Mono"` or `"JetBrains Mono"` **will not resolve**
 * unless the font has been explicitly registered with CanvasKit. Even
 * [FontFamily.Monospace] may fall back to a generic sans-serif glyph.
 *
 * To guarantee a specific monospace font on wasmJs (and all other targets),
 * bundle the font as a Compose Resource and load it via
 * [org.jetbrains.compose.resources.Font]:
 *
 * ```kotlin
 * // In a @Composable context — Font() requires composition
 * val monoFont = FontFamily(Font(Res.font.JetBrainsMono_Regular))
 * val fontExtension = editorContentStyle.of(TextStyle(fontFamily = monoFont))
 *
 * // Pass the extension when creating the EditorState:
 * val state = EditorState.create(
 *     EditorStateConfig(extensions = listOf(basicSetup, fontExtension))
 * )
 * ```
 *
 * See [editorContentStyle] for the full font-override API.
 */
val defaultEditorFontFamily: FontFamily = FontFamily.Monospace

/**
 * A typed key for extensible theme properties. Modules define their own keys
 * with sensible defaults; themes can override them via [themeExtras].
 *
 * Uses identity equality so each `ThemeKey` instance is unique.
 */
class ThemeKey<T>(val default: T)

/**
 * Layout measurements for the editor. Nested inside [EditorTheme] to separate
 * layout concerns from color properties.
 */
@Immutable
data class EditorLayout(
    val gutterStartPadding: Dp = 5.dp,
    val gutterEndPadding: Dp = 3.dp,
    val customGutterWidth: Dp = 14.dp,
    val contentTopPadding: Dp = 4.dp,
    val contentBottomPadding: Dp = 4.dp,
    val panelBorderWidth: Dp = 1.dp,
    val dropCursorWidth: Dp = 2.dp
)

/**
 * Color/style tokens for the editor.  Passed through a [CompositionLocal] so
 * every composable in the editor tree can read them without prop-drilling.
 */
@Immutable
data class EditorTheme(
    /** Background color of the editor container. */
    val background: Color = Color(0xFF282C34),
    /** Default foreground (text) color. Can be overridden by setting a color in [editorContentStyle]. */
    val foreground: Color = Color(0xFFABB2BF),
    /** Cursor color. */
    val cursor: Color = Color(0xFF528BFF),
    /** Selection background color. */
    val selection: Color = Color(0xFF3E4451),
    /** Active line background highlight. */
    val activeLineBackground: Color = Color(0x0B6699FF),
    /** Gutter background. */
    val gutterBackground: Color = Color(0xFF282C34),
    /** Gutter foreground (line numbers). */
    val gutterForeground: Color = Color(0xFF7D8799),
    /** Gutter active foreground. */
    val gutterActiveForeground: Color = Color(0xFFCCCCCC),
    /** Gutter right border color. */
    val gutterBorderColor: Color = Color.Transparent,
    /** Background for search matches. */
    val searchMatchBackground: Color = Color(0x5972A1FF),
    /** Background for the selected/active search match. */
    val searchMatchSelectedBackground: Color = Color(0x2F6199FF),
    /** Background for matches of the current selection word. */
    val selectionMatchBackground: Color = Color(0x1AAAFE66),
    /** Background for matching brackets. */
    val matchingBracketBackground: Color = Color(0x4400CC00),
    /** Background for non-matching brackets. */
    val nonMatchingBracketBackground: Color = Color(0x44CC0000),
    /** Panel background. */
    val panelBackground: Color = Color(0xFF282C34),
    /** 1px separator between panel and editor. */
    val panelBorderColor: Color = Color(0xFF555555),
    /** Panel button fill. */
    val buttonBackground: Color = Color(0xFF393939),
    /** Panel button border. */
    val buttonBorderColor: Color = Color(0xFF888888),
    /** Text input fill. */
    val inputBackground: Color = Color(0xFF282C34),
    /** Text input border. */
    val inputBorderColor: Color = Color(0xFF555555),
    /** Tooltip background. */
    val tooltipBackground: Color = Color(0xFF353A42),
    /** Fold placeholder text color. */
    val foldPlaceholderColor: Color = Color(0xFFDDDDDD),
    /** Fold placeholder widget background. */
    val foldPlaceholderBackground: Color = Color(0x33EEEEEE),
    /** Active line gutter background. */
    val activeLineGutterBackground: Color = Color(0x0B6699FF),
    /** Whether this is a dark theme (affects some rendering decisions). */
    val dark: Boolean = true,
    /** Layout measurements for the editor. */
    val layout: EditorLayout = EditorLayout(),
    /** Extensible theme properties for module-specific colors/values. */
    val extras: Map<ThemeKey<*>, Any?> = emptyMap()
) {
    /** Look up a module-specific theme value by its [ThemeKey]. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: ThemeKey<T>): T =
        if (extras.containsKey(key)) extras[key] as T else key.default
}

/** Build a theme extras map from key-value pairs. */
fun themeExtras(vararg entries: Pair<ThemeKey<*>, Any?>): Map<ThemeKey<*>, Any?> = mapOf(*entries)

/** Merge additional [extras] into this theme's extras map. */
operator fun EditorTheme.plus(extras: Map<ThemeKey<*>, Any?>): EditorTheme =
    copy(extras = this.extras + extras)

/** Color for visible whitespace marks (spaces and tabs). */
val whitespaceColor = ThemeKey(default = Color(0x40808080))

/** Background color for trailing whitespace. */
val trailingWhitespaceBackground = ThemeKey(default = Color(0x30FF6666))

/** Text color for special (non-printing) character placeholders. */
val specialCharForeground = ThemeKey(default = Color.White)

/** Background color for special (non-printing) character placeholders. */
val specialCharBackground = ThemeKey(default = Color(0xFFCC0000))

/** A default dark theme. */
val defaultEditorTheme: EditorTheme = EditorTheme()

/** A light theme. */
val lightEditorTheme: EditorTheme = EditorTheme(
    background = Color(0xFFFFFFFF),
    foreground = Color(0xFF000000),
    cursor = Color(0xFF000000),
    selection = Color(0xFFD7D4F0),
    activeLineBackground = Color(0x44CCEEFF),
    gutterBackground = Color(0xFFF5F5F5),
    gutterForeground = Color(0xFF6C6C6C),
    gutterActiveForeground = Color(0xFF333333),
    gutterBorderColor = Color(0xFFDDDDDD),
    searchMatchBackground = Color(0x80FFD54F),
    searchMatchSelectedBackground = Color(0x4000BFA5),
    selectionMatchBackground = Color(0x30A0D000),
    matchingBracketBackground = Color(0x4400CC00),
    nonMatchingBracketBackground = Color(0x44CC0000),
    panelBackground = Color(0xFFF5F5F5),
    panelBorderColor = Color(0xFFDDDDDD),
    buttonBackground = Color(0xFFEFF1F5),
    buttonBorderColor = Color(0xFF888888),
    inputBackground = Color(0xFFFFFFFF),
    inputBorderColor = Color(0xFFC0C0C0),
    tooltipBackground = Color(0xFFF5F5F5),
    foldPlaceholderColor = Color(0xFF555555),
    foldPlaceholderBackground = Color(0xFFEEEEEE),
    activeLineGutterBackground = Color(0x44CCEEFF),
    dark = false
)

/** CompositionLocal that provides the current [EditorTheme]. */
val LocalEditorTheme = compositionLocalOf { defaultEditorTheme }

/** Facet that lets extensions override the editor theme. */
val editorTheme: Facet<EditorTheme, EditorTheme> = Facet.define(
    combine = { values -> values.lastOrNull() ?: defaultEditorTheme }
)

/** Default content text style with font metrics but no color. */
val defaultContentTextStyle: TextStyle = TextStyle(
    fontFamily = defaultEditorFontFamily,
    fontSize = 15.sp,
    lineHeight = (15 * 1.4).sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both
    )
)

/**
 * Facet for editor content text styling (font family, size, line height, color).
 *
 * Multiple values are merged via [TextStyle.merge] — later extensions override
 * earlier ones field-by-field. If no extension specifies a text color, the
 * editor falls back to [EditorTheme.foreground].
 *
 * ```kotlin
 * // Override just font size:
 * editorContentStyle.of(TextStyle(fontSize = 18.sp, lineHeight = (18 * 1.4).sp))
 *
 * // Override font family (works on all targets including wasmJs):
 * editorContentStyle.of(TextStyle(fontFamily = myFontFamily))
 * ```
 *
 * ## wasmJs font loading
 *
 * On wasmJs system font names are not available to CanvasKit — fonts must be
 * loaded from bundled Compose Resources. Because [org.jetbrains.compose.resources.Font]
 * requires a composable context, build the extension inside a `@Composable`
 * function and pass it to [EditorState][com.monkopedia.kodemirror.state.EditorState]:
 *
 * ```kotlin
 * @Composable
 * fun MyEditor() {
 *     val monoFont = FontFamily(Font(Res.font.JetBrainsMono_Regular))
 *     val session = rememberEditorSession(
 *         EditorStateConfig(
 *             extensions = listOf(
 *                 basicSetup,
 *                 editorContentStyle.of(TextStyle(fontFamily = monoFont))
 *             )
 *         )
 *     )
 *     KodeMirror(session)
 * }
 * ```
 *
 * See [defaultEditorFontFamily] for background on the wasmJs limitation.
 */
val editorContentStyle: Facet<TextStyle, TextStyle> = Facet.define(
    combine = { values ->
        values.fold(defaultContentTextStyle) { acc, style -> acc.merge(style) }
    }
)

/** CompositionLocal that provides the resolved content text style. */
val LocalContentTextStyle = compositionLocalOf { defaultContentTextStyle }

/** Convenience extension to build a [SpanStyle] from theme colors. */
fun EditorTheme.selectionStyle(): SpanStyle = SpanStyle(background = selection)

/** Convenience extension to build a [SpanStyle] for active line. */
fun EditorTheme.activeLineStyle(): SpanStyle = SpanStyle(background = activeLineBackground)

/**
 * Create an [EditorTheme] from semantic color values, designed for easy
 * integration with Material Design color schemes.
 *
 * ```kotlin
 * // With Material 3:
 * val colors = MaterialTheme.colorScheme
 * val theme = editorThemeFromColors(
 *     background = colors.surface,
 *     foreground = colors.onSurface,
 *     primary = colors.primary,
 *     surface = colors.surfaceVariant,
 *     outline = colors.outline,
 *     dark = colors.surface.luminance() < 0.5f
 * )
 * ```
 *
 * @param background Editor background color
 * @param foreground Default text color
 * @param primary Accent color (cursor, active elements)
 * @param surface Surface color (panels, tooltips, gutter)
 * @param outline Border/separator color
 * @param dark Whether this is a dark theme
 */
fun editorThemeFromColors(
    background: Color,
    foreground: Color,
    primary: Color,
    surface: Color,
    outline: Color,
    dark: Boolean
): EditorTheme {
    val dimForeground = foreground.copy(alpha = 0.5f)
    val subtleHighlight = primary.copy(alpha = if (dark) 0.15f else 0.2f)
    val selectionColor = primary.copy(alpha = if (dark) 0.3f else 0.25f)
    return EditorTheme(
        background = background,
        foreground = foreground,
        cursor = primary,
        selection = selectionColor,
        activeLineBackground = subtleHighlight,
        gutterBackground = surface,
        gutterForeground = dimForeground,
        gutterActiveForeground = foreground,
        gutterBorderColor = outline,
        searchMatchBackground = primary.copy(alpha = 0.35f),
        searchMatchSelectedBackground = primary.copy(alpha = 0.2f),
        selectionMatchBackground = primary.copy(alpha = 0.1f),
        matchingBracketBackground = Color(0x4400CC00),
        nonMatchingBracketBackground = Color(0x44CC0000),
        panelBackground = surface,
        panelBorderColor = outline,
        buttonBackground = surface,
        buttonBorderColor = outline,
        inputBackground = background,
        inputBorderColor = outline,
        tooltipBackground = surface,
        foldPlaceholderColor = dimForeground,
        foldPlaceholderBackground = surface,
        activeLineGutterBackground = subtleHighlight,
        dark = dark
    )
}
