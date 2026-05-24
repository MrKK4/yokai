package eu.kanade.tachiyomi.ui.recents

import android.view.View
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.RecentsHistoryBucketHeaderItemBinding
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.util.view.setText
import yokai.i18n.MR

class HistoryBucketHeaderHolder(
    view: View,
    private val adapter: RecentMangaAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = RecentsHistoryBucketHeaderItemBinding.bind(view)

    init {
        binding.root.setOnClickListener {
            adapter.delegate.onHistoryBucketClicked(flexibleAdapterPosition)
        }
    }

    fun bind(item: RecentMangaItem) {
        val bucket = item.historyBucket ?: return
        binding.title.setText(bucket.stringRes)
        binding.collapseArrow.setImageResource(
            if (item.historyBucketCollapsed) {
                R.drawable.ic_expand_more_24dp
            } else {
                R.drawable.ic_expand_less_24dp
            },
        )
    }
}

private val HistoryBucket.stringRes
    get() = when (this) {
        HistoryBucket.Today -> MR.strings.history_today
        HistoryBucket.Yesterday -> MR.strings.history_yesterday
        HistoryBucket.UpTo7Days -> MR.strings.history_up_to_7_days
        HistoryBucket.UpTo14Days -> MR.strings.history_up_to_14_days
        HistoryBucket.UpTo30Days -> MR.strings.history_up_to_30_days
        HistoryBucket.UpTo60Days -> MR.strings.history_up_to_60_days
        HistoryBucket.UpTo90Days -> MR.strings.history_up_to_90_days
        HistoryBucket.UpTo120Days -> MR.strings.history_up_to_120_days
        HistoryBucket.Over120Days -> MR.strings.history_over_120_days
    }
