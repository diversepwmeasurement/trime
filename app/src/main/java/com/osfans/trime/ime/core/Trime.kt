/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.core.Rime
import com.osfans.trime.data.AppPrefs
import com.osfans.trime.data.db.DraftHelper
import com.osfans.trime.data.sound.SoundThemeManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.databinding.CompositionRootBinding
import com.osfans.trime.ime.broadcast.IntentReceiver
import com.osfans.trime.ime.enums.Keycode
import com.osfans.trime.ime.enums.SymbolKeyboardType
import com.osfans.trime.ime.keyboard.Event
import com.osfans.trime.ime.keyboard.InitializationUi
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyboardSwitcher
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.landscapeinput.LandscapeInputUIMode
import com.osfans.trime.ime.lifecycle.LifecycleInputMethodService
import com.osfans.trime.ime.symbol.TabManager
import com.osfans.trime.ime.symbol.TabView
import com.osfans.trime.ime.text.Candidate
import com.osfans.trime.ime.text.Composition
import com.osfans.trime.ime.text.CompositionPopupWindow
import com.osfans.trime.ime.text.ScrollView
import com.osfans.trime.ime.text.TextInputManager
import com.osfans.trime.util.ShortcutUtils
import com.osfans.trime.util.StringUtils
import com.osfans.trime.util.ViewUtils
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.isNightMode
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.systemservices.inputMethodManager
import timber.log.Timber
import java.util.Objects

/** [輸入法][InputMethodService]主程序  */

@Suppress("ktlint:standard:property-naming")
open class Trime : LifecycleInputMethodService() {
    private var normalTextEditor = false
    private val prefs: AppPrefs
        get() = AppPrefs.defaultInstance()
    private var mainKeyboardView: KeyboardView? = null // 主軟鍵盤
    private var mCandidate: Candidate? = null // 候選
    private var mComposition: Composition? = null // 編碼
    private var compositionRootBinding: CompositionRootBinding? = null
    private var mCandidateRoot: ScrollView? = null
    private var mTabRoot: ScrollView? = null
    private var tabView: TabView? = null
    var inputView: InputView? = null
    private var eventListeners = WeakHashSet<EventListener>()
    var inputFeedbackManager: InputFeedbackManager? = null // 效果管理器
    private var mIntentReceiver: IntentReceiver? = null
    private var editorInfo: EditorInfo? = null
    private var isWindowShown = false // 键盘窗口是否已显示
    private var isAutoCaps = false // 句首自動大寫
    var activeEditorInstance: EditorInstance? = null
    var textInputManager: TextInputManager? = null // 文字输入管理器
    private var minPopupSize = 0 // 上悬浮窗的候选词的最小词长
    private var minPopupCheckSize = 0 // 第一屏候选词数量少于设定值，则候选词上悬浮窗。（也就是说，第一屏存在长词）此选项大于1时，min_length等参数失效
    private var mCompositionPopupWindow: CompositionPopupWindow? = null
    private var candidateExPage = false

    fun hasCandidateExPage(): Boolean {
        return candidateExPage
    }

    fun setCandidateExPage(value: Boolean) {
        candidateExPage = value
    }

