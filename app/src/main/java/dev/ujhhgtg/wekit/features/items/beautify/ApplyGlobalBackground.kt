package dev.ujhhgtg.wekit.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import coil3.load
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.nul
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import kotlin.math.max
import kotlin.math.roundToInt
import org.luckypray.dexkit.DexKitBridge

@Feature(
    name = "应用全局背景", categories = ["界面美化"],
    description = "将聊天界面背景替换为图片，铺满整个屏幕"
)
object ApplyGlobalBackground : ClickableFeature(), IResolveDex {

    private const val TAG = "ApplyGlobalBackground"

    private var backgroundUri by prefOption("global_bg_uri", nul<String>())
    private var transparentStatusBar by prefOption("global_bg_transparent_status_bar", false)
    private var opacity by prefOption("global_bg_opacity", 1.0f)

    private const val OVERLAY_TAG = "wekit_global_bg_overlay"
    private const val APPLIED_URI_TAG_KEY = 0x55020001
    private const val ORIGIN_BG_TAG_KEY = 0x55020002
    private const val APPLY_STATUS_BAR_DELAY_MS = 80L
    private const val CHATTING_FRAGMENT_CLASS = "com.tencent.mm.ui.chatting.ChattingUIFragment"

    /** 设置了背景图片后重启即生效，无需手动打开功能开关。 */
    override val defaultEnabled: Boolean = true

    override fun resolveDex(dexKit: DexKitBridge) {
        // 聊天界面 hook 通过运行时反射完成，无需 DexKit 符号查找。
    }

    override fun onEnable() {
        Activity::class.reflekt().apply {
            firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onStart"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
                if (isChattingScreen(activity)) {
                    WeLogger.i(TAG, "chatting screen detected on ${activity.javaClass.name}")
                    applyBackground(activity)
                }
            }

            firstMethod {
                name = "onWindowFocusChanged"
                parameters(Boolean::class)
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }
        }

