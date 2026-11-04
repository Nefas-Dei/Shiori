package tachiyomi.data.track

import tachiyomi.domain.track.model.Track

object TrackMapper {
    fun mapTrack(
        id: Long,
        mangaId: Long,
        syncId: Long,
        remoteId: Long,
        libraryId: Long?,
        title: String,
        lastChapterRead: Double,
        totalChapters: Long,
        status: Long,
        score: Double,
        remoteUrl: String,
        startDate: Long,
        finishDate: Long,
        private: Boolean,
        progressSite: String,
        progressSeriesKey: String,
        progressPercent: Double,
        progressParagraph: Long,
        progressUpdatedAt: Long,
    ): Track = Track(
        id = id,
        mangaId = mangaId,
        trackerId = syncId,
        remoteId = remoteId,
        libraryId = libraryId,
        title = title,
        lastChapterRead = lastChapterRead,
        totalChapters = totalChapters,
        status = status,
        score = score,
        remoteUrl = remoteUrl,
        startDate = startDate,
        finishDate = finishDate,
        private = private,
        progressSite = progressSite,
        progressSeriesKey = progressSeriesKey,
        progressPercent = progressPercent,
        progressParagraph = progressParagraph,
        progressUpdatedAt = progressUpdatedAt,
    )
}
