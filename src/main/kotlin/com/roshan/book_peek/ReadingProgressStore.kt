package com.roshan.book_peek

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.ThreeState
import java.io.File

/**
 * 阅读进度的本地存储。
 *
 * 按 IDE 实例隔离：文件路径落在 IDE 配置目录下，同一机器上的多个 IDE 实例（不同配置/插件安装目录）
 * 各写各的，并通过文件锁串行化读写，避免多进程同时写同一份进度时互相覆盖或读到半截内容。
 * 保存量很小，因此直接即时写盘，不再受默认 5 秒保存阈值的延迟影响。
 */
@State(
    name = "BookPeekReadingProgress",
    storages = [Storage(value = "BookPeekReadingProgress.xml", roamingType = RoamingType.DISABLED, useSaveThreshold = ThreeState.NO)]
)
class ReadingProgressStore : PersistentStateComponent<ReadingProgressStore.State> {

    private var myState = State()

    data class State(
        /** 书籍文件路径 */
        var bookPath: String = "",
        /** 章节索引 */
        var chapterIndex: Int = 0,
        /** 章节内滚动位置（像素），null 表示未记录过滚动位置 */
        var scrollValue: Int? = null
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * 读取指定书籍的进度，书籍不匹配时返回 null
     */
    fun progressOf(bookPath: String): Progress? {
        val current = myState
        return if (current.bookPath.isNotEmpty() && current.bookPath == bookPath) {
            Progress(current.chapterIndex, current.scrollValue)
        } else {
            null
        }
    }

    /**
     * 写入指定书籍的进度；内容未变化时不触发落盘，避免滚动时频繁写文件
     */
    fun saveProgress(bookPath: String, progress: Progress) {
        val current = myState
        if (current.bookPath == bookPath &&
            current.chapterIndex == progress.chapterIndex &&
            current.scrollValue == progress.scrollValue
        ) {
            return
        }
        myState = State(bookPath, progress.chapterIndex, progress.scrollValue)
        ApplicationManager.getApplication().saveSettings()
    }

    /** 书籍被移除时清空进度描点 */
    fun clearProgress() {
        myState = State()
        ApplicationManager.getApplication().saveSettings()
    }

    /** scrollValue 为 null 表示没有滚动位置的记录（例如旧版本只保存了章节） */
    data class Progress(val chapterIndex: Int, val scrollValue: Int?)

    companion object {
        @JvmStatic
        fun getInstance(): ReadingProgressStore {
            return ApplicationManager.getApplication().getService(ReadingProgressStore::class.java)
        }

        /** 书籍的稳定标识：绝对路径，用于比对不同实例间的进度是否属于同一本书 */
        fun pathIdOf(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }
}