    init {
        try {
            check(self == null) { "Trime is already initialized" }
            self = this
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (isWindowShown) {
            Timber.i("Ignoring (is already shown)")
            return
        } else {
            Timber.i("onWindowShown...")
        }
        if (RimeWrapper.isReady() && activeEditorInstance != null) {
            isWindowShown = true
            updateComposing()
            for (listener in eventListeners) {
                listener.onWindowShown()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (!isWindowShown) {
            Timber.d("Ignoring (window is already hidden)")
            return
        } else {
            Timber.d("onWindowHidden")
        }
        isWindowShown = false
        if (prefs.profile.syncBackgroundEnabled) {
            val msg = Message()
            msg.obj = this
            syncBackgroundHandler.sendMessageDelayed(msg, 5000) // 输入面板隐藏5秒后，开始后台同步
        }
        for (listener in eventListeners) {
            listener.onWindowHidden()
        }
    }

    fun updatePopupWindow(
        offsetX: Int,
        offsetY: Int,
    ) {
        mCompositionPopupWindow!!.updatePopupWindow(offsetX, offsetY)
    }

    fun loadConfig() {
        val theme = ThemeManager.activeTheme
        minPopupSize = theme.style.getInt("layout/min_length")
        minPopupCheckSize = theme.style.getInt("layout/min_check")
        textInputManager!!.shouldResetAsciiMode = theme.style.getBoolean("reset_ascii_mode")
        isAutoCaps = theme.style.getBoolean("auto_caps")
        textInputManager!!.shouldUpdateRimeOption = true
        mCompositionPopupWindow!!.loadConfig(theme, prefs)
    }

    private fun updateRimeOption(): Boolean {
        try {
            if (textInputManager!!.shouldUpdateRimeOption) {
                Rime.setOption("soft_cursor", prefs.keyboard.softCursorEnabled) // 軟光標
                Rime.setOption("_horizontal", ThemeManager.activeTheme.style.getBoolean("horizontal")) // 水平模式
                textInputManager!!.shouldUpdateRimeOption = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /** 防止重启系统 强行停止应用时alarm任务丢失 */
    @SuppressLint("ScheduleExactAlarm")
    fun restartSystemStartTimingSync() {
        if (prefs.profile.timingSyncEnabled) {
            val triggerTime = prefs.profile.timingSyncTriggerTime
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

            /** 设置待发送的同步事件 */
            val pendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("com.osfans.trime.timing.sync"),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // 根据SDK设置alarm任务
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // MUST WRAP all code within Service onCreate() in try..catch to prevent any crash loops
        try {
            // Additional try..catch wrapper as the event listeners chain or the super.onCreate() method
            // could crash
            //  and lead to a crash loop
            Timber.d("onCreate")
            val context: InputMethodService = this
            RimeWrapper.startup {
                Timber.d("Running Trime.onCreate")
                ThemeManager.init(resources.configuration)
                textInputManager = TextInputManager.getInstance()
                activeEditorInstance = EditorInstance(context)
                inputFeedbackManager = InputFeedbackManager(context)
                mCompositionPopupWindow = CompositionPopupWindow()
                restartSystemStartTimingSync()
                try {
                    for (listener in eventListeners) {
                        listener.onCreate()
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
                Timber.d("Trime.onCreate  completed")
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private var symbolKeyboardType = SymbolKeyboardType.NO_KEY

    fun inputSymbol(text: String) {
        textInputManager!!.onPress(KeyEvent.KEYCODE_UNKNOWN)
        if (Rime.isAsciiMode) Rime.setOption("ascii_mode", false)
        val asciiPunch = Rime.isAsciiPunch
        if (asciiPunch) Rime.setOption("ascii_punct", false)
        textInputManager!!.onText("{Escape}$text")
        if (asciiPunch) Rime.setOption("ascii_punct", true)
        self!!.selectLiquidKeyboard(-1)
    }

    fun selectLiquidKeyboard(tabIndex: Int) {
        if (inputView == null) return
        if (tabIndex >= 0) {
            inputView!!.switchUiByState(KeyboardWindow.State.Symbol)
            symbolKeyboardType = inputView!!.liquidKeyboard.select(tabIndex)
            tabView!!.updateTabWidth()
            mTabRoot!!.move(tabView!!.hightlightLeft, tabView!!.hightlightRight)
            showLiquidKeyboardToolbar()
        } else {
            symbolKeyboardType = SymbolKeyboardType.NO_KEY
            // 设置液体键盘处于隐藏状态
            TabManager.get().setTabExited()
            inputView!!.switchUiByState(KeyboardWindow.State.Main)
            updateComposing()
        }
    }

    // 按键需要通过tab name来打开liquidKeyboard的指定tab
    fun selectLiquidKeyboard(name: String) {
        if (name.matches("-?\\d+".toRegex())) {
            selectLiquidKeyboard(name.toInt())
        } else if (name.matches("[A-Z]+".toRegex())) {
            selectLiquidKeyboard(SymbolKeyboardType.valueOf(name))
        } else {
            selectLiquidKeyboard(TabManager.getTagIndex(name))
        }
    }

    fun selectLiquidKeyboard(type: SymbolKeyboardType?) {
        selectLiquidKeyboard(TabManager.getTagIndex(type))
    }

    fun pasteByChar() {
        commitTextByChar(Objects.requireNonNull(ShortcutUtils.pasteFromClipboard(this)).toString())
    }

    private fun showCompositionView(isCandidate: Boolean) {
        if (TextUtils.isEmpty(Rime.compositionText) && isCandidate) {
            mCompositionPopupWindow!!.hideCompositionView()
            return
        }
        compositionRootBinding!!.compositionRoot.measure(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        mCompositionPopupWindow!!.updateCompositionView(
            compositionRootBinding!!.compositionRoot.measuredWidth,
            compositionRootBinding!!.compositionRoot.measuredHeight,
        )
    }

    private fun loadBackground(recreate: Boolean) {
        val theme = ThemeManager.activeTheme
        val textBackground =
            theme.colors.getDrawable(
                "text_back_color",
                "layout/border",
                "border_color",
                "layout/round_corner",
                "layout/alpha",
            )
        mCompositionPopupWindow!!.setThemeStyle(
            dp(theme.style.getFloat("layout/elevation")),
            textBackground,
        )

        if (inputView == null || !recreate) return

        inputView?.quickBar?.view?.background =
            theme.colors.getDrawable(
                "candidate_background",
                "candidate_border",
                "candidate_border_color",
                "candidate_border_round",
                null,
            )

        inputView?.background = theme.colors.getDrawable("root_background")

        tabView!!.reset()
    }

    fun resetKeyboard() {
        if (mainKeyboardView != null) {
            mainKeyboardView!!.setShowHint(!Rime.getOption("_hide_key_hint"))
            mainKeyboardView!!.setShowSymbol(!Rime.getOption("_hide_key_symbol"))
            mainKeyboardView!!.reset() // 實體鍵盤無軟鍵盤
        }
    }

    fun resetCandidate() {
        if (mCandidateRoot != null) {
            loadBackground(true)
            setShowComment(!Rime.getOption("_hide_comment"))
            mCandidateRoot!!.visibility = if (!Rime.getOption("_hide_candidate")) View.VISIBLE else View.GONE
            mCandidate!!.reset()
            mCompositionPopupWindow!!.isPopupWindowEnabled = (
                prefs.keyboard.popupWindowEnabled &&
                    ThemeManager.activeTheme.style.getObject("window") != null
            )
            mComposition!!.visibility = if (mCompositionPopupWindow!!.isPopupWindowEnabled) View.VISIBLE else View.GONE
            mComposition!!.reset()
        }
    }

    /** 重置鍵盤、候選條、狀態欄等 !!注意，如果其中調用Rime.setOption，切換方案會卡住  */
    private fun reset() {
        if (inputView == null) return
        inputView!!.switchUiByState(KeyboardWindow.State.Main)
        loadConfig()
        val theme = ThemeManager.activeTheme
        SoundThemeManager.switchSound(theme.colors.getString("sound"))
        resetCandidate()
        KeyboardSwitcher.newOrReset()
        mCompositionPopupWindow!!.hideCompositionView()
        resetKeyboard()
    }

    /** Must be called on the UI thread  */
    fun initKeyboard() {
        if (textInputManager != null) {
            reset()
            // setNavBarColor();
            textInputManager!!.shouldUpdateRimeOption = true // 不能在Rime.onMessage中調用set_option，會卡死
            bindKeyboardToInputView()
            // loadBackground(); // reset()调用过resetCandidate()，resetCandidate()一键调用过loadBackground();
            updateComposing() // 切換主題時刷新候選
        }
    }

    override fun onDestroy() {
        if (mIntentReceiver != null) mIntentReceiver!!.unregisterReceiver(this)
        mIntentReceiver = null
        if (inputFeedbackManager != null) inputFeedbackManager!!.destroy()
        inputFeedbackManager = null
        inputView = null
        for (listener in eventListeners) {
            listener.onDestroy()
        }
        eventListeners.clear()
        if (mCompositionPopupWindow != null) {
            mCompositionPopupWindow!!.destroy()
        }
        super.onDestroy()
        self = null
    }

    private fun handleReturnKey() {
        if (editorInfo == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        if (editorInfo!!.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        if (editorInfo!!.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            val ic = currentInputConnection
            ic?.commitText("\n", 1)
            return
        }
        if (!TextUtils.isEmpty(editorInfo!!.actionLabel) &&
            editorInfo!!.actionId != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            val ic = currentInputConnection
            ic?.performEditorAction(editorInfo!!.actionId)
            return
        }
        val action = editorInfo!!.imeOptions and EditorInfo.IME_MASK_ACTION
        val ic = currentInputConnection
        when (action) {
            EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> ic?.commitText("\n", 1)
            else -> ic?.performEditorAction(action)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val config = resources.configuration
        if (config.orientation != newConfig.orientation) {
            // Clear composing text and candidates for orientation change.
            performEscape()
            config.orientation = newConfig.orientation
        }
        super.onConfigurationChanged(newConfig)
        ThemeManager.onSystemNightModeChange(newConfig.isNightMode())
        initKeyboard()
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        mCompositionPopupWindow!!.updateCursorAnchorInfo(cursorAnchorInfo)
        if (mCandidateRoot != null) {
            showCompositionView(true)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (candidatesEnd != -1 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // 移動光標時，更新候選區
            if (newSelEnd in candidatesStart..<candidatesEnd) {
                val n = newSelEnd - candidatesStart
                Rime.setCaretPos(n)
                updateComposing()
            }
        }
        if (candidatesStart == -1 && candidatesEnd == -1 && newSelStart == 0 && newSelEnd == 0) {
            // 上屏後，清除候選區
            performEscape()
        }
        // Update the caps-lock status for the current cursor position.
        dispatchCapsStateToInputView()
    }

    override fun onComputeInsets(outInsets: Insets) {
        val (_, y) = intArrayOf(0, 0).also { inputView?.keyboardView?.getLocationInWindow(it) }
        outInsets.apply {
            contentTopInsets = y
            touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            touchableRegion.setEmpty()
            visibleTopInsets = y
        }
    }

    override fun onCreateInputView(): View {
        Timber.d("onCreateInputView()")
        RimeWrapper.runAfterStarted {
            inputView = InputView(this, Rime.getInstance(false), ThemeManager.activeTheme)
            mainKeyboardView = inputView!!.keyboardWindow.oldMainInputView.mainKeyboardView
            // 初始化候选栏
            mCandidateRoot = inputView!!.quickBar.oldCandidateBar.root
            mCandidate = inputView!!.quickBar.oldCandidateBar.candidates

            // 候选词悬浮窗的容器
            compositionRootBinding = CompositionRootBinding.inflate(LayoutInflater.from(this))
            mComposition = compositionRootBinding!!.compositions
            mCompositionPopupWindow!!.init(compositionRootBinding!!.compositionRoot, mCandidateRoot!!)
            mTabRoot = inputView!!.quickBar.oldTabBar.root

            tabView = inputView!!.quickBar.oldTabBar.tabs
            for (listener in eventListeners) {
                listener.onInitializeInputUi(inputView!!)
            }
            loadBackground(false)
            KeyboardSwitcher.newOrReset()
            bindKeyboardToInputView()
            setInputView(inputView!!)
            Timber.d("onCreateInputView - completely ended")
        }
        Timber.d("onCreateInputView() finish")
        return InitializationUi(this).root
    }

    override fun setInputView(view: View) {
        val inputArea =
            window.window!!.decorView
                .findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        super.setInputView(view)
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun setShowComment(showComment: Boolean) {
        if (mCandidateRoot != null) mCandidate!!.setShowComment(showComment)
        mComposition!!.setShowComment(showComment)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        editorInfo = attribute
        Timber.d("onStartInput: restarting=%s", restarting)
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=%s", restarting)
        editorInfo = attribute
        RimeWrapper.runAfterStarted {
            inputFeedbackManager!!.resumeSoundPool()
            inputFeedbackManager!!.resetPlayProgress()
            for (listener in eventListeners) {
                listener.onStartInputView(activeEditorInstance!!, restarting)
            }
            if (prefs.other.showStatusBarIcon) {
                showStatusIcon(R.drawable.ic_trime_status) // 狀態欄圖標
            }
            bindKeyboardToInputView()
            // if (!restarting) setNavBarColor();
            setCandidatesViewShown(!Rime.isEmpty) // 軟鍵盤出現時顯示候選欄
            if (attribute.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION
                == EditorInfo.IME_FLAG_NO_ENTER_ACTION
            ) {
                mainKeyboardView!!.resetEnterLabel()
            } else {
                mainKeyboardView!!.setEnterLabel(
                    attribute.imeOptions and EditorInfo.IME_MASK_ACTION,
                    attribute.actionLabel,
                )
            }
            when (attribute.inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> {
                    Timber.i(
                        "EditorInfo: private;" +
                            " packageName=" +
                            attribute.packageName +
                            "; fieldName=" +
                            attribute.fieldName +
                            "; actionLabel=" +
                            attribute.actionLabel +
                            "; inputType=" +
                            attribute.inputType +
                            "; VARIATION=" +
                            (attribute.inputType and InputType.TYPE_MASK_VARIATION) +
                            "; CLASS=" +
                            (attribute.inputType and InputType.TYPE_MASK_CLASS) +
                            "; ACTION=" +
                            (attribute.imeOptions and EditorInfo.IME_MASK_ACTION),
                    )
                    normalTextEditor = false
                }

                else -> {
                    Timber.i(
                        "EditorInfo: normal;" +
                            " packageName=" +
                            attribute.packageName +
                            "; fieldName=" +
                            attribute.fieldName +
                            "; actionLabel=" +
                            attribute.actionLabel +
                            "; inputType=" +
                            attribute.inputType +
                            "; VARIATION=" +
                            (attribute.inputType and InputType.TYPE_MASK_VARIATION) +
                            "; CLASS=" +
                            (attribute.inputType and InputType.TYPE_MASK_CLASS) +
                            "; ACTION=" +
                            (attribute.imeOptions and EditorInfo.IME_MASK_ACTION),
                    )
                    if (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        == EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    ) {
                        //  应用程求以隐身模式打开键盘应用程序
                        normalTextEditor = false
                        Timber.d("EditorInfo: normal -> private, IME_FLAG_NO_PERSONALIZED_LEARNING")
                    } else if (attribute.packageName == BuildConfig.APPLICATION_ID ||
                        prefs
                            .clipboard
                            .draftExcludeApp
                            .contains(attribute.packageName)
                    ) {
                        normalTextEditor = false
                        Timber.d("EditorInfo: normal -> exclude, packageName=%s", attribute.packageName)
                    } else {
                        normalTextEditor = true
                        DraftHelper.onInputEventChanged()
                    }
                }
            }
        }
        RimeWrapper.runCheck()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (RimeWrapper.isReady()) {
            if (normalTextEditor) {
                DraftHelper.onInputEventChanged()
            }
            try {
                // Dismiss any pop-ups when the input-view is being finished and hidden.
                mainKeyboardView!!.closing()
                performEscape()
                if (inputFeedbackManager != null) {
                    inputFeedbackManager!!.releaseSoundPool()
                }
                mCompositionPopupWindow!!.hideCompositionView()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show the PopupWindow.")
            }
        }
        if (inputView != null) {
            inputView!!.finishInput()
        }
        Timber.d("OnFinishInputView")
    }

    override fun onFinishInput() {
        editorInfo = null
        super.onFinishInput()
    }

    fun bindKeyboardToInputView() {
        if (mainKeyboardView != null) {
            // Bind the selected keyboard to the input view.
            val sk = KeyboardSwitcher.currentKeyboard
            mainKeyboardView!!.keyboard = sk
            dispatchCapsStateToInputView()
        }
    }

    /**
     * Dispatches cursor caps info to input view in order to implement auto caps lock at the start of
     * a sentence.
     */
    private fun dispatchCapsStateToInputView() {
        if (isAutoCaps && Rime.isAsciiMode && mainKeyboardView != null && !mainKeyboardView!!.isCapsOn) {
            mainKeyboardView!!.setShifted(false, activeEditorInstance!!.cursorCapsMode != 0)
        }
    }

    private val isComposing: Boolean
        get() = Rime.isComposing

    /**
     * Commit the current composing text together with the new text
     *
     * @param text the new text to be committed
     */
    fun commitText(text: String?) {
        currentInputConnection.finishComposingText()
        activeEditorInstance!!.commitText(text!!, true)
    }

    private fun commitTextByChar(text: String) {
        for (i in text.indices) {
            if (!activeEditorInstance!!.commitText(text.substring(i, i + 1), false)) break
        }
    }

    /**
     * 如果爲Back鍵[KeyEvent.KEYCODE_BACK]，則隱藏鍵盤
     *
     * @param keyCode 鍵碼[KeyEvent.getKeyCode]
     * @return 是否處理了Back鍵事件
     */
    private fun handleBack(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            requestHideSelf(0)
            return true
        }
        return false
    }

    private fun onRimeKey(event: IntArray): Boolean {
        updateRimeOption()
        // todo 改为异步处理按键事件、刷新UI
        val ret = Rime.processKey(event[0], event[1])
        activeEditorInstance!!.commitRimeText()
        return ret
    }

    private fun composeEvent(event: KeyEvent): Boolean {
        if (textInputManager == null) {
            return false
        }
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_MENU) return false // 不處理 Menu 鍵
        if (!Keycode.isStdKey(keyCode)) return false // 只處理安卓標準按鍵
        if (event.repeatCount == 0 && Key.isTrimeModifierKey(keyCode)) {
            val ret =
                onRimeKey(
                    Event.getRimeEvent(
                        keyCode,
                        if (event.action == KeyEvent.ACTION_DOWN) event.modifiers else Rime.META_RELEASE_ON,
                    ),
                )
            if (this.isComposing) setCandidatesViewShown(textInputManager!!.isComposable) // 藍牙鍵盤打字時顯示候選欄
            return ret
        }
        return textInputManager!!.isComposable && !Rime.isVoidKeycode(keyCode)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        Timber.i("\t<TrimeInput>\tonKeyDown()\tkeycode=%d, event=%s", keyCode, event.toString())
        return if (composeEvent(event) && onKeyEvent(event) && isWindowShown) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        Timber.i("\t<TrimeInput>\tonKeyUp()\tkeycode=%d, event=%s", keyCode, event.toString())
        if (composeEvent(event) && textInputManager!!.needSendUpRimeKey) {
            textInputManager!!.onRelease(keyCode)
            if (isWindowShown) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 处理实体键盘事件
     *
     * @param event 按鍵事件[KeyEvent]
     * @return 是否成功處理
     */
    private fun onKeyEvent(event: KeyEvent): Boolean {
        Timber.i("\t<TrimeInput>\tonKeyEvent()\tRealKeyboard event=%s", event.toString())
        var keyCode = event.keyCode
        textInputManager!!.needSendUpRimeKey = Rime.isComposing
        if (!this.isComposing) {
            if (keyCode == KeyEvent.KEYCODE_DEL ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BACK
            ) {
                return false
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            keyCode = KeyEvent.KEYCODE_ESCAPE // 返回鍵清屏
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.repeatCount == 0 && !KeyEvent.isModifierKey(keyCode)) {
            if (hookKeyboard(keyCode, event.metaState)) return true
        }
        val unicodeChar = event.unicodeChar
        val s = unicodeChar.toChar().toString()
        val i = Event.getClickCode(s)
        var mask = 0
        if (i > 0) {
            keyCode = i
        } else { // 空格、回車等
            mask = event.metaState
        }
        val ret = handleKey(keyCode, mask)
        if (this.isComposing) setCandidatesViewShown(textInputManager!!.isComposable) // 藍牙鍵盤打字時顯示候選欄
        return ret
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                val window = window.window
                if (window != null) {
                    inputMethodManager
                        .switchToLastInputMethod(window.attributes.token)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                val window = window.window
                if (window != null) {
                    inputMethodManager
                        .switchToNextInputMethod(window.attributes.token, false)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    // 处理键盘事件(Android keycode)
    fun handleKey(
        keyEventCode: Int,
        metaState: Int,
    ): Boolean { // 軟鍵盤
        textInputManager!!.needSendUpRimeKey = false
        if (onRimeKey(Event.getRimeEvent(keyEventCode, metaState))) {
            // 如果输入法消费了按键事件，则需要释放按键
            textInputManager!!.needSendUpRimeKey = true
            Timber.d(
                "\t<TrimeInput>\thandleKey()\trimeProcess, keycode=%d, metaState=%d",
                keyEventCode,
                metaState,
            )
        } else if (hookKeyboard(keyEventCode, metaState)) {
            Timber.d("\t<TrimeInput>\thandleKey()\thookKeyboard, keycode=%d", keyEventCode)
        } else if (performEnter(keyEventCode) || handleBack(keyEventCode)) {
            // 处理返回键（隐藏软键盘）和回车键（换行）
            // todo 确认是否有必要单独处理回车键？是否需要把back和escape全部占用？
            Timber.d("\t<TrimeInput>\thandleKey()\tEnterOrHide, keycode=%d", keyEventCode)
        } else if (ShortcutUtils.openCategory(keyEventCode)) {
            // 打开系统默认应用
            Timber.d("\t<TrimeInput>\thandleKey()\topenCategory keycode=%d", keyEventCode)
        } else {
            textInputManager!!.needSendUpRimeKey = true
            Timber.d(
                "\t<TrimeInput>\thandleKey()\treturn FALSE, keycode=%d, metaState=%d",
                keyEventCode,
                metaState,
            )
            return false
        }
        return true
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    private fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA) ic.performContextMenuAction(android.R.id.selectAll) else false
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionEnd - et.selectionStart > 0) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.i("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionEnd - et.selectionStart > 0) return ic.performContextMenuAction(android.R.id.copy)
                    }
                }
                Timber.i("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        if (ic.commitText(ShortcutUtils.pasteFromClipboard(this), 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = StringUtils.findSectionAfter(et.text, et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = StringUtils.findSectionBefore(et.text, et.startOffset + et.selectionStart)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    /** 更新Rime的中西文狀態、編輯區文本  */
    fun updateComposing(): Int {
        val ic = currentInputConnection
        activeEditorInstance!!.updateComposingText()
        if (ic != null && !mCompositionPopupWindow!!.isWinFixed()) {
            mCompositionPopupWindow!!.isCursorUpdated = ic.requestCursorUpdates(1)
        }
        var startNum = 0
        if (mCandidateRoot != null) {
            if (mCompositionPopupWindow!!.isPopupWindowEnabled) {
                Timber.d("updateComposing() SymbolKeyboardType=%s", symbolKeyboardType.toString())
                if (symbolKeyboardType != SymbolKeyboardType.NO_KEY &&
                    symbolKeyboardType != SymbolKeyboardType.CANDIDATE
                ) {
                    showLiquidKeyboardToolbar()
                } else {
                    mComposition!!.visibility = View.VISIBLE
                    startNum = mComposition!!.setWindow(minPopupSize, minPopupCheckSize, Int.MAX_VALUE)
                    mCandidate!!.setText(startNum)
                    // if isCursorUpdated, showCompositionView will be called in onUpdateCursorAnchorInfo
                    // otherwise we need to call it here
                    if (!mCompositionPopupWindow!!.isCursorUpdated) showCompositionView(true)
                }
            } else {
                mCandidate!!.setText(0)
            }
            mCandidate!!.setExpectWidth(mainKeyboardView!!.width)
            // 刷新候选词后，如果候选词超出屏幕宽度，滚动候选栏
            mTabRoot!!.move(mCandidate!!.highlightLeft, mCandidate!!.highlightRight)
        }
        if (mainKeyboardView != null) mainKeyboardView!!.invalidateComposingKeys()
        if (!onEvaluateInputViewShown()) setCandidatesViewShown(textInputManager!!.isComposable) // 實體鍵盤打字時顯示候選欄
        return startNum
    }

    private fun showLiquidKeyboardToolbar() {
        mComposition!!.changeToLiquidKeyboardToolbar()
        showCompositionView(false)
    }

    /**
     * 如果爲回車鍵[KeyEvent.KEYCODE_ENTER]，則換行
     *
     * @param keyCode 鍵碼[KeyEvent.getKeyCode]
     * @return 是否處理了回車事件
     */
    private fun performEnter(keyCode: Int): Boolean { // 回車
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            DraftHelper.onInputEventChanged()
            handleReturnKey()
            return true
        }
        return false
    }

    /** 模擬PC鍵盤中Esc鍵的功能：清除輸入的編碼和候選項  */
    fun performEscape() {
        if (this.isComposing) textInputManager!!.onKey(KeyEvent.KEYCODE_ESCAPE, 0)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val config = resources.configuration
        if (config == null || config.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        return when (prefs.keyboard.fullscreenMode) {
            LandscapeInputUIMode.AUTO_SHOW -> {
                Timber.d("FullScreen: Auto")
                val ei = currentInputEditorInfo
                if (ei != null && ei.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0) {
                    return false
                }
                Timber.d("FullScreen: Always")
                true
            }

            LandscapeInputUIMode.ALWAYS_SHOW -> {
                Timber.d("FullScreen: Always")
                true
            }

            LandscapeInputUIMode.NEVER_SHOW -> {
                Timber.d("FullScreen: Never")
                false
            }
        }
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        updateSoftInputWindowLayoutParameters()
    }

    /** Updates the layout params of the window and input view.  */
    private fun updateSoftInputWindowLayoutParameters() {
        val w = window.window ?: return
        if (inputView != null) {
            val layoutHeight =
                if (isFullscreenMode) {
                    WindowManager.LayoutParams.WRAP_CONTENT
                } else {
                    WindowManager.LayoutParams.MATCH_PARENT
                }
            val inputArea = w.findViewById<View>(android.R.id.inputArea)
            // TODO: 需要获取到文本编辑框、完成按钮，设置其色彩和尺寸。
            //      if (isFullscreenMode()) {
            //        Timber.d("isFullscreenMode");
            //        /* In Fullscreen mode, when layout contains transparent color,
            //         * the background under input area will disturb users' typing,
            //         * so set the input area as light pink */
            //        inputArea.setBackgroundColor(parseColor("#ff660000"));
            //      } else {
            //        Timber.d("NotFullscreenMode");
            //        /* Otherwise, set it as light gray to avoid potential issue */
            //        inputArea.setBackgroundColor(parseColor("#dddddddd"));
            //      }
            ViewUtils.updateLayoutHeightOf(inputArea, layoutHeight)
            ViewUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM)
            ViewUtils.updateLayoutHeightOf(inputView!!, layoutHeight)
        }
    }

    fun addEventListener(listener: EventListener): Boolean {
        return eventListeners.add(listener)
    }

    fun removeEventListener(listener: EventListener): Boolean {
        return eventListeners.remove(listener)
    }

    interface EventListener {
        fun onCreate() {}

        fun onInitializeInputUi(inputView: InputView) {}

        fun onDestroy() {}

        fun onStartInputView(
            instance: EditorInstance,
            restarting: Boolean,
        ) {}

        fun osFinishInputView(finishingInput: Boolean) {}

        fun onWindowShown() {}

        fun onWindowHidden() {}

        fun onUpdateSelection() {}
    }

    companion object {
        var self: Trime? = null

        @JvmStatic
        fun getService(): Trime {
            return self ?: throw IllegalStateException("Trime not initialized")
        }

        fun getServiceOrNull(): Trime? {
            return self
        }

        private val syncBackgroundHandler =
            Handler(
                Looper.getMainLooper(),
            ) { msg: Message ->
                // 若当前没有输入面板，则后台同步。防止面板关闭后5秒内再次打开
                if (!(msg.obj as Trime).isShowInputRequested) {
                    ShortcutUtils.syncInBackground()
                    (msg.obj as Trime).loadConfig()
                }
                false
            }
    }
}