package eu.kanade.tachiyomi.ui.recents

import android.app.Activity
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import coil3.dispose
import coil3.size.Scale
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.dominantCoverColors
import eu.kanade.tachiyomi.databinding.MangaGridItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.util.lang.highlightText
import eu.kanade.tachiyomi.util.manga.MangaCoverMetadata
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.backgroundColor
import eu.kanade.tachiyomi.util.view.setCards
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import yokai.util.coil.loadManga

class RecentMangaHolder(
    view: View,
    val adapter: RecentMangaAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = MangaGridItemBinding.bind(view)

    init {
        binding.card.setOnClickListener { adapter.delegate.onCoverClick(flexibleAdapterPosition) }
        binding.constraintLayout.setOnClickListener { adapter.delegate.onCoverClick(flexibleAdapterPosition) }
        
        binding.compactTitle.isVisible = false
        binding.gradient.isVisible = false
        listOf(binding.playLayout, binding.playButton).forEach {
            it.updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        }
    }

    fun bind(item: RecentMangaItem) {
        setCards(adapter.showOutline, binding.card, binding.unreadDownloadBadge.root)
        
        binding.constraintLayout.isVisible = item.mch.manga.id != Long.MIN_VALUE
        binding.title.text = item.mch.manga.title
        binding.behindTitle.text = item.mch.manga.title
        
        val mangaColor = item.mch.manga.dominantCoverColors
        binding.coverConstraint.backgroundColor = mangaColor?.first ?: itemView.context.getResourceColor(R.attr.background)
        binding.behindTitle.setTextColor(
            mangaColor?.second ?: itemView.context.getResourceColor(R.attr.colorOnBackground),
        )

        binding.subtitle.text = ""
        binding.subtitle.isVisible = false

        binding.coverThumbnail.dispose()
        setCover(item.mch.manga)
    }

    fun updateCards() {
        setCards(adapter.showOutline, binding.card, null)
    }

    private fun setCover(manga: Manga) {
        if ((adapter.recyclerView.context as? Activity)?.isDestroyed == true) return
        binding.coverThumbnail.loadManga(manga) {
            val hasRatio = binding.coverThumbnail.layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT
            if (!hasRatio) {
                scale(Scale.FIT)
            }
            listener(
                onSuccess = { _, _ ->
                    if (!hasRatio && MangaCoverMetadata.getRatio(manga) != null) {
                        setFreeformCoverRatio(manga)
                    }
                },
            )
        }
    }

    fun setFreeformCoverRatio(manga: Manga, parent: AutofitRecyclerView? = null) {
        val ratio = MangaCoverMetadata.getRatio(manga)
        val itemWidth = parent?.itemWidth ?: binding.root.width
        if (ratio != null) {
            binding.coverThumbnail.adjustViewBounds = false
            binding.coverThumbnail.maxHeight = (itemWidth / 3f * 10f).toInt()
            binding.coverThumbnail.minimumHeight = 56.dpToPx
            binding.constraintLayout.minHeight = 56.dpToPx
        } else {
            val coverHeight = (itemWidth / 3f * 4f).toInt()
            binding.constraintLayout.minHeight = coverHeight / 2
            binding.coverThumbnail.minimumHeight =
                (itemWidth / 3f * 3.6f).toInt()
            binding.coverThumbnail.maxHeight = (itemWidth / 3f * 6f).toInt()
            binding.coverThumbnail.adjustViewBounds = true
        }
        binding.coverThumbnail.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (ratio != null) {
                height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                matchConstraintMaxHeight = binding.coverThumbnail.maxHeight
                matchConstraintMinHeight = binding.coverThumbnail.minimumHeight
                dimensionRatio = "W,1:$ratio"
            } else {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                dimensionRatio = null
            }
        }
    }
}
