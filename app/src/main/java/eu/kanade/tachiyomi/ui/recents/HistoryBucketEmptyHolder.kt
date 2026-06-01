package eu.kanade.tachiyomi.ui.recents

import android.view.View
import eu.kanade.tachiyomi.databinding.RecentsHistoryBucketEmptyItemBinding
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.util.view.setText
import yokai.i18n.MR

class HistoryBucketEmptyHolder(
    view: View,
    adapter: RecentMangaAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = RecentsHistoryBucketEmptyItemBinding.bind(view)

    fun bind() {
        binding.emptyMessage.setText(MR.strings.history_bucket_empty)
    }
}
