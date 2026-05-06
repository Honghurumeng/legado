package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiChapterComment

@Dao
interface AiChapterCommentDao {

    @get:Query("select * from aiChapterComments")
    val all: List<AiChapterComment>

    @Query(
        """
        select * from aiChapterComments
        where bookUrl = :bookUrl
        and chapterIndex = :chapterIndex
        and contentHash = :contentHash
        and commentCount = :commentCount
        limit 1
        """
    )
    fun get(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        commentCount: Int
    ): AiChapterComment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg comments: AiChapterComment)

    @Query("delete from aiChapterComments where bookUrl = :bookUrl")
    fun deleteByBookUrl(bookUrl: String)
}
