package com.lollipop.iconkit.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.lollipop.iconcore.ui.IconHelper
import com.lollipop.iconcore.ui.IconImageView
import com.lollipop.iconcore.util.delay
import com.lollipop.iconcore.util.doAsync
import com.lollipop.iconkit.LIconKit
import com.lollipop.iconkit.R
import kotlinx.android.synthetic.main.kit_fragment_request.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max

class RequestFragment : BaseTabFragment() {
    override val tabIcon: Int
        get() = R.drawable.ic_baseline_architecture_24
    override val tabTitle: Int
        get() = R.string.request
    override val tabColorId: Int
        get() = R.color.tabRequestSelectedColor
    override val layoutId: Int
        get() = R.layout.kit_fragment_request

    private val appInfoList = ArrayList<RequestAppInfo>()

    private val appAdapter = AppAdapter(appInfoList)

    private val iconHelper = IconHelper(IconHelper.DrawableMapProvider {
        LIconKit.createRequestPageMap(it)
    })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appList.layoutManager = LinearLayoutManager(
            view.context, RecyclerView.VERTICAL, false)
        appList.adapter = appAdapter
        appAdapter.notifyDataSetChanged()

        doAsync {
            iconHelper.loadAppInfo(view.context)
            appInfoList.clear()
            for (index in 0 until iconHelper.notSupportCount) {
                appInfoList.add(RequestAppInfo.create(iconHelper.getNotSupportInfo(index)))
            }
            Collections.sort(appInfoList, RequestAppComparator())
            delay(appList.animate().duration) {
                appAdapter.notifyDataSetChanged()
            }
        }
    }

    private class RequestAppComparator: Comparator<RequestAppInfo> {
        /**
         * a negative integer, zero, or a positive integer as the
         *         first argument is less than, equal to, or greater than the
         *         second.
         */
        override fun compare(o1: RequestAppInfo?, o2: RequestAppInfo?): Int {
            if (o1 == null) {
                return if (o2 != null) {
                    -1
                } else {
                    0
                }
            } else {
                if (o2 == null) {
                    return 1
                }
                val tag1 = o1.tag.toCharArray()
                val tag2 = o2.tag.toCharArray()
                val max = max(tag1.size, tag2.size)
                for (index in 0 until max) {
                    val char1 = charAt(tag1, index)
                    val char2 = charAt(tag2, index)
                    if (char1 > char2) {
                        return 1
                    }
                    if (char1 < char2) {
                        return -1
                    }
                }
            }
            return 0
        }

        private fun charAt(array: CharArray, index: Int): Char {
            if (index < 0 || index >= array.size) {
                return 0.toChar()
            }
            return array[index]
        }

    }

    private class RequestAppInfo(val tag: String, val app: IconHelper.AppInfo) {
        companion object {

            fun create(app: IconHelper.AppInfo): RequestAppInfo {
                return RequestAppInfo(findTag(app), app)
            }

            private fun findTag(app: IconHelper.AppInfo): String {
                if (app.name.isEmpty()) {
                    return ""
                }
                return app.name.substring(0, 1).toUpperCase(Locale.CHINA)
            }

        }
    }

    private class FloatingTagHelper(
        private val floatingPanel: View,
        private val floatingText: TextView,
        private val isShowTag: (Int) -> Boolean,
        private val getTag: (Int) -> String): RecyclerView.OnScrollListener() {

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            val layoutManager = recyclerView.layoutManager
            if (layoutManager is LinearLayoutManager) {
                onScrolled(layoutManager)
            }
        }

        private fun onScrolled(layoutManager: LinearLayoutManager) {
            val firstPosition = layoutManager.findFirstVisibleItemPosition()
            val firstCompletelyPosition =
                layoutManager.findFirstCompletelyVisibleItemPosition()

            if (firstPosition == firstCompletelyPosition) {
                if (isShowTag(firstPosition)) {
                    floatingPanel.visibility = View.INVISIBLE
                } else {
                    floatingText.text = getTag(firstPosition)
                    floatingPanel.visibility = View.VISIBLE
                }
            }

        }

    }

    private class AppAdapter(
        private val appList: ArrayList<RequestAppInfo>): RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_APP = 0
            private const val TYPE_EMPTY = 1

            private const val HEADER_EMPTY = 1
            private const val FOOTER_EMPTY = 1
        }

        private val selectedApp = ArrayList<RequestAppInfo>()

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_APP) {
                return AppHolder.create(parent, ::onAppClick, ::isShowTag, ::isChecked)
            }
            return EmptyHolder.create(parent)
        }

        private fun onAppClick(position: Int): Boolean {
            val real = position - HEADER_EMPTY
            val app = appList[real]
            if (selectedApp.remove(app)) {
                return false
            }
            selectedApp.add(app)
            return true
        }

        private fun isShowTag(position: Int): Boolean {
            val real = position - HEADER_EMPTY
            if (real == 0) {
                return true
            }
            if (appList[real].tag != appList[real - 1].tag) {
                return true
            }
            return false
        }

        private fun isChecked(position: Int): Boolean {
            val app = appList[position - HEADER_EMPTY]
            return selectedApp.contains(app)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AppHolder) {
                holder.bind(appList[position - HEADER_EMPTY])
            }
        }

        override fun getItemViewType(position: Int): Int {
            val real = position - HEADER_EMPTY
            if (real < 0 || real >= appList.size) {
                return TYPE_EMPTY
            }
            return TYPE_APP
        }

        override fun getItemCount(): Int {
            return appList.size + HEADER_EMPTY + FOOTER_EMPTY
        }

    }

    private class EmptyHolder(view: View): RecyclerView.ViewHolder(view) {
        companion object {
            fun create(group: ViewGroup): EmptyHolder {
                return EmptyHolder(
                    LayoutInflater.from(group.context)
                        .inflate(R.layout.kit_item_request, group, false))
            }
        }

        init {
            itemView.visibility = View.INVISIBLE
        }

    }

    private class AppHolder private constructor(
        view: View,
        private val onCLick: (Int) -> Boolean,
        private val isShowTag: (Int) -> Boolean,
        private val isChecked: (Int) -> Boolean): RecyclerView.ViewHolder(view) {

        companion object {
            fun create(group: ViewGroup,
                       onClick: (Int) -> Boolean,
                       isShowTag: (Int) -> Boolean,
                       isChecked: (Int) -> Boolean): AppHolder {
                return AppHolder(
                    LayoutInflater.from(group.context)
                        .inflate(R.layout.kit_item_request, group, false),
                    onClick, isShowTag, isChecked)
            }
        }

        private val tagView: TextView = itemView.findViewById(R.id.tagTextView)
        private val iconView: IconImageView = itemView.findViewById(R.id.iconView)
        private val labelView: TextView = itemView.findViewById(R.id.labelView)
        private val pkgView: TextView = itemView.findViewById(R.id.pkgView)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.checkBox)

        init {
            itemView.setOnClickListener {
                checkBox.isChecked = onCLick(adapterPosition)
            }
        }

        fun bind(info: RequestAppInfo) {
            tagView.text = if (isShowTag(adapterPosition)) { info.tag } else { "" }
            iconView.setImageDrawable(info.app.srcIcon)
            labelView.text = info.app.name
            pkgView.text = info.app.pkg.packageName
        }

    }

}