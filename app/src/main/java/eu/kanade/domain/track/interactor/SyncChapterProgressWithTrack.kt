package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.custom.CustomTracker
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import kotlin.math.abs
import kotlin.math.max

class SyncChapterProgressWithTrack(
    private val updateChapter: UpdateChapter,
    private val insertTrack: InsertTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
) {

    suspend fun await(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
    ) {
        if (tracker is CustomTracker) {
            applyShioriSyncProgress(mangaId, remoteTrack)
            return
        }

        if (tracker !is EnhancedTracker) {
            return
        }

        val sortedChapters = getChaptersByMangaId.await(mangaId)
            .sortedBy { it.chapterNumber }
            .filter { it.isRecognizedNumber }

        val chapterUpdates = sortedChapters
            .filter { chapter -> chapter.chapterNumber <= remoteTrack.lastChapterRead && !chapter.read }
            .map { it.copy(read = true).toChapterUpdate() }

        // only take into account continuous reading
        val localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()?.chapterNumber ?: 0F
        val lastRead = max(remoteTrack.lastChapterRead, localLastRead.toDouble())
        val updatedTrack = remoteTrack.copy(lastChapterRead = lastRead)

        try {
            tracker.update(updatedTrack.toDbTrack())
            updateChapter.awaitAll(chapterUpdates)
            insertTrack.await(updatedTrack)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
    }

    /**
     * Pull-only application of the server's reading position for [CustomTracker]:
     * mark chapters up to the server's position as read and, for novels, restore
     * the scroll position within the chapter the server points at. Nothing is
     * pushed back here so a stale local state can never regress the server.
     */
    private suspend fun applyShioriSyncProgress(mangaId: Long, remoteTrack: Track) {
        val sortedChapters = getChaptersByMangaId.await(mangaId)
            .sortedBy { it.chapterNumber }
            .filter { it.isRecognizedNumber }

        val chapterUpdates = sortedChapters
            .filter { chapter -> chapter.chapterNumber <= remoteTrack.lastChapterRead && !chapter.read }
            .map { it.copy(read = true).toChapterUpdate() }

        // Novel readers persist their position as a 0-100% scroll value in the
        // chapter's last_page_read, so the server's percent maps onto it.
        val resumeUpdates = if (getManga.await(mangaId)?.isNovel == true) {
            sortedChapters
                .find { chapter ->
                    abs(chapter.chapterNumber - remoteTrack.lastChapterRead) <= CHAPTER_NUMBER_EPSILON
                }
                ?.let { chapter ->
                    val percent = remoteTrack.progressPercent.toInt()
                    if (percent > chapter.lastPageRead) {
                        listOf(chapter.copy(lastPageRead = percent.toLong()).toChapterUpdate())
                    } else {
                        emptyList()
                    }
                }
                ?: emptyList()
        } else {
            emptyList()
        }

        try {
            updateChapter.awaitAll(chapterUpdates + resumeUpdates)
            insertTrack.await(remoteTrack)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Failed to apply Shiori Sync reading position" }
        }
    }

    private companion object {
        const val CHAPTER_NUMBER_EPSILON = 0.0001f
    }
}
