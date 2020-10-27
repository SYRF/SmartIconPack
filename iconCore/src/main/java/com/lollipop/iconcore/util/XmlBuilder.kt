package com.lollipop.iconcore.util

import android.text.TextUtils
import com.lollipop.iconcore.ui.IconHelper
import java.io.*
import java.lang.StringBuilder
import java.nio.charset.Charset

/**
 * @author lollipop
 * @date 10/27/20 23:52
 * Xml的构造器
 */
class XmlBuilder private constructor(private val tag: String) {

    companion object {
        private const val RETURN = "\r\n"
        private const val SPACE = " "

        const val RESOURCES = "resources"
        const val ITEM = "item"

        fun create(root: String): XmlBuilder {
            return XmlBuilder(root)
        }

        fun create(infoList: List<IconHelper.AppInfo>): XmlBuilder {
            return create(infoList.size) { infoList[it] }
        }

        fun create(count: Int, infoProvider: (Int) -> IconHelper.AppInfo): XmlBuilder {
            val builder = create(RESOURCES)
            for (index in 0 until count) {
                val info = infoProvider(index)
                builder.addChild(ITEM)
                    .addAttr(IconHelper.ATTR_NAME, info.name.toString())
                    .addAttr(IconHelper.ATTR_COMPONENT, info.pkg.toString())
                    .addAttr(IconHelper.ATTR_DRAWABLE,
                        info.pkg.packageName.replace(".", "_"))
            }
            return builder
        }
    }

    private var parent: XmlBuilder? = null
    private val children = ArrayList<XmlBuilder>()
    private val attributeList = ArrayList<Attribute>()
    private var text = ""

    private val commentList = ArrayList<String>()

    fun addChild(tag: String): XmlBuilder {
        val xml = XmlBuilder(tag)
        addChild(xml)
        return xml
    }

    fun addChild(xml: XmlBuilder) {
        xml.parent = this
        children.add(xml)
    }

    fun addAttr(name: String, value: String): XmlBuilder {
        attributeList.add(Attribute(name, value))
        return this
    }

    fun setText(value: String): XmlBuilder {
        this.text = value
        return this
    }

    fun addComment(value: String): XmlBuilder {
        commentList.add(value)
        return this
    }

    fun up(): XmlBuilder {
        return parent?:this
    }

    fun upToRoot(): XmlBuilder {
        return parent?.upToRoot()?:this
    }

    val hasParent: Boolean
        get() {
            return parent != null
        }

    override fun toString(): String {
        val builder = StringBuilder()
        if (!hasParent) {
            builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            builder.append(RETURN)
        }
        for (comment in commentList) {
            // <!--  -->
            builder.append("<!-- ")
            builder.append(comment)
            builder.append(" -->")
            builder.append(RETURN)
        }
        builder.append("<")
        builder.append(tag)
        builder.append(SPACE)
        for (attr in attributeList) {
            builder.append(attr.name)
            builder.append("=\"")
            builder.append(attr.value)
            builder.append("\"")
            builder.append(SPACE)
        }
        if (children.isEmpty() && TextUtils.isEmpty(text)) {
            builder.append("/>")
            return builder.toString()
        }
        builder.append(">")
        builder.append(text)
        if (children.isNotEmpty()) {
            builder.append(RETURN)
            for (child in children) {
                builder.append(child.toString())
                builder.append(RETURN)
            }
        }
        builder.append("</")
        builder.append(tag)
        builder.append(">")
        return builder.toString()
    }

    fun writeTo(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
            }
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            val buffer = ByteArray(2048)
            try {
                inputStream = ByteArrayInputStream(toString().toByteArray(Charsets.UTF_8))
                outputStream = FileOutputStream(file)
                var length = inputStream.read(buffer)
                while (length >= 0) {
                    outputStream.write(buffer, 0, length)
                    length = inputStream.read(buffer)
                }
                outputStream.flush()
            } catch (ee: Throwable) {
                ee.printStackTrace()
            } finally {
                inputStream?.close()
                outputStream?.close()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private data class Attribute(val name: String, val value: String)

}