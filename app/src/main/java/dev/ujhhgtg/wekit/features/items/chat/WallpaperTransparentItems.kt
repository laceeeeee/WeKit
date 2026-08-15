package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

/**
 * 清除自定义聊天壁纸下微信自动添加的"可读性"浅灰背景:
 *
 * 1. 时间标签/系统提示/拍一拍/参与接龙 的灰色胶囊背景 — 全部来自
 *    com.tencent.mm.ui.chatting.component.v2#m0() 这一个资源出口
 *    (chatBgAttr.d=true 时返回 0x7f080500/0x7f0804ff)。hook 恒返回 0
 *    后, 所有 14 处调用方都变成 setBackgroundResource(0) = 无背景,
 *    与微信"未开背景"时的行为完全一致, 不存在时序竞态。
 *
 * 2. 引用消息的灰色胶囊背景 — sg5/f.a() 构造引用数据时把
 *    cze/czd (0x7f080500/0x7f0804ff) 硬编码进 df5/b.i, 之后
 *    io/t.c() 异步应用。hook sg5/f.a() 后置空 df5/b.i,
 *    引用渲染就不会设置灰底。
 */
@Feature(
    name = "壁纸元素透明化",
    categories = ["聊天"],
    description = "设置自定义聊天背景后, 清除时间标签/引用内容/系统提示的背景, 让壁纸透出"
)
object WallpaperTransparentItems : SwitchFeature(), IResolveDex {

    private const val TAG = "WallpaperTransparentItems"

    /**
     * com.tencent.mm.ui.chatting.component.v2#m0()I — 时间胶囊背景资源唯一出口。
     * 无日志字符串, 只能按类名 + 方法名 + 签名匹配。
     */
    private val methodTimePillRes by dexMethod()

    /**
     * sg5.f.a(Lfd5/d;Lcom/tencent/mm/storage/e9;Lcom/tencent/mm/ui/chatting/viewitems/g0;
     * Lcom/tencent/mm/ui/chatting/viewitems/a0;Ldf5/b;Lcom/tencent/mm/plugin/msgquote/model/MsgQuoteItem;)V
     * — 引用数据构造器, 第 5 个参数 (index 4) 是 df5/b, 其中 i 字段为背景资源。
     */
    private val methodQuoteDataBuilder by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodTimePillRes.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.component.v2"
                name = "m0"
                paramCount = 0
                returnType = "int"
            }
        }
        methodQuoteDataBuilder.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = "sg5.f"
                name = "a"
                paramCount = 6
                returnType = "void"
            }
        }
    }

    override fun onEnable() {
        if (!methodTimePillRes.isPlaceholder) {
            methodTimePillRes.hookBefore {
                // 恒返回 0: 所有调用方 setBackgroundResource(0) 即无背景
                result = 0
            }
            WeLogger.i(TAG, "time pill background hook enabled")
        } else {
            WeLogger.e(TAG, "methodTimePillRes unresolved, time pill background not cleared")
        }

        if (!methodQuoteDataBuilder.isPlaceholder) {
            methodQuoteDataBuilder.hookAfter {
                val quoteData = args.getOrNull(4) ?: return@hookAfter
                runCatching {
                    quoteData.reflekt()
                        .firstField { name = "i" }
                        .set(null)
                }.onFailure { WeLogger.e(TAG, "failed to clear quote background field", it) }
            }
            WeLogger.i(TAG, "quote background hook enabled")
        } else {
            WeLogger.e(TAG, "methodQuoteDataBuilder unresolved, quote background not cleared")
        }
    }
}