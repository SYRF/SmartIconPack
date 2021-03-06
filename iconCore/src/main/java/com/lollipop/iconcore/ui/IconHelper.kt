package com.lollipop.iconcore.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Xml
import com.lollipop.iconcore.util.AppInfoCore
import com.lollipop.iconcore.util.TimeProfiler
import com.lollipop.iconcore.util.findDrawableId
import com.lollipop.iconcore.util.timeProfiler
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * @author lollipop
 * @date 10/22/20 02:32
 * 图标计算辅助类
 *
 * 它提供了主要的Icon处理操作
 * @param flags 图标处理的一些标示，它可以优化一些性能以及内存使用
 * 请见 {@link #FLAG_SUPPORTED_INFO},
 * {@link #FLAG_UNSUPPORTED_INFO},
 * {@link #FLAG_ICON_PACK_INFO}
 * @param customizeMap 自定义图标包提供器
 * 它可能是必要的，如果没有设置，
 * 那么可能会导致找不到对应的图标而认为没有适配
 */
class IconHelper private constructor(
    private val flags: Int,
    private val customizeMap: DrawableMapProvider? = null): AppInfoCore.AppChangeListener {

    companion object {

        /**
         * 只保留已适配应用的具体信息
         * 但是会保留未适配以及图标包的数量信息
         */
        const val FLAG_SUPPORTED_INFO = 1

        /**
         * 只保留未适配应用的具体信息
         * 但是会保留其他信息的数量
         */
        const val FLAG_UNSUPPORTED_INFO = 1 shl 1

        /**
         * 只保留图标包的信息
         * 这表示它不会记录系统应用的相关信息
         * 同时它会非常依赖{@link #customizeMap}
         */
        const val FLAG_ICON_PACK_INFO = 1 shl 2

        /**
         * 跳过AppInfo
         * 如果设置来此Flag，表示将不会检查应用列表
         * 这可能会加快数据的读取，但是也将得不到任何应用列表相关的数据
         * 甚至是应用数量
         */
        const val FLAG_SKIP_APP_INFO = 1 shl 3

        /**
         * 记录所有应用信息
         */
        const val FLAG_FULL_APP_INFO = FLAG_SUPPORTED_INFO or FLAG_UNSUPPORTED_INFO

        /**
         * 记录所有信息
         */
        const val FLAG_ALL_INFO = FLAG_FULL_APP_INFO or FLAG_ICON_PACK_INFO

        const val CATEGORY = "category"
        const val ITEM = "item"
        const val CATEGORY_DEFAULT = "default"

        const val ATTR_NAME = "name"
        const val ATTR_TITLE = "title"
        const val ATTR_COMPONENT = "component"
        const val ATTR_DRAWABLE = "drawable"

        private val EMPTY_ICON_ID = IntArray(0)
        private val EMPTY_COMPONENT = ComponentName("", "")
        private val EMPTY_ICON = IconInfo("", EMPTY_COMPONENT, 0)
        private val EMPTY_APP_INFO = AppInfo(EMPTY_COMPONENT, EMPTY_ICON_ID)

        /**
         * 以只记录已适配应用的形式创建
         */
        fun supportedOnly(creator: (context: Context) -> DrawableMap?): IconHelper {
            return IconHelper(FLAG_SUPPORTED_INFO, DrawableMapProvider(creator))
        }

        /**
         * 以只记录未适配应用的形式创建
         */
        fun unsupportedOnly(creator: (context: Context) -> DrawableMap?): IconHelper {
            return IconHelper(FLAG_UNSUPPORTED_INFO, DrawableMapProvider(creator))
        }

        /**
         * 以只记录图标包的形式创建
         */
        fun iconPackOnly(skipAppInfo: Boolean = false,
                         creator: (context: Context) -> DrawableMap?): IconHelper {
            val flag = if (skipAppInfo) {
                FLAG_ICON_PACK_INFO or FLAG_SKIP_APP_INFO
            } else {
                FLAG_ICON_PACK_INFO
            }
            return IconHelper(flag, DrawableMapProvider(creator))
        }

        /**
         * 根据名字检索Drawable的id
         */
        fun findDrawableId(context: Context, name: String): Int {
            return context.findDrawableId(name)
        }

        /**
         * 解析Component信息
         */
        fun parseComponent(info: String): ComponentName {
            if (!info.startsWith("ComponentInfo")) {
                return EMPTY_COMPONENT
            }
            val start = info.indexOf("{") + 1
            val end = info.indexOf("}")
            if (start > end || start == end) {
                return EMPTY_COMPONENT
            }
            val infoContent = info.substring(start, end)
            val split = infoContent.split("/")
            if (split.size < 2) {
                return EMPTY_COMPONENT
            }
            val pkg = split[0]
            val cls = split[1]
            val fullName = if (cls[0] == '.') { pkg + cls } else { cls }
            return ComponentName(pkg, fullName)
        }

        private fun activityFullName(pkg: String, cls: String): String {
            return if (cls[0] == '.') { pkg + cls } else { cls }
        }

        /**
         * 解析补全一个activity路径
         */
        fun String.fullName(pkg: String): String {
            return activityFullName(pkg, this)
        }

        /**
         * 自定义Flag的形式创建
         */
        fun newHelper(flags: Int, creator: (context: Context) -> DrawableMap?): IconHelper {
            return IconHelper(flags, DrawableMapProvider(creator))
        }

        /**
         * 通过包信息直接获取一个Drawable
         * 如果找不到匹配的包信息，那么将会返回一个无效的Drawable
         */
        fun loadIcon(context: Context, name: ComponentName): Drawable {
            return AppInfoCore.loadIcon(context, name)
        }

        /**
         * 通过包信息直接获取一个label
         * 如果找不到匹配的包信息，那么将会返回一个无效的CharSequence
         */
        fun getLabel(context: Context, name: ComponentName): CharSequence {
            return AppInfoCore.getLabel(context, name)
        }

    }

    private val notSupportList = ArrayList<AppInfo>()
    private val supportedList = ArrayList<AppInfo>()
    private var iconList = ArrayList<IconInfo>()
    private var drawableMap: DrawableMap? = null

    private var supportedListSize = 0
    private var notSupportListSize = 0
    private var iconListSize = 0

    /**
     * 所有应用的数量
     */
    val allAppCount: Int
        get() {
            return supportedCount + notSupportCount
        }

    /**
     * 未适配的应用数量
     */
    val notSupportCount: Int
        get() {
            if (!hasUnsupportedInfo) {
                return notSupportListSize
            }
            return notSupportList.size
        }

    /**
     * 已适配的应用数量
     */
    val supportedCount: Int
        get() {
            if (!hasSupportedInfo) {
                return supportedListSize
            }
            return supportedList.size
        }

    /**
     * 图标包的图标数量
     */
    val iconCount: Int
        get() {
            if (!hasIconPackInfo) {
                return iconListSize
            }
            return iconList.size
        }

    val skipAppInfo = flags and FLAG_SKIP_APP_INFO != 0

    val hasSupportedInfo = !skipAppInfo && (flags and FLAG_SUPPORTED_INFO != 0)

    val hasUnsupportedInfo = !skipAppInfo && (flags and FLAG_UNSUPPORTED_INFO != 0)

    val hasIconPackInfo = flags and FLAG_ICON_PACK_INFO != 0

    private var onAppListChangeCallback: ((IconHelper) -> Unit)? = null

    private var autoFixCallback: AutoFixCallback? = null

    /**
     * 按照序号获取图标信息
     * 如果flag中未保留图标包信息，或找不到有效的图标
     * 那么会返回一个空的图标信息
     */
    fun getIconInfo(index: Int): IconInfo {
        if (index < 0 || index >= iconCount || !hasIconPackInfo) {
            return EMPTY_ICON
        }
        return iconList[index]
    }

    /**
     * 按照序号获取应用信息
     * 如果flag中未保留应用信息，或找不到有效的应用
     * 那么会返回一个空的应用信息
     */
    fun getAppInfo(index: Int): AppInfo {
        if (index < 0 || index >= allAppCount) {
            return EMPTY_APP_INFO
        }
        if (index < supportedCount) {
            if (!hasSupportedInfo) {
                return EMPTY_APP_INFO
            }
            return getSupportedInfo(index)
        }
        if (!hasUnsupportedInfo) {
            return EMPTY_APP_INFO
        }
        return getNotSupportInfo(index - supportedCount)
    }

    /**
     * 按照序号获取未适配的应用信息
     * 如果flag中未保留未适配的应用信息，或找不到有效的应用
     * 那么会返回一个空的应用信息
     */
    fun getNotSupportInfo(index: Int): AppInfo {
        if (!hasUnsupportedInfo) {
            return EMPTY_APP_INFO
        }
        return notSupportList[index]
    }

    /**
     * 按照序号获取已适配的应用信息
     * 如果flag中未保留已适配的应用信息，或找不到有效的应用
     * 那么会返回一个空的应用信息
     */
    fun getSupportedInfo(index: Int): AppInfo {
        if (!hasSupportedInfo) {
            return EMPTY_APP_INFO
        }
        return supportedList[index]
    }

    /**
     * 加载应用信息
     * 需要触发它来激活并获取图标信息
     * 如果没有调用，那么将会导致获取不到有效的信息
     * 如果图标信息发生了变更，那么需要重新触发初始化信息
     */
    fun loadAppInfo(context: Context, autoFix: Boolean = true) {
        if (drawableMap == null) {
            drawableMap = customizeMap?.getDrawableMap(context)
        }
        val timeProfiler = timeProfiler()
        loadAppInfoOnly(context)
        timeProfiler.punch()
        loadIconPackInfoOnly(context)
        timeProfiler.punchAndPrintInterval()
        if (autoFix) {
            AppInfoCore.addAppChangeListener(this)
            autoFixCallback = AutoFixCallback(context)
        }
    }

    fun onAppListChange(callback: (IconHelper) -> Unit) {
        onAppListChangeCallback = callback
    }

    fun onDestroy() {
        offline()
        onAppListChangeCallback = null

    }

    private fun offline() {
        autoFixCallback = null
        AppInfoCore.removeAppChangeListener(this)
    }

    override fun onAppListChange() {
        autoFixCallback?.autoFix(this)
        onAppListChangeCallback?.invoke(this)
    }

    private fun loadAppInfoOnly(context: Context) {
        supportedList.clear()
        notSupportList.clear()
        supportedListSize = 0
        notSupportListSize = 0
        if (skipAppInfo) {
            return
        }

        AppInfoCore.init(context) {
            AppInfoCore.forEach { _, appInfo ->
                val pkgName = appInfo.activityInfo.packageName
                val clsName = appInfo.activityInfo.name.fullName(pkgName)
                val iconPack = getIconByPkg(context, pkgName, clsName)
                if (iconPack.isEmpty()) {
                    if (hasUnsupportedInfo) {
                        notSupportList.add(AppInfo(ComponentName(pkgName, clsName), iconPack))
                    } else {
                        notSupportListSize ++
                    }
                } else {
                    if (hasSupportedInfo) {
                        supportedList.add(AppInfo(ComponentName(pkgName, clsName), iconPack))
                    } else {
                        supportedListSize ++
                    }
                }
            }
        }
    }

    private fun loadIconPackInfoOnly(context: Context) {
        iconList.clear()
        iconListSize = 0
        val map = drawableMap
        if (hasIconPackInfo) {
            if (map == null) {
                val deduplicationList = ArrayList<Int>()
                for (app in supportedList) {
                    val iconPack = app.iconPack
                    if (iconPack.isEmpty()) {
                        continue
                    }
                    for (icon in iconPack) {
                        if (icon != 0 && deduplicationList.indexOf(icon) < 0) {
                            deduplicationList.add(icon)
                            iconList.add(IconInfo(context, app, icon))
                        }
                    }
                }
            } else {
                for (index in 0 until map.iconCount) {
                    iconList.add(map[index])
                }
            }
        } else {
            if (map != null) {
                iconListSize = map.iconCount
            } else if (supportedList.isNotEmpty()) {
                val deduplicationList = ArrayList<Int>()
                for (app in supportedList) {
                    val iconPack = app.iconPack
                    if (iconPack.isEmpty()) {
                        continue
                    }
                    for (icon in iconPack) {
                        if (icon != 0 && deduplicationList.indexOf(icon) < 0) {
                            deduplicationList.add(icon)
                            iconList.add(IconInfo(app.getLabel(context), app.pkg, icon))
                        }
                    }
                }
            } else {
                iconListSize = supportedListSize
            }
        }
    }

    private fun getIconByPkg(context: Context, pkg: String, cls: String): IntArray {
        val customize = drawableMap
        if (customize != null) {
            return customize.getDrawableName(pkg, cls)
        }
        val drawableName = pkg.replace(".", "_")
        val identifier = findDrawableId(context, drawableName)
        if (identifier == 0) {
            return EMPTY_ICON_ID
        }
        return intArrayOf(identifier)
    }

    /**
     * 自定义Drawable获取对象
     * 可以根据自己的定义来获取图标
     */
    interface DrawableMap {
        /**
         * 根据包名
         */
        fun getDrawableName(packageName: String, clsName: String): IntArray

        /**
         * 图标数量
         */
        val iconCount: Int

        /**
         * 返回一个图标对象
         */
        operator fun get(index: Int): IconInfo
    }

    /**
     * 图标包信息
     */
    class IconInfo private constructor(
        val nameProvider: () -> CharSequence, component: ComponentName, id: Int) {

        /**
         * @param label 图标名称
         * @param component 图标包对应的应用包名
         * @param id 图标对应的drawable id
         */
        constructor(label: CharSequence, component: ComponentName, id: Int):
                this({label}, component, id)

        /**
         * @param context 上下文
         * @param appInfo 应用信息
         * @param icon 图标对应的drawable id
         */
        constructor(context: Context, appInfo: AppInfo, icon: Int):
                this({ getLabel(context, appInfo.pkg)}, appInfo.pkg, icon)

        /**
         * 图标包的名称
         */
        val name: CharSequence by lazy {
            nameProvider()
        }

        /**
         * 包信息
         */
        val pkg: ComponentName = component

        /**
         * 资源id
         */
        val resId: Int = id
    }

    /**
     * 应用信息
     * @param pkg 应用包名
     * @param iconPack 应用对应的图标包（允许一个应用对应多个图标）
     */
    class AppInfo(val pkg: ComponentName, val iconPack: IntArray) {
        /**
         * 通过包信息生成一个图标名称
         */
        val drawableName: String by lazy {
            if (pkg.className.contains(pkg.packageName)) {
                pkg.className
            } else {
                pkg.packageName + "_" + pkg.className
            }.fullName(pkg.packageName)
                .replace(".", "_")
                .toLowerCase(Locale.getDefault())
        }

        private var myLabel: CharSequence? = null

        private var myIcon: Drawable? = null

        /**
         * 图标是否已经被加载过了
         */
        val iconIsLoaded: Boolean
            get() {
                return myIcon != null
            }

        /**
         * 应用名称是否已经被加载过了
         */
        val labelIsLoaded: Boolean
            get() {
                return myLabel != null
            }

        /**
         * 直接获取info中的icon信息
         * 但是它可能是空的
         * 你需要使用{@link #iconIsLoaded}来检查
         */
        fun optIcon(): Drawable? {
            return myIcon
        }

        /**
         * 直接获取info中的label信息
         * 但是它可能是空的
         * 你需要使用{@link #labelIsLoaded}来检查
         */
        fun optLabel(): CharSequence? {
            return myLabel
        }

        /**
         * 加载应用名称
         */
        fun getLabel(context: Context): CharSequence {
            val label = myLabel
            if (label != null) {
                return label
            }
            val l = getLabel(context, pkg)
            myLabel = l
            return l
        }

        /**
         * 加载应用图标
         */
        fun loadIcon(context: Context): Drawable {
            val icon = myIcon
            if (icon != null) {
                return icon
            }
            val loadIcon = loadIcon(context, pkg)
            myIcon = loadIcon
            return loadIcon
        }

    }

    /**
     * 默认的图标包配置清单解析器
     */
    class DefaultXmlMap(context: Context, xml: XmlPullParser): DrawableMap {

        private val iconMap = HashMap<String, ArrayList<IconInfo>>()
        private val allIcon = ArrayList<IconInfo>()
        private val iconCache = HashMap<String, IntArray>()

        private val keys: Array<String> by lazy {
            iconMap.keys.toTypedArray()
        }

        companion object {

            /**
             * 从Assets中读取配置清单文件
             */
            fun readFromAssets(context: Context, name: String): DefaultXmlMap {
                val newPullParser = Xml.newPullParser()
                newPullParser.setInput(context.assets.open(name), "UTF-8")
                return DefaultXmlMap(context, newPullParser)
            }

            /**
             * 从res中读取配置清单文件
             */
            fun readFromResource(context: Context, resId: Int): DefaultXmlMap {
                return DefaultXmlMap(context, context.resources.getXml(resId))
            }
        }

        init {
            decodeFromXml(xml, context)
        }

        private fun decodeFromXml(xml: XmlPullParser, context: Context) {
            var eventType = xml.eventType
            val defGroup = ArrayList<IconInfo>()
            iconMap[CATEGORY_DEFAULT] = defGroup
            var iconGroup = defGroup
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = xml.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (CATEGORY == tagName) {
                            val name = xml.getAttributeValue(null, ATTR_TITLE)
                            iconGroup = iconMap[name] ?: ArrayList()
                            iconMap[name] = iconGroup
                        } else if (ITEM == tagName) {
                            val name = xml.getAttributeValue(null, ATTR_NAME) ?: ""
                            val pkg = xml.getAttributeValue(null, ATTR_COMPONENT) ?: ""
                            val icon = xml.getAttributeValue(null, ATTR_DRAWABLE) ?: ""
                            val info = IconInfo(name, parseComponent(pkg), findDrawableId(context, icon))
                            iconGroup.add(info)
                            allIcon.add(info)
                        }
                    }
                }
                eventType = xml.next()
            }
            if (defGroup.isEmpty()) {
                iconMap.remove(CATEGORY_DEFAULT)
            }
            if (xml is AutoCloseable) {
                xml.close()
            } else if (xml is Closeable) {
                xml.close()
            }
        }

        override fun getDrawableName(packageName: String, clsName: String): IntArray {
            val token = packageName + clsName
            val cache = iconCache[token]
            if (cache != null) {
                return cache
            }
            val icons = ArrayList<IconInfo>()
            for (icon in allIcon) {
                if (icon.pkg.packageName == packageName && icon.pkg.className == clsName) {
                    icons.add(icon)
                }
            }
            val idArray = IntArray(icons.size) { index -> icons[index].resId }
            iconCache[token] = idArray
            return idArray
        }

        override val iconCount: Int
            get() {
                return allIcon.size
            }

        override fun get(index: Int): IconInfo {
            return allIcon[index]
        }

        val categoryCount: Int
            get() {
                return keys.size
            }

        fun getCategory(index: Int): String {
            return keys[index]
        }

        fun getIcon(category: String, index: Int): IconInfo {
            return iconMap[category]?.get(index)?:EMPTY_ICON
        }

        fun iconCountByCategory(category: String): Int {
            return iconMap[category]?.size?:0
        }

    }

    /**
     * 图标包提供者包装类
     */
    class DrawableMapProvider(private val creator: (context: Context) -> DrawableMap?) {

        private var drawableMap: DrawableMap? = null
        private var isInit = false

        fun getDrawableMap(context: Context): DrawableMap? {
            if (!isInit) {
                drawableMap = creator(context)
                isInit = true
            }
            return drawableMap
        }
    }

    private class AutoFixCallback(context: Context) {
        private val contextWrapper = WeakReference(context)

        fun autoFix(helper: IconHelper) {
            val context = contextWrapper.get()
            if (context != null) {
                helper.loadAppInfoOnly(context)
            } else {
                helper.offline()
            }
        }

    }

}