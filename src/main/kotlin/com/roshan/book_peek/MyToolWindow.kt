package com.roshan.book_peek

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.geom.Point2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.plaf.basic.BasicTextPaneUI
import javax.swing.text.JTextComponent
import javax.swing.text.Position
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * 小说阅读器工具窗口工厂
 */
class BookPeekToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 设置窗口失焦时自动隐藏
        toolWindow.setAutoHide(true)
        
        val readerPanel = BookPeekPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(readerPanel, "阅读器", false)
        readerPanel.onTitleChanged = { title -> content.displayName = title }
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 章节数据类
 */
data class Chapter(val title: String, val content: String)

/**
 * 阅读器主面板 (Swing UI)
 */
class BookPeekPanel(
    private val project: Project?,
    private val toolWindow: ToolWindow? = null
) : JPanel(BorderLayout()), Disposable {
    private var currentChapterIndex = 0
    private var chapters: MutableList<Chapter> = mutableListOf()
    private var currentBookPath: String = ""
    /** 书籍的稳定标识，用于比对进度存储中的记录 */
    private var currentBookId: String = ""
    private var lastLoadedBookPath: String = ""
    /** 阅读内容所在的滚动面板，在 init 中配置并注册滚动监听 */
    private val contentScrollPane: JBScrollPane
    
    private val titleLabel: JBLabel
    private val contentArea: JTextPane

    /** 恢复进度期间忽略滚动事件，避免把恢复结果反向覆盖成旧值 */
    private var restoringScroll = false

    /** 滚动位置落盘的防抖定时器：滚动期间只更新内存，停下后统一写盘一次 */
    private var scrollFlushTimer: Timer? = null

    /**
     * 窗口标识：多个 IDEA 窗口在同一进程内运行，每个窗口对应独立 Project。
     * 用项目名 + basePath 区分，basePath 不同则同名项目也能分开；同名同路径的多实例再靠 locationHash 区分。
     */
    private val windowId: String = buildWindowId()

    private val pageLabel: JBLabel
    private val prevButton: JButton
    private val nextButton: JButton
    private val chapterListButton: JButton
    private var currentBookName: String = "未加载书籍"
    
    // 控制面板（顶部面板和底部导航）
    private val topPanel: JPanel
    private val topToolBar: JPanel
    private val bottomPanel: JPanel
    
    // CardLayout 用于切换阅读视图和章节目录视图
    private val cardLayout = CardLayout()
    private val contentPanel: JPanel
    private val chapterListPanel: JPanel
    private val chapterJList: JBList<String>
    private val chapterListModel = DefaultListModel<String>()

    /**
     * 章节标题变化时同步到工具窗口 tab：有标题就显示章节标题，没有就显示"阅读器"
     */
    var onTitleChanged: ((String) -> Unit)? = null
        set(value) {
            field = value
            updateTabTitle()
        }
    
    companion object {
        private const val CARD_READER = "reader"
        private const val CARD_CHAPTERS = "chapters"
        private val LOG = logger<BookPeekPanel>()
    }

    init {
        val settings = ReaderSettings.getInstance().state
        
        // 订阅配置变化通知
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(ReaderNotifier.TOPIC, object : ReaderNotifier {
                override fun settingsChanged() {
                    onSettingsChanged()
                }
            })
        
        // 顶部面板（包含标题和工具栏）
        topPanel = JPanel(BorderLayout())
        
        // 标题
        titleLabel = JBLabel(currentBookName, SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 16f)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        
        // 工具按钮
        topToolBar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5))

        chapterListButton = JButton("章节目录").apply {
            addActionListener { toggleChapterList() }
        }
        topToolBar.add(chapterListButton)
        
        topPanel.add(topToolBar, BorderLayout.WEST)
        topPanel.add(titleLabel, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)

        // 使用 CardLayout 的内容面板
        contentPanel = JPanel(cardLayout)
        
        // 阅读视图
        val readerPanel = JPanel(BorderLayout())
        contentArea = object : JTextPane() {
            init {
                // DefaultCaret 拖拽时直接调 UI 的 viewToModel，覆盖组件自身的方法拦不到，
                // 所以换成带行尾修正的 UI
                val fg = foreground
                val bg = background
                setUI(BookPeekTextPaneUI())
                foreground = fg
                background = bg
            }

            override fun getScrollableTracksViewportWidth() = true
        }.apply {
            text = "请在设置中选择书籍文件开始阅读...\n\nSettings → Tools → BookPeek"
            isEditable = false
            caret = object : javax.swing.text.DefaultCaret() {
                override fun isVisible() = false
                override fun setVisible(v: Boolean) {}
            }
            font = Font("Microsoft YaHei", Font.PLAIN, settings.fontSize)
            border = BorderFactory.createEmptyBorder(10, 15, 10, 15)
        }
        applyLineSpacing()
        contentScrollPane = JBScrollPane(contentArea).apply {
            border = BorderFactory.createEmptyBorder()
        }
        readerPanel.add(contentScrollPane, BorderLayout.CENTER)
        
        // 章节目录视图
        chapterListPanel = JPanel(BorderLayout())
        chapterJList = JBList(chapterListModel).apply {
            font = Font("Microsoft YaHei", Font.PLAIN, 14)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            // 双击选择章节
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        selectChapter(selectedIndex)
                    }
                }
            })
            // 回车选择章节
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                        selectChapter(selectedIndex)
                    }
                }
            })
        }
        val chapterScrollPane = JBScrollPane(chapterJList).apply {
            border = BorderFactory.createEmptyBorder()
        }
        chapterListPanel.add(chapterScrollPane, BorderLayout.CENTER)
        
        // 章节目录底部提示
        val chapterTip = JBLabel("双击或按回车选择章节", SwingConstants.CENTER).apply {
            border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
        }
        chapterListPanel.add(chapterTip, BorderLayout.SOUTH)
        
        contentPanel.add(readerPanel, CARD_READER)
        contentPanel.add(chapterListPanel, CARD_CHAPTERS)
        add(contentPanel, BorderLayout.CENTER)

        // 底部导航
        bottomPanel = JPanel(FlowLayout(FlowLayout.CENTER, 20, 10))
        
        prevButton = JButton("上一章").apply {
            isEnabled = false
            addActionListener { goToPreviousChapter() }
        }
        bottomPanel.add(prevButton)

        pageLabel = JBLabel("0 / 0")
        bottomPanel.add(pageLabel)

        nextButton = JButton("下一章").apply {
            isEnabled = false
            addActionListener { goToNextChapter() }
        }
        bottomPanel.add(nextButton)

        add(bottomPanel, BorderLayout.SOUTH)

        // 滚动时记录章节内位置。滚轮滚动时 valueIsAdjusting 为 false，会在停下后再落盘，
        // 拖动滚动条时则为 true，拖拽过程中只更新内存，避免逐像素写配置造成卡顿
        contentScrollPane.verticalScrollBar.addAdjustmentListener {
            val bar = contentScrollPane.verticalScrollBar
            LOG.info("记录滚动条位置[$windowId]: 滚动条高度=${bar.value}, 窗口总高度=${bar.maximum}")
            saveReadingProgress(storeScroll = true)
        }
        
        // 恢复上次阅读进度
        restoreLastReadingProgress()
        
        // 初始化控制按钮可见性
        updateControlsVisibility()
    }
    
    /**
     * 切换章节目录显示
     */
    private fun toggleChapterList() {
        if (chapters.isEmpty()) {
            return
        }
        
        // 检查当前显示的是哪个视图
        val isShowingChapters = chapterListButton.text == "返回阅读"
        
        if (isShowingChapters) {
            // 切换回阅读视图
            cardLayout.show(contentPanel, CARD_READER)
            chapterListButton.text = "章节目录"
            titleLabel.text = "$currentBookName - ${chapters[currentChapterIndex].title}"
        } else {
            // 显示章节目录
            updateChapterListModel()
            chapterJList.selectedIndex = currentChapterIndex
            chapterJList.ensureIndexIsVisible(currentChapterIndex)
            cardLayout.show(contentPanel, CARD_CHAPTERS)
            chapterListButton.text = "返回阅读"
            titleLabel.text = "$currentBookName - 章节目录"
        }
    }
    
    /**
     * 更新章节列表模型
     */
    private fun updateChapterListModel() {
        chapterListModel.clear()
        chapters.forEachIndexed { index, chapter ->
            val prefix = if (index == currentChapterIndex) "▶ " else "   "
            chapterListModel.addElement("$prefix${index + 1}. ${chapter.title}")
        }
    }
    
    /**
     * 选择章节
     */
    private fun selectChapter(index: Int) {
        if (index in chapters.indices) {
            currentChapterIndex = index
            updateContent()
            saveReadingProgress()
            // 切换回阅读视图
            cardLayout.show(contentPanel, CARD_READER)
            chapterListButton.text = "章节目录"
        }
    }

    /**
     * 恢复上次阅读进度
     */
    private fun restoreLastReadingProgress() {
        val settings = ReaderSettings.getInstance().state
        val lastBookPath = settings.lastBookPath
        val bookId = if (lastBookPath.isBlank()) "" else ReadingProgressStore.pathIdOf(File(lastBookPath))
        val stored = ReadingProgressStore.getInstance().progressOf(bookId)
        LOG.info("恢复进度: 从存储读取 stored=$stored, bookId=$bookId, 设置里的章节=${settings.lastChapterIndex}")
        // 升级前只记过章节索引时，沿用设置里的章节，滚动位置留空
        val progress = stored ?: settings.lastChapterIndex.takeIf { lastBookPath.isNotBlank() }?.let {
            ReadingProgressStore.Progress(it, null)
        }
        val lastIndex = progress?.chapterIndex ?: 0
        restoringScroll = true

        // 必须在滚动条真正设置完成后才解除屏蔽：updateContent 的恢复动作在 invokeLater 里，
        // 若这里同步置 false，恢复写入 value 触发的 adjustment 事件会被当成用户滚动保存，覆盖已存进度
        val finishRestore: () -> Unit = {
            restoringScroll = false
            LOG.info("恢复完成[$windowId]: 解除滚动屏蔽, 当前滚动条value=${contentScrollPane.verticalScrollBar.value}")
        }

        if (lastBookPath.isNotBlank()) {
            val file = File(lastBookPath)
            if (file.exists() && file.isFile) {
                try {
                    val content = file.readText(Charsets.UTF_8)
                    parseBookContent(file.nameWithoutExtension, content, file.absolutePath)
                    lastLoadedBookPath = file.absolutePath
                    
                    // 恢复章节位置
                    if (lastIndex in chapters.indices) {
                        currentChapterIndex = lastIndex
                    }
                    val savedScroll = progress?.scrollValue
                    updateContent(
                        resetScroll = savedScroll == null,
                        restoreScrollValue = savedScroll ?: 0,
                        onRestored = finishRestore
                    )
                    // 解析时索引被重置为 0，这里把恢复后的进度写回
                    saveReadingProgress()
                } catch (e: Exception) {
                    // 恢复失败，清空内容
                    chapters.clear()
                    lastLoadedBookPath = ""
                    currentBookName = "未加载书籍"
                    updateContent(onRestored = finishRestore)
                }
            } else {
                chapters.clear()
                lastLoadedBookPath = ""
                currentBookName = "未加载书籍"
                updateContent(onRestored = finishRestore)
            }
        } else {
            chapters.clear()
            lastLoadedBookPath = ""
            currentBookName = "未加载书籍"
            updateContent(onRestored = finishRestore)
        }
    }

    /**
     * 配置变化时调用
     */
    private fun onSettingsChanged() {
        val settings = ReaderSettings.getInstance().state
        val newBookPath = settings.lastBookPath
        
        // 书籍路径变化，重新加载
        if (newBookPath != lastLoadedBookPath) {
            restoreLastReadingProgress()
        } else {
            updateContent(resetScroll = false)
        }
        
        // 更新控制按钮可见性
        updateControlsVisibility()
    }
    
    /**
     * 更新控制按钮的可见性
     */
    private fun updateControlsVisibility() {
        val settings = ReaderSettings.getInstance().state
        val showControls = settings.showControls
        
        // 隐藏/显示顶部面板（包括标题和工具栏）和底部导航
        topPanel.isVisible = showControls
        bottomPanel.isVisible = showControls
        
        // 如果隐藏控制按钮且当前在章节目录视图，切换回阅读视图
        if (!showControls && chapterListButton.text == "返回阅读") {
            cardLayout.show(contentPanel, CARD_READER)
            chapterListButton.text = "章节目录"
        }
        
        revalidate()
        repaint()
    }

    /**
     * 构造当前工具窗口所属 IDEA 窗口的标识。
     * 无 Project 时退化为基于该面板对象身份的 hash，保证同一进程内不同面板仍可区分。
     */
    private fun buildWindowId(): String {
        val p = project ?: return "no-project@${System.identityHashCode(this).toString(16)}"
        val name = p.name.ifBlank { "<unnamed>" }
        val path = p.basePath ?: "<no-base-path>"
        val hash = p.locationHash
        return "$name | $path | $hash"
    }

    override fun dispose() {
        // 取消未决的防抖任务，关闭工具窗口 / 退出 IDE 时立即落盘兜底
        LOG.info("dispose[$windowId]: 取消防抖定时器并落盘, currentBookId=$currentBookId, 当前滚动条value=${contentScrollPane.verticalScrollBar.value}")
        scrollFlushTimer?.stop()
        scrollFlushTimer = null
        if (currentBookId.isNotEmpty()) {
            ReadingProgressStore.getInstance().flush()
        }
    }

    /**
     * 保存阅读进度（storeScroll：由滚动事件触发时传入，恢复期间忽略）
     */
    private fun saveReadingProgress(storeScroll: Boolean = false) {
        if (restoringScroll && storeScroll) return
        // 未加载书籍时 currentBookPath 为空，不覆盖已有进度
        if (currentBookPath.isEmpty() || currentBookId.isEmpty()) return

        val settings = ReaderSettings.getInstance()
        val state = settings.state
        settings.loadState(
            state.copy(
                lastBookPath = currentBookPath,
                lastChapterIndex = currentChapterIndex
            )
        )

        val store = ReadingProgressStore.getInstance()
        // 恢复阶段尚未写入滚动位置时沿用已有记录，避免把"未知"写成 0
        val existing = store.progressOf(currentBookId)
        val scrollValue = if (restoringScroll) existing?.scrollValue else contentScrollPane.verticalScrollBar.value
        LOG.info("saveReadingProgress[$windowId]: storeScroll=$storeScroll, restoringScroll=$restoringScroll, 计算出的scrollValue=$scrollValue, 当前滚动条value=${contentScrollPane.verticalScrollBar.value}")
        store.saveProgress(
            currentBookId,
            ReadingProgressStore.Progress(currentChapterIndex, scrollValue),
            // 滚动期间只更新内存，由防抖定时器在停下后统一落盘一次；
            // 非滚动触发（切章、恢复等）保持立即落盘
            flush = !storeScroll
        )

        if (storeScroll) {
            // 重置防抖计时：连续滚动只会在最后一次滚动结束 500ms 后写盘
            scrollFlushTimer?.stop()
            LOG.info("saveReadingProgress[$windowId]: 启动500ms防抖定时器, 将写入scrollValue=$scrollValue")
            scrollFlushTimer = Timer(500) {
                LOG.info("防抖定时器触发[$windowId]: 落盘scrollValue=$scrollValue")
                store.flush()
            }.apply { isRepeats = false }
            scrollFlushTimer?.start()
        } else {
            scrollFlushTimer?.stop()
            scrollFlushTimer = null
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().saveSettings()
            }
        }
    }

    /**
     * 解析书籍内容，按章节分割
     */
    private fun parseBookContent(bookName: String, content: String, filePath: String = "") {
        val settings = ReaderSettings.getInstance().state
        val chapterPattern = Regex(settings.chapterPattern, RegexOption.MULTILINE)
        
        chapters.clear()
        currentChapterIndex = 0
        currentBookName = bookName
        currentBookPath = filePath
        currentBookId = if (filePath.isEmpty()) "" else ReadingProgressStore.pathIdOf(File(filePath))
        
        val lines = content.lines()
        var currentTitle = "序章"
        val currentContent = StringBuilder()
        
        for (line in lines) {
            if (chapterPattern.matches(line.trim())) {
                // 保存上一章
                if (currentContent.isNotBlank()) {
                    chapters.add(Chapter(currentTitle, currentContent.toString().trim()))
                }
                // 开始新章节
                currentTitle = line.trim()
                currentContent.clear()
            } else {
                currentContent.appendLine(line)
            }
        }
        
        // 保存最后一章
        if (currentContent.isNotBlank()) {
            chapters.add(Chapter(currentTitle, currentContent.toString().trim()))
        }
        
        // 如果没有识别到章节，将整个内容作为一章
        if (chapters.isEmpty()) {
            chapters.add(Chapter(bookName, content))
        }

        updateContent()
    }


    /**
     * 上一章 (public 供 Action 调用)
     */
    fun goToPreviousChapter() {
        if (currentChapterIndex > 0) {
            currentChapterIndex--
            updateContent()
            saveReadingProgress()
        }
    }

    /**
     * 下一章 (public 供 Action 调用)
     */
    fun goToNextChapter() {
        if (currentChapterIndex < chapters.size - 1) {
            currentChapterIndex++
            updateContent()
            saveReadingProgress()
        }
    }

    private fun applyLineSpacing() {
        val settings = ReaderSettings.getInstance().state
        val attrs = SimpleAttributeSet()
        StyleConstants.setLineSpacing(attrs, settings.lineSpacing - 1.0f)
        val doc = contentArea.styledDocument
        doc.setParagraphAttributes(0, doc.length, attrs, false)
    }

    private fun updateTabTitle() {
        val title = chapters.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() } ?: "阅读器"
        onTitleChanged?.invoke(title)
    }

    /**
     * 更新显示内容。
     *
     * resetScroll 为 true 时回到章节开头；restoreScrollValue 用于恢复上次阅读的位置，
     * 会在布局完成后按比例还原（文档未完成排版时滚动条的最大值不可靠）。
     */
    private fun updateContent(
        resetScroll: Boolean = true,
        restoreScrollValue: Int = 0,
        onRestored: (() -> Unit)? = null
    ) {
        val settings = ReaderSettings.getInstance().state
        contentArea.font = Font("Microsoft YaHei", Font.PLAIN, settings.fontSize)
        
        if (chapters.isEmpty()) {
            titleLabel.text = "未加载书籍"
            contentArea.text = "请在设置中选择书籍文件开始阅读...\n\nSettings → Tools → BookPeek"
            applyLineSpacing()
            pageLabel.text = "0 / 0"
            prevButton.isEnabled = false
            nextButton.isEnabled = false
            updateTabTitle()
            onRestored?.invoke()
            return
        }
        
        val scrollBar = contentScrollPane.verticalScrollBar
        val savedScrollValue = scrollBar.value
        val savedScrollRatio = if (scrollBar.maximum > 0) savedScrollValue.toDouble() / scrollBar.maximum else 0.0
        
        val chapter = chapters[currentChapterIndex]
        titleLabel.text = "$currentBookName - ${chapter.title}"
        val indentedContent = chapter.content.lines().joinToString("\n") { line ->
            if (line.isNotBlank()) "\u3000\u3000${line.trimStart()}" else line
        }
        contentArea.text = indentedContent
        applyLineSpacing()
        if (resetScroll) {
            contentArea.caretPosition = 0
        } else {
            val restoredRatio = if (restoringScroll && scrollBar.maximum > 0) {
                restoreScrollValue.toDouble() / scrollBar.maximum
            } else {
                savedScrollRatio
            }
            SwingUtilities.invokeLater {
                val maxScroll = maxOf(scrollBar.maximum - scrollBar.visibleAmount, 0)
                val value = when {
                    restoringScroll && restoreScrollValue > 0 && restoreScrollValue <= maxScroll -> restoreScrollValue
                    else -> (restoredRatio * maxScroll).toInt()
                }
                scrollBar.value = value.coerceIn(0, maxScroll)
                LOG.info("恢复滚动条位置[$windowId]: 恢复到的高度=${scrollBar.value}, 窗口总高度=${scrollBar.maximum}")
                // 滚动条设置完成后才解除屏蔽，避免恢复写入触发的 adjustment 事件被当成用户滚动保存
                onRestored?.invoke()
            }
        }
        if (resetScroll) {
            onRestored?.invoke()
        }
        pageLabel.text = "${currentChapterIndex + 1} / ${chapters.size}"
        prevButton.isEnabled = currentChapterIndex > 0
        nextButton.isEnabled = currentChapterIndex < chapters.size - 1
        updateTabTitle()
    }
}

