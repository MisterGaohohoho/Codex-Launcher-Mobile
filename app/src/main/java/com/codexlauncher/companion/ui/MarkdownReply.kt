package com.codexlauncher.companion.ui

import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownReply(markdown: String) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val linkColor = colors.primary.toArgb()
    val codeColor = colors.surfaceContainerHighest.toArgb()
    val textSize =
        with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.fontSize.toPx() }
    val renderer =
        remember(context, textColor, linkColor, codeColor) {
            Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(
                    object : AbstractMarkwonPlugin() {
                        override fun configureTheme(builder: MarkwonTheme.Builder) {
                            builder
                                .linkColor(linkColor)
                                .codeTextColor(textColor)
                                .codeBackgroundColor(codeColor)
                                .codeBlockBackgroundColor(codeColor)
                                .blockQuoteColor(linkColor)
                                .headingBreakHeight(0)
                                .headingTextSizeMultipliers(
                                    floatArrayOf(1.5f, 1.35f, 1.2f, 1.1f, 1f, 1f)
                                )
                        }

                        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                            builder.linkResolver { view, link ->
                                val uri = Uri.parse(link)
                                if (uri.scheme in listOf("https", "http")) {
                                    runCatching {
                                        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                }
                            }
                        }
                    }
                )
                .build()
        }
    val content = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    AndroidView(
        factory = {
            TextView(it).apply {
                setTextIsSelectable(true)
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1.2f)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        update = { view ->
            view.setTextColor(textColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            renderer.setParsedMarkdown(view, content)
        },
    )
}
