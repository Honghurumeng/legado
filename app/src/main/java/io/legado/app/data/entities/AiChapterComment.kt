package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "aiChapterComments",
    primaryKeys = ["bookUrl", "chapterIndex", "contentHash", "commentCount"],
    indices = [
        Index(value = ["bookUrl", "chapterIndex"]),
        Index(value = ["updatedAt"])
    ]
)
data class AiChapterComment(
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val contentHash: String = "",
    val commentCount: Int = 0,
    val commentsJson: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