/**
 * 修正自动换行行尾的命中偏移。
 *
 * DefaultCaret 处理鼠标拖拽时直接调用 UI 的 viewToModel，绕过了组件自身的方法，
 * 所以只能在 UI 层拦。指针落在某行最后一个字的右侧空白里时，默认实现会把偏移钳在
 * "最后一个字的起点"，这个字永远进不了选区；这里把这种"行末外侧"的命中推进到
 * 该字之后，也就是下一行的行首。
 */
private class BookPeekTextPaneUI : BasicTextPaneUI() {
    override fun viewToModel(tc: JTextComponent, pt: Point, biasReturn: Array<Position.Bias>): Int =
        fixLineEnd(tc, pt.x.toDouble(), super.viewToModel(tc, pt, biasReturn))

    override fun viewToModel2D(tc: JTextComponent, pt: Point2D, biasReturn: Array<Position.Bias>): Int =
        fixLineEnd(tc, pt.x, super.viewToModel2D(tc, pt, biasReturn))

    private fun fixLineEnd(tc: JTextComponent, x: Double, off: Int): Int {
        val doc = tc.document ?: return off
        if (off <= 0 || off >= doc.length) return off
        return try {
            val here = tc.modelToView2D(off) ?: return off
            val next = tc.modelToView2D(off + 1) ?: return off
            // 只有软换行的行尾才有这个问题：该字必须是本视觉行的最后一个字
            if (next.y == here.y && off + 1 < doc.length) return off
            val ch = doc.getText(off, 1)[0]
            if (ch == '\n' || ch == '\r') return off
            val charRight = here.x + tc.getFontMetrics(tc.font).charWidth(ch)
            if (x > charRight) off + 1 else off
        } catch (e: Exception) {
            off
        }
    }
}