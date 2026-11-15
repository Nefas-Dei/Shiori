package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.custom.CustomTracker
import eu.kanade.tachiyomi.data.track.source.SourceTrackerDispatcher
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.translation.model.TranslationLocator
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val translatedChapterRepository: TranslatedChapterRepository,
    private val sourceManager: SourceManager,
) {

    private val sourceTrackerDispatcher: SourceTrackerDispatcher by lazy { Injekt.get() }

    private val mapper = { chapter: Chapter, read: Boolean ->
        ChapterUpdate(
            read = read,
            lastPageRead = if (!read) 0 else null,
            id = chapter.id,
        )
    }

    /**
     * Marks chapters read/unread. For novels the reached scroll position is
     * preserved when unmarking (the user explicitly asked to keep it), and the
     * Shiori Sync server is told about the correction so the next reader open
     * does not re-mark the chapter as read.
     */
    suspend fun await(read: Boolean, vararg chapters: Chapter): Result = withNonCancellableContext {
        val chaptersToUpdate = chapters.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastPageRead > 0
            }
        }
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            if (read) {
                chapterRepository.updateAll(chaptersToUpdate.map { mapper(it, read) })
            } else {
                // Grouped so novel chapters can keep their reached position
                // instead of being reset to 0.
                chaptersToUpdate
                    .groupBy { it.mangaId }
                    .flatMap { (mangaId, group) ->
                        val keepProgress = mangaRepository.getMangaByIdOrNull(mangaId)?.isNovel == true
                        group.map { chapter ->
                            ChapterUpdate(
                                read = false,
                                lastPageRead = if (keepProgress) null else 0L,
                                id = chapter.id,
                            )
                        }
                    }
                    .let { chapterRepository.updateAll(it) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        if (read) {
            chaptersToUpdate.groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
                val manga = mangaRepository.getMangaByIdOrNull(mangaId)
                if (manga == null) {
                    logcat(LogPriority.WARN) { "Skipping translation cleanup: manga $mangaId not found" }
                } else {
                    val sourceName = sourceManager.getOrStub(manga.source).toString()
                    chapters.forEach { chapter ->
                        try {
                            translatedChapterRepository.deleteAllForChapter(
                                TranslationLocator(sourceName, manga.title, chapter.name, chapter.url),
                            )
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Failed to delete translations for chapter ${chapter.id}" }
                        }
                    }
                }
            }
        }

        if (read && downloadPreferences.removeAfterMarkedAsRead.get()) {
            chaptersToUpdate
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    deleteDownload.awaitAll(
                        manga = mangaRepository.getMangaById(mangaId),
                        chapters = chapters.toTypedArray(),
                    )
                }
        }

        try {
            chaptersToUpdate.groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
                val manga = mangaRepository.getMangaById(mangaId)
                if (read) {
                    sourceTrackerDispatcher.notifyChaptersRead(manga, chapters)
                } else {
                    sourceTrackerDispatcher.notifyChaptersUnread(manga, chapters)
                    if (manga.isNovel) {
                        notifyShioriUnread(manga, chapters)
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "SourceTrackerDispatcher fan-out failed" }
        }

        Result.Success
    }

    /**
     * Tells the Shiori Sync server that the user explicitly unmarked chapters:
     * the server position moves back to the furthest unmarked chapter with its
     * preserved scroll percent, so reopening it later does not re-mark it read
     * with a stale completed position. Unmarking a chapter below the server's
     * furthest position is ignored - it would regress the position every other
     * device resumes from.
     */
    private suspend fun notifyShioriUnread(manga: Manga, chapters: List<Chapter>) {
        val trackerManager: TrackerManager = Injekt.get()
        val tracker = trackerManager.custom
        if (!tracker.isLoggedIn) return

        val furthest = chapters.maxByOrNull { it.chapterNumber } ?: return
        for (track in Injekt.get<GetTracks>().await(manga.id)) {
            if (
                track.trackerId != TrackerManager.CUSTOM ||
                track.progressSite.isBlank() ||
                track.progressSeriesKey.isBlank()
            ) {
                continue
            }
            if (furthest.chapterNumber.toDouble() < track.lastChapterRead - SHIORI_CHAPTER_EPSILON) continue

            try {
                val dbTrack = track.toDbTrack().apply {
                    last_chapter_read = furthest.chapterNumber.toDouble()
                    // An unmarked chapter is by definition not finished; keep
                    // the percent below the completion threshold so the next
                    // pull cannot mark it read again.
                    progress_percent = furthest.lastPageRead.toDouble().coerceIn(0.0, 99.0)
                    status = CustomTracker.READING
                    progress_updated_at = System.currentTimeMillis()
                }
                tracker.update(dbTrack, didReadChapter = false)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Shiori Sync unread correction failed" }
            }
        }
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = chapterRepository
                .getChapterByMangaId(mangaId)
                .toTypedArray(),
        )
    }

    suspend fun await(manga: Manga, read: Boolean) =
        await(manga.id, read)

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }

    private companion object {
        const val SHIORI_CHAPTER_EPSILON = 0.0001
    }
}
