package eu.kanade.tachiyomi.ui.recents

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterItem

class RecentMangaItem(
    val mch: MangaChapterHistory = MangaChapterHistory.createBlank(),
    chapter: Chapter = ChapterImpl(),
    header: AbstractHeaderItem<*>?,
    val historyBucket: HistoryBucket? = null,
    val historyBucketCollapsed: Boolean = false,
) :
    BaseChapterItem<BaseChapterHolder, AbstractHeaderItem<*>>(chapter, header) {

    var downloadInfo = listOf<DownloadInfo>()

    override fun getLayoutRes(): Int {
        return when {
            historyBucket != null -> R.layout.recents_history_bucket_header_item
            mch.manga.id == null -> R.layout.recents_footer_item
            else -> R.layout.manga_grid_item
        }
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): BaseChapterHolder {
        return if (historyBucket != null) {
            HistoryBucketHeaderHolder(view, adapter as RecentMangaAdapter)
        } else if (mch.manga.id == null) {
            RecentMangaFooterHolder(view, adapter as RecentMangaAdapter)
        } else {
            RecentMangaHolder(view, adapter as RecentMangaAdapter)
        }
    }

    override fun isSwipeable(): Boolean {
        return mch.manga.id != null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is RecentMangaItem) {
            return if (historyBucket != null || other.historyBucket != null) {
                historyBucket == other.historyBucket
            } else if (mch.manga.id == null) {
                (header as? RecentMangaHeaderItem)?.recentsType ==
                    (other.header as? RecentMangaHeaderItem)?.recentsType
            } else {
                chapter.id == other.chapter.id
            }
        }
        return false
    }

    override fun hashCode(): Int {
        return if (historyBucket != null) {
            historyBucket.hashCode()
        } else if (mch.manga.id == null) {
            -((header as? RecentMangaHeaderItem)?.recentsType ?: 0).hashCode()
        } else {
            (chapter.id ?: 0L).hashCode()
        }
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: BaseChapterHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        if (historyBucket != null) {
            (holder as? HistoryBucketHeaderHolder)?.bind(this)
        } else if (mch.manga.id == null) {
            (holder as? RecentMangaFooterHolder)?.bind((header as? RecentMangaHeaderItem)?.recentsType ?: 0)
        } else if (chapter.id != null) (holder as? RecentMangaHolder)?.bind(this)
    }

    companion object {
        fun historyBucketHeader(bucket: HistoryBucket, collapsed: Boolean): RecentMangaItem =
            RecentMangaItem(
                header = null,
                historyBucket = bucket,
                historyBucketCollapsed = collapsed,
            )
    }

    class DownloadInfo {
        private var _status: Download.State = Download.State.default

        var chapterId: Long? = 0L

        val progress: Int
            get() {
                val pages = download?.pages ?: return 0
                return pages.map(Page::progress).average().toInt()
            }

        var status: Download.State
            get() = download?.status ?: _status
            set(value) { _status = value }

        @Transient var download: Download? = null

        val isDownloaded: Boolean
            get() = status == Download.State.DOWNLOADED
    }
}
