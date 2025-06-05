package com.itsaky.androidide.ui

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log // Import Log
import android.view.Gravity // Keep this if you still try to set gravity programmatically
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.RelativeLayout
import androidx.annotation.GravityInt
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.transition.TransitionManager
import com.blankj.utilcode.util.KeyboardUtils
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.ThreadUtils.runOnUiThread
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayout.Tab
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.R // Main R class
import com.itsaky.androidide.adapters.DiagnosticsAdapter
import com.itsaky.androidide.adapters.EditorBottomSheetTabAdapter
import com.itsaky.androidide.adapters.SearchListAdapter
import com.itsaky.androidide.databinding.LayoutEditorBottomSheetBinding // Binding for this layout
import com.itsaky.androidide.fragments.output.ShareableOutputFragment
import com.itsaky.androidide.models.LogLine
import com.itsaky.androidide.resources.R.string // Aliased R.string for resources
import com.itsaky.androidide.tasks.TaskExecutor.CallbackWithError
import com.itsaky.androidide.tasks.TaskExecutor.executeAsync
import com.itsaky.androidide.tasks.TaskExecutor.executeAsyncProvideError
import com.itsaky.androidide.utils.IntentUtils.shareFile
import com.itsaky.androidide.utils.Symbols.forFile
import com.itsaky.androidide.utils.flashError
import org.slf4j.LoggerFactory // Keep slf4j for existing logs
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Callable
import kotlin.math.roundToInt

