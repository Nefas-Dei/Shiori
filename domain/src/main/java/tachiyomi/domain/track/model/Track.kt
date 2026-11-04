package tachiyomi.domain.track.model

import java.io.Serializable

data class Track(
    val id: Long,
    val mangaId: Long,
    val trackerId: Long,
    val remoteId: Long,
    val libraryId: Long?,
    val title: String,
    val lastChapterRead: Double,
    val totalChapters: Long,
    val status: Long,
    val score: Double,
    val remoteUrl: String,
    val startDate: Long,
    val finishDate: Long,
    val private: Boolean,
    val progressSite: String = "",
    val progressSeriesKey: String = "",
    val progressPercent: Double = 0.0,
    val progressParagraph: Long = -1,
    val progressUpdatedAt: Long = 0,
) : Serializable
