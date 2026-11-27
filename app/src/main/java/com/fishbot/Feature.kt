package com.fishbot

/** Что функции нужно от приложения (пока справочно; захват экрана включается всегда). */
enum class Need { CAPTURE, TAPS }

/** То, что оболочка (FloatingService) даёт функции. */
interface FeatureHost {
    fun setPanelVisible(visible: Boolean)
    fun stopWork()
    fun isWorking(): Boolean
    fun appContext(): android.content.Context
    fun screenSize(): Pair<Int, Int>
    fun toast(msg: String)
    fun marker(x: Int, y: Int, color: Int)
    /** Один кадр экрана целиком (пиксели ARGB) или null, если кадра нет. Вызывать НЕ из главного потока. */
    fun grabFrame(): ScanFrame?
    /** Причина последней неудачи grabFrame() для лога ("ok", если кадр был). */
    fun grabWhy(): String
}

/**
 * Функция в меню «Функционал». Чтобы добавить новую: написать object XFeature : Feature
 * и дописать его в ALL_FEATURES. Строка с галочкой в меню появится сама.
 * onDisable обязан остановить работу и быть безопасным при повторном вызове.
 */
interface Feature {
    val id: String
    val title: String
    val needs: Set<Need>
    val defaultOn: Boolean
    fun onEnable(host: FeatureHost)
    fun onDisable(host: FeatureHost)
}

/** Авторыбалка: тонкая обёртка, логика ловли остаётся в BotLoop/RodSwap. */
object FishFeature : Feature {
    override val id = "fish"
    override val title = "Авторыбалка"
    override val needs = setOf(Need.CAPTURE, Need.TAPS)
    override val defaultOn = true
    override fun onEnable(host: FeatureHost) { host.setPanelVisible(true) }
    override fun onDisable(host: FeatureHost) { host.stopWork(); host.setPanelVisible(false) }
}

/** Кадр экрана для функций: ширина, высота, пиксели ARGB (w*h штук). */
class ScanFrame(val w: Int, val h: Int, val px: IntArray)

val ALL_FEATURES: List<Feature> = listOf(FishFeature, BerriesFeature, BerryScanFeature)