class EditorBottomSheet
@JvmOverloads
constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
  defStyleRes: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes) {

  // Keep existing slf4j logger for its original purpose
  private val slf4jLogger = LoggerFactory.getLogger(EditorBottomSheet::class.java)
  // Add Android Log Tag for new debugging
  private val DEBUG_TAG = "AutoFixDebug"


  private val collapsedHeight: Float by lazy {
    val localContext = getContext() ?: return@lazy 0f
    localContext.resources.getDimension(R.dimen.editor_sheet_collapsed_height)
  }
  private val behavior: BottomSheetBehavior<EditorBottomSheet> by lazy {
    BottomSheetBehavior.from(this).apply {
      isFitToContents = false
      skipCollapsed = true
    }
  }

  @JvmField
  var binding: LayoutEditorBottomSheetBinding
  val pagerAdapter: EditorBottomSheetTabAdapter

  private var anchorOffset = 0
  private var isImeVisible = false
  private var windowInsets: Insets? = null

  private val insetBottom: Int
    get() = if (isImeVisible) 0 else windowInsets?.bottom ?: 0

  companion object {
    // private val log = LoggerFactory.getLogger(EditorBottomSheet::class.java) // Use instance logger
    private const val COLLAPSE_HEADER_AT_OFFSET = 0.5f
    const val CHILD_HEADER = 0
    const val CHILD_SYMBOL_INPUT = 1
    const val CHILD_ACTION = 2
  }

  private fun initialize(context: FragmentActivity) {
    val mediator =
      TabLayoutMediator(binding.tabs, binding.pager, true, true) { tab, position ->
        tab.text = pagerAdapter.getTitle(position)
      }
    mediator.attach()
    binding.pager.isUserInputEnabled = false
    binding.pager.offscreenPageLimit = pagerAdapter.itemCount - 1

    binding.tabs.addOnTabSelectedListener(
      object : OnTabSelectedListener {
        override fun onTabSelected(tab: Tab) {
          val fragment: Fragment = pagerAdapter.getFragmentAtIndex(tab.position)
          if (fragment is ShareableOutputFragment) {
            binding.clearFab.show()
            binding.shareOutputFab.show()
          } else {
            binding.clearFab.hide()
            binding.shareOutputFab.hide()
          }
        }
        override fun onTabUnselected(tab: Tab) {}
        override fun onTabReselected(tab: Tab) {}
      }
    )

    binding.shareOutputFab.setOnClickListener {
      val fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)
      if (fragment !is ShareableOutputFragment) {
        slf4jLogger.error("Unknown fragment: {}", fragment)
        return@setOnClickListener
      }
      val filename = fragment.getFilename()
      @Suppress("DEPRECATION")
      val progress = android.app.ProgressDialog.show(getContext(), null, getContext().getString(string.please_wait))
      executeAsync(fragment::getContent) {
        progress.dismiss()
        shareText(it, filename)
      }
    }
    TooltipCompat.setTooltipText(binding.clearFab, context.getString(string.title_clear_output))
    binding.clearFab.setOnClickListener {
      val fragment: Fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)
      if (fragment !is ShareableOutputFragment) {
        slf4jLogger.error("Unknown fragment: {}", fragment)
        return@setOnClickListener
      }
      (fragment as ShareableOutputFragment).clearOutput()
    }
    binding.headerContainer.setOnClickListener {
      if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
      }
    }

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      this.windowInsets = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
      insets
    }
  }

  init {
    Log.d(DEBUG_TAG, "EditorBottomSheet: init called")
    if (context !is FragmentActivity) {
      throw IllegalArgumentException("EditorBottomSheet must be set up with a FragmentActivity")
    }
    val inflater = LayoutInflater.from(context)
    binding = LayoutEditorBottomSheetBinding.inflate(inflater, this, true)
    pagerAdapter = EditorBottomSheetTabAdapter(context)
    binding.pager.adapter = pagerAdapter
    initialize(context)
    Log.d(DEBUG_TAG, "EditorBottomSheet: init finished. Binding.autoFixIndicatorIconDirect is null: ${binding.autoFixIndicatorIconDirect == null}")
  }

  fun setImeVisible(isVisible: Boolean) {
    isImeVisible = isVisible
    behavior.isGestureInsetBottomIgnored = isVisible
  }

  fun setOffsetAnchor(view: View) {
    val listener =
      object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
          view.viewTreeObserver.removeOnGlobalLayoutListener(this)
          anchorOffset = view.height + SizeUtils.dp2px(1f)
          behavior.peekHeight = collapsedHeight.roundToInt()
          behavior.expandedOffset = anchorOffset
          behavior.isGestureInsetBottomIgnored = isImeVisible
          binding.root.updatePadding(bottom = anchorOffset + insetBottom)
          binding.headerContainer.apply {
            updatePaddingRelative(bottom = paddingBottom + insetBottom)
            updateLayoutParams<ViewGroup.LayoutParams> {
              height = (collapsedHeight + insetBottom).roundToInt()
            }
          }
        }
      }
    view.viewTreeObserver.addOnGlobalLayoutListener(listener)
  }

  fun onSlide(sheetOffset: Float) {
    val heightScale = if (sheetOffset >= COLLAPSE_HEADER_AT_OFFSET) {
      ((COLLAPSE_HEADER_AT_OFFSET - sheetOffset) + COLLAPSE_HEADER_AT_OFFSET) * 2f
    } else {
      1f
    }
    val paddingScale = if (!isImeVisible && sheetOffset <= COLLAPSE_HEADER_AT_OFFSET) {
      ((1f - sheetOffset) * 2f) - 1f
    } else {
      0f
    }
    val padding = insetBottom * paddingScale
    binding.headerContainer.apply {
      updateLayoutParams<ViewGroup.LayoutParams> {
        height = ((collapsedHeight + padding) * heightScale).roundToInt()
      }
      updatePaddingRelative(
        bottom = padding.roundToInt()
      )
    }
  }

  fun showChild(index: Int) {
    binding.headerContainer.displayedChild = index
  }

  fun setActionText(text: CharSequence) {
    binding.bottomAction.actionText.text = text
  }

  fun setActionProgress(progress: Int) {
    binding.bottomAction.progress.setProgressCompat(progress, true)
  }

  fun appendApkLog(line: LogLine) {
    pagerAdapter.logFragment?.appendLog(line)
  }

  fun appendBuildOut(str: String?) {
    pagerAdapter.buildOutputFragment?.appendOutput(str)
  }

  fun clearBuildOutput() {
    pagerAdapter.buildOutputFragment?.clearOutput()
  }

  fun handleDiagnosticsResultVisibility(errorVisible: Boolean) {
    runOnUiThread { pagerAdapter.diagnosticsFragment?.isEmpty = errorVisible }
  }

  fun handleSearchResultVisibility(errorVisible: Boolean) {
    runOnUiThread { pagerAdapter.searchResultFragment?.isEmpty = errorVisible }
  }

  fun setDiagnosticsAdapter(adapter: DiagnosticsAdapter) {
    runOnUiThread { pagerAdapter.diagnosticsFragment?.setAdapter(adapter) }
  }

  fun setSearchResultAdapter(adapter: SearchListAdapter) {
    runOnUiThread { pagerAdapter.searchResultFragment?.setAdapter(adapter) }
  }

  fun refreshSymbolInput(editor: CodeEditorView) {
    binding.symbolInput.refresh(editor.editor, forFile(editor.file))
  }

  fun onSoftInputChanged() {
    if (context !is Activity) {
      slf4jLogger.error("Bottom sheet is not attached to an activity!")
      return
    }
    binding.symbolInput.itemAnimator?.endAnimations()
    TransitionManager.beginDelayedTransition(
      binding.root,
      MaterialSharedAxis(MaterialSharedAxis.Y, false)
    )
    val activity = context as Activity
    if (KeyboardUtils.isSoftInputVisible(activity)) {
      binding.headerContainer.displayedChild = CHILD_SYMBOL_INPUT
    } else {
      binding.headerContainer.displayedChild = CHILD_HEADER
    }
  }

  fun setStatus(text: CharSequence, @GravityInt gravity: Int) {
    runOnUiThread {
      try {
        Log.d(DEBUG_TAG, "EditorBottomSheet: setStatus called with text: '$text'")
        binding.generalStatusTextDirect.textAlignment = when(gravity) {
          Gravity.START, Gravity.LEFT -> View.TEXT_ALIGNMENT_VIEW_START
          Gravity.END, Gravity.RIGHT -> View.TEXT_ALIGNMENT_VIEW_END
          Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> View.TEXT_ALIGNMENT_CENTER
          else -> View.TEXT_ALIGNMENT_INHERIT
        }
        binding.generalStatusTextDirect.text = text
      } catch (e: Exception) {
        Log.e(DEBUG_TAG, "EditorBottomSheet: Error accessing binding.generalStatusTextDirect. " +
                "Ensure ID 'general_status_text_direct' exists in layout_editor_bottom_sheet.xml. Error: ${e.message}", e)
      }
    }
  }

  fun setAutoFixIndicatorVisibility(isVisible: Boolean) {
    Log.d(DEBUG_TAG, "EditorBottomSheet: setAutoFixIndicatorVisibility called with isVisible: $isVisible")
    runOnUiThread {
      val targetVisibility = if (isVisible) View.VISIBLE else View.GONE
      Log.d(DEBUG_TAG, "EditorBottomSheet: Target visibility for indicator: ${if(targetVisibility == View.VISIBLE) "VISIBLE" else "GONE"}")
      try {
        // binding is not lateinit, so it's guaranteed to be initialized if this method is callable on an instance.
        // The null checks for individual views are more for robustness during development/debugging.
        if (binding.autoFixIndicatorIconDirect == null) {
          Log.e(DEBUG_TAG, "EditorBottomSheet: binding.autoFixIndicatorIconDirect IS NULL!")
        }
        if (binding.autoFixIndicatorTextDirect == null) {
          Log.e(DEBUG_TAG, "EditorBottomSheet: binding.autoFixIndicatorTextDirect IS NULL!")
        }

        binding.autoFixIndicatorIconDirect.visibility = targetVisibility
        binding.autoFixIndicatorTextDirect.visibility = targetVisibility
        Log.i(DEBUG_TAG, "EditorBottomSheet: Auto-fix indicator visibility set to ${if(isVisible) "VISIBLE" else "GONE"}")

      } catch (e: Exception) {
        // This catch is still important in case the views themselves are somehow null
        // (e.g., if the IDs in XML were mistyped and View Binding didn't generate the properties,
        // though that usually causes a compile error for unresolved reference earlier).
        // Or if setting visibility on a null view occurs due to some race condition (unlikely here).
        Log.e(DEBUG_TAG, "EditorBottomSheet: Error setting direct auto-fix indicator visibility. " +
                "Ensure IDs 'auto_fix_indicator_icon_direct' & 'auto_fix_indicator_text_direct' exist. Error: ${e.message}", e)
      }
    }
  }

  private fun shareFile(file: File) {
    shareFile(context, file, "text/plain")
  }

  @Suppress("DEPRECATION")
  private fun shareText(text: String?, type: String) {
    if (text == null || TextUtils.isEmpty(text)) {
      flashError(context.getString(string.msg_output_text_extraction_failed))
      return
    }
    val pd = android.app.ProgressDialog.show(context, null, context.getString(string.please_wait), true, false)
    executeAsyncProvideError(
      Callable { writeTempFile(text, type) },
      CallbackWithError<File> { result: File?, error: Throwable? ->
        pd.dismiss()
        if (result == null || error != null) {
          slf4jLogger.warn("Unable to share output", error)
          return@CallbackWithError
        }
        shareFile(result)
      }
    )
  }

  private fun writeTempFile(text: String, type: String): File {
    val file: Path = context.filesDir.toPath().resolve("$type.txt")
    try {
      if (Files.exists(file)) {
        Files.delete(file)
      }
      Files.write(file, text.toByteArray(StandardCharsets.UTF_8), CREATE_NEW, WRITE)
    } catch (e: IOException) {
      slf4jLogger.error("Unable to write output to file", e)
    }
    return file.toFile()
  }
}