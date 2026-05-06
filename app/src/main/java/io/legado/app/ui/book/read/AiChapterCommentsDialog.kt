package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterComment
import io.legado.app.help.ai.AiChapterCommenter
import io.legado.app.help.book.BookHelp
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemAiCommentBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiChapterCommentsDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { CommentAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.95f, 0.85f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.ai_comment)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
            tvOk.isGone = true
            tvCancel.isVisible = true
            tvCancel.text = getString(R.string.close)
            tvCancel.setOnClickListener { dismissAllowingStateLoss() }
            tvFooterLeft.isVisible = true
            tvFooterLeft.text = getString(R.string.ai_comment_generate)
            tvFooterLeft.setOnClickListener { generate(regenerate = false) }
        }

        val storedComment = loadStoredComment()
        if (storedComment != null) {
            adapter.setItems(storedComment.comments)
            binding.tvFooterLeft.text = getString(R.string.ai_comment_regenerate)
            binding.tvFooterLeft.setOnClickListener { generate(regenerate = true) }
            binding.tvMsg.isGone = true
        } else {
            adapter.setItems(emptyList())
            binding.tvMsg.isVisible = true
            binding.tvMsg.text = getString(R.string.ai_comment_generate).let { "点击“$it”生成本章 AI 评论。" }
        }
    }

    private fun generate(regenerate: Boolean) {
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
        if (chapter == null) {
            toastOnUi(R.string.no_chapter)
            return
        }

        val config = try {
            AiChapterCommenter.loadConfigOrThrow()
        } catch (e: AiChapterCommenter.NotEnabledException) {
            toastOnUi(R.string.ai_comment_not_enabled)
            return
        } catch (e: AiChapterCommenter.NotConfiguredException) {
            toastOnUi(R.string.ai_comment_not_configured)
            return
        } catch (e: Exception) {
            toastOnUi(e.localizedMessage ?: getString(R.string.ai_comment_generate_failed))
            return
        }

        val content = BookHelp.getContent(book, chapter) ?: ReadBook.curTextChapter?.getContent()
        if (content.isNullOrBlank()) {
            toastOnUi("无内容")
            return
        }

        val commentCount = AiChapterCommenter.getCommentCount()
        val contentHash = contentHash(content)
        if (!regenerate) {
            val cached = loadStoredComment(book.bookUrl, chapter.index, contentHash, commentCount)
            if (cached != null && cached.comments.isNotEmpty()) {
                adapter.setItems(cached.comments)
                binding.tvMsg.isGone = true
                binding.tvFooterLeft.text = getString(R.string.ai_comment_regenerate)
                binding.tvFooterLeft.setOnClickListener { generate(regenerate = true) }
                return
            }
        }

        val idleFooterText = binding.tvFooterLeft.text

        execute {
            AiChapterCommenter.generateComments(
                config = config,
                bookName = book.name,
                chapterTitle = chapter.title,
                chapterIndex = chapter.index,
                chapterText = content
            )
        }.onStart {
            binding.rotateLoading.visible()
            binding.tvFooterLeft.isEnabled = false
            binding.tvFooterLeft.text = getString(R.string.ai_comment_generating)
            binding.tvMsg.isGone = true
        }.onSuccess { result ->
            adapter.setItems(result.comments)
            binding.tvFooterLeft.text = getString(R.string.ai_comment_regenerate)
            binding.tvFooterLeft.setOnClickListener { generate(regenerate = true) }
            appDb.aiChapterCommentDao.insert(
                AiChapterComment(
                    bookUrl = book.bookUrl,
                    chapterIndex = chapter.index,
                    contentHash = contentHash,
                    commentCount = commentCount,
                    commentsJson = GSON.toJson(result)
                )
            )
            postEvent(EventBus.AI_CHAPTER_COMMENT_UPDATED, chapter.index)
        }.onError { e ->
            binding.tvMsg.isVisible = true
            binding.tvMsg.text = e.localizedMessage ?: getString(R.string.ai_comment_generate_failed)
            binding.tvFooterLeft.text = idleFooterText
        }.onFinally {
            binding.rotateLoading.gone()
            binding.tvFooterLeft.isEnabled = true
        }
    }

    private fun loadStoredComment(): AiChapterCommenter.AiCommentResult? {
        val book = ReadBook.book ?: return null
        val chapterIndex = ReadBook.durChapterIndex
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        val content = BookHelp.getContent(book, chapter) ?: return null
        return loadStoredComment(
            book.bookUrl,
            chapter.index,
            contentHash(content),
            AiChapterCommenter.getCommentCount()
        )
    }

    private fun loadStoredComment(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        commentCount: Int
    ): AiChapterCommenter.AiCommentResult? {
        val stored = appDb.aiChapterCommentDao.get(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            commentCount = commentCount
        ) ?: return null
        return kotlin.runCatching {
            GSON.fromJson(stored.commentsJson, AiChapterCommenter.AiCommentResult::class.java)
        }.getOrNull()
    }

    private fun contentHash(content: String): String = MD5Utils.md5Encode16(content)

    private inner class CommentAdapter(context: android.content.Context) :
        RecyclerAdapter<AiChapterCommenter.AiComment, ItemAiCommentBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAiCommentBinding {
            return ItemAiCommentBinding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiCommentBinding) {
            // no-op
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiCommentBinding,
            item: AiChapterCommenter.AiComment,
            payloads: MutableList<Any>
        ) {
            val tone = item.tone?.takeIf { it.isNotBlank() }
            val replyName = item.replyTo?.let { replyIndex ->
                getItem(replyIndex)?.name
            }?.takeIf { it.isNotBlank() }
            binding.tvMeta.text = buildString {
                append(item.name)
                if (tone != null) append(" · ").append(tone)
                if (replyName != null) append(" · 回复 @").append(replyName)
            }
            binding.tvContent.text = item.content
        }
    }
}