        hookChatFooter()
    }

    /**
     * 聊天界面的主探针：ChatFooter 是微信 pluginsdk 的公共 SDK 类（8.0.76 未混淆），
     * 只在聊天界面创建。它 attach 到窗口时整个聊天布局已就绪，此时铺背景最可靠，
     * 完全绕开 8.0.76 中被混淆的 ChattingUIFragment 方法。
     */
    private fun hookChatFooter() {
        runCatching {
            ChatFooter::class.reflekt().firstMethod { name = "onAttachedToWindow" }.hookAfter {
                val footer = thisObject as View
                val activity = activityOf(footer.context) ?: return@hookAfter
                WeLogger.i(TAG, "ChatFooter attached on ${activity.javaClass.name}")
                applyBackground(activity)
                if (transparentStatusBar) applyTransparentStatusBar(activity)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook ChatFooter.onAttachedToWindow", it)
        }
    }

    private fun activityOf(ctx: Context): Activity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * 聊天界面判定：不依赖任何微信方法名（8.0.76 起 ChattingUIFragment 方法名被混淆）。
     * 通过 Activity 类名 + FragmentManager 实例遍历双重确认。
     */
    private fun isChattingScreen(activity: Activity): Boolean {
        if (isChattingActivity(activity)) return true
        val fragmentActivity = activity as? FragmentActivity ?: return false
        return fragmentActivity.supportFragmentManager.fragments.any { containsChattingFragment(it) }
    }

    private fun containsChattingFragment(fragment: Fragment): Boolean {
        if (fragment.javaClass.name == CHATTING_FRAGMENT_CLASS) return true
        return fragment.childFragmentManager.fragments.any { containsChattingFragment(it) }
    }

    private fun isChattingActivity(activity: Activity): Boolean =
        activity.javaClass.name.startsWith("${PackageNames.WECHAT}.ui.chatting.ChattingUI")

    private fun applyTransparentStatusBarIfEnabled(activity: Activity) {
        if (!transparentStatusBar) return
        if (!isChattingScreen(activity)) return
        applyTransparentStatusBar(activity)
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            val decor = window.decorView as? ViewGroup ?: return

            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }

            clearSystemBarBackgrounds(activity, decor)
            decor.postDelayed(APPLY_STATUS_BAR_DELAY_MS) {
                clearSystemBarBackgrounds(activity, decor)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to apply transparent status bar", it)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun clearSystemBarBackgrounds(activity: Activity, decor: ViewGroup) {
        for (resName in listOf("statusBarBackground", "navigationBarBackground")) {
            val systemBarBackgroundId = activity.resources.getIdentifier(
                resName,
                "id",
                "android"
            )

            if (systemBarBackgroundId != 0) {
                decor.findViewById<View>(systemBarBackgroundId)?.makeTransparent()
            }
        }

        setLastViewsTransparent(decor, 3)
    }

    private fun setLastViewsTransparent(viewGroup: ViewGroup, count: Int) {
        val start = max(0, viewGroup.childCount - count)
        for (index in start until viewGroup.childCount) {
            val child = viewGroup.getChildAt(index)
            val name = child.resourceEntryName().orEmpty()
            if (name == "statusBarBackground" || name == "navigationBarBackground" ||
                child.height <= statusBarHeightGuess(child)
            ) {
                child.makeTransparent()
            }
        }
    }

    private const val MIN = 0.01f
    private const val MAX = 1.0f
    private val MINIMAX = MIN..MAX
    private fun Float.miniMaxed() = this.coerceIn(MINIMAX)

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hasImage by remember { mutableStateOf(backgroundUri != null) }
            var opacityInput by remember { mutableFloatStateOf(opacity) }
            var transparentStatusBarInput by remember { mutableStateOf(transparentStatusBar) }

            AlertDialogContent(
                title = { Text("应用全局背景") },
                text = {
                    DefaultColumn {
                        Text(
                            text = if (hasImage) {
                                "已设置背景图片"
                            } else {
                                "未设置背景图片"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = {
                                opacity = opacityInput.miniMaxed()
                                transparentStatusBar = transparentStatusBarInput
                                onDismiss()
                                selectBackgroundImage(context)
                            }) {
                                Text("选择图片")
                            }
                            TextButton(
                                enabled = hasImage,
                                onClick = {
                                    backgroundUri = null
                                    hasImage = false
                                    showToast("已清除背景图片, 重启微信生效")
                                }
                            ) {
                                Text("清除图片")
                            }
                        }
                        Text(
                            text = "透明度: ${(opacityInput * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Slider(
                            value = opacityInput,
                            onValueChange = { opacityInput = it.miniMaxed() },
                            valueRange = MINIMAX
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                transparentStatusBarInput = !transparentStatusBarInput
                            },
                            trailingContent = {
                                Switch(
                                    checked = transparentStatusBarInput,
                                    onCheckedChange = null
                                )
                            },
                            supportingContent = { Text("仅在聊天界面生效，背景铺满整个屏幕") },
                            headlineContent = { Text("状态栏/导航栏透明") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        opacity = opacityInput.miniMaxed()
                        transparentStatusBar = transparentStatusBarInput
                        showToast("已保存, 重启微信生效")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    /**
     * 背景主逻辑：把背景图铺到窗口最底层（覆盖整个屏幕，包括状态栏/导航栏区域），
     * 并把聊天界面里的纯色背景视图清成透明，露出背景图。
     */
    private fun applyBackground(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val uri = backgroundUri

        if (uri == null) {
            restoreContentBackgrounds(decor)
            findOverlay(decor)?.let { decor.removeView(it) }
            return
        }

        val overlay = findOverlay(decor) ?: createOverlay(activity, decor)
        overlay.visibility = View.VISIBLE
        overlay.alpha = opacity

        if (overlay.getTag(APPLIED_URI_TAG_KEY) != uri) {
            overlay.setTag(APPLIED_URI_TAG_KEY, uri)
            WeLogger.i(TAG, "loading background $uri on ${activity.javaClass.name}")
            overlay.load(uri) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        WeLogger.i(TAG, "background loaded OK on ${activity.javaClass.name}")
                    },
                    onError = { _, result ->
                        WeLogger.w(TAG, "background load failed on ${activity.javaClass.name}", result.throwable)
                    }
                )
            }
        }

        transparentizeContentBackgrounds(decor, overlay)
    }

    private fun createOverlay(context: Context, decor: ViewGroup): ImageView {
        return ImageView(context).apply {
            tag = OVERLAY_TAG
            background = null
            setBackgroundColor(0xFFEDEDED.toInt())
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            decor.addView(this, 0)
        }
    }

    private fun findOverlay(decor: ViewGroup): ImageView? {
        for (index in 0 until decor.childCount) {
            val child = decor.getChildAt(index)
            if (child is ImageView && child.tag == OVERLAY_TAG) {
                return child
            }
        }
        return null
    }

    private fun transparentizeContentBackgrounds(decor: ViewGroup, overlay: ImageView) {
        for (index in 0 until decor.childCount) {
            val child = decor.getChildAt(index)
            if (child === overlay) continue
            child.transparentizePageBackgrounds(child.height)
        }
    }

    private fun View.transparentizePageBackgrounds(parentHeight: Int) {
        if (this is Button) return
        if (getTag(ORIGIN_BG_TAG_KEY) == null) {
            setTag(ORIGIN_BG_TAG_KEY, background)
        }
        setBackgroundColor(Color.TRANSPARENT)
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.height >= parentHeight * 0.8f) {
                    child.transparentizePageBackgrounds(child.height)
                }
            }
        }
    }

    private fun restoreContentBackgrounds(decor: ViewGroup) {
        for (index in 0 until decor.childCount) {
            val child = decor.getChildAt(index)
            if (child is ImageView && child.tag == OVERLAY_TAG) continue
            child.restoreTree()
        }
    }

    private fun View.restoreTree() {
        val origin = getTag(ORIGIN_BG_TAG_KEY) as? Drawable
        if (origin != null) {
            setBackground(origin)
            setTag(ORIGIN_BG_TAG_KEY, null)
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).restoreTree()
            }
        }
    }

    private fun View.makeTransparent() {
        setBackgroundColor(Color.TRANSPARENT)
        setBackgroundResource(0)
    }

    private fun View.resourceEntryName(): String? {
        val viewId = id
        if (viewId == View.NO_ID) return null
        return runCatching {
            resources.getResourceEntryName(viewId)
        }.getOrNull()
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeightGuess(view: View): Int {
        val resourceId = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            view.resources.getDimensionPixelSize(resourceId)
        } else {
            (32f * view.resources.displayMetrics.density).toInt()
        }
    }

    private fun selectBackgroundImage(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                val contentResolver = HostInfo.application.contentResolver
                val storedUri = runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    val displayName = contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "wekit_chat_bg.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(
                            MediaStore.Images.Media.MIME_TYPE,
                            contentResolver.getType(uri) ?: "image/jpeg"
                        )
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/WeKit"
                        )
                    }
                    val dest = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: return@runCatching null
                    contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                        ?: return@runCatching null
                    dest.toString()
                }.getOrNull()

                if (storedUri == null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }.onFailure {
                        WeLogger.w(TAG, "failed to take persistable uri permission", it)
                    }
                    backgroundUri = uri.toString()
                } else {
                    backgroundUri = storedUri
                }
                showToast("背景图片已设置, 重启微信生效")
            }

            launcher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }
}
