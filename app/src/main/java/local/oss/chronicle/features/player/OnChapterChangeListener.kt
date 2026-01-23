package local.oss.chronicle.features.player

import local.oss.chronicle.data.model.Chapter

/**
 * Callback interface for chapter change events.
 */
fun interface OnChapterChangeListener {
    /**
     * Called when the current chapter changes.
     *
     * @param previousChapter The chapter that was playing before, or null if none
     * @param newChapter The chapter that is now playing, or null if none
     * @param chapterIndex The index of the new chapter (0-based), or -1 if none
     */
    fun onChapterChanged(previousChapter: Chapter?, newChapter: Chapter?, chapterIndex: Int)
}
