/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.utils

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.common.databinding.LayoutDialogProgressBinding
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.resources.R.style
import org.jetbrains.annotations.Contract
import java.util.concurrent.atomic.AtomicInteger

/**
 * Utility class for creating dialogs.
 *
 * @author Akash Yadav
 */
object DialogUtils {

  @JvmStatic
  @JvmOverloads
  fun newProgressDialog(
    context: Context,
    title: String,
    message: String? = null,
    cancelable: Boolean = false,
    onCancelClick: DialogInterface.OnClickListener? = null
  ): MaterialAlertDialogBuilder {
    val binding = LayoutDialogProgressBinding.inflate(LayoutInflater.from(context))
    val builder = newMaterialDialogBuilder(context)
    builder.setTitle(title)
    builder.setView(binding.root)
    builder.setCancelable(cancelable)

    if (message != null) {
      binding.message.text = message
      binding.message.visibility = View.VISIBLE
    }

    if (onCancelClick != null) {
      builder.setPositiveButton(android.R.string.cancel) { dialog, which ->
        dialog.dismiss()
        onCancelClick.onClick(dialog, which)
      }
    }

    return builder
  }

  /**
   * Create a new alert dialog with two buttons: <span>Yes</span> and <span>No</span>. This method
   * simply calls [.newYesNoDialog] with default values for title and message.
   *
   * @param context The context for the dialog.
   * @param positiveClickListener A listener that will be invoked on the <span>Yes</span> button
   * click.
   * @param negativeClickListener A listener that will be invoked on the <span>No</span> button
   * click.
   * @return The newly created dialog.
   */
  @JvmStatic
  @JvmOverloads
  fun newYesNoDialog(
    context: Context,
    positiveClickListener: DialogInterface.OnClickListener? = null,
    negativeClickListener: DialogInterface.OnClickListener? = null
  ): MaterialAlertDialogBuilder {
    return newYesNoDialog(
      context,
      context.getString(string.msg_yesno_def_title),
      context.getString(string.msg_yesno_def_message),
      positiveClickListener,
      negativeClickListener
    )
  }

  /**
   * Create a new alert dialog with two buttons: <span>Yes</span> and <span>No</span>.
   *
   * @param context The context for the dialog.
   * @param title The title of the dialog.
   * @param message The message of the dialog.
   * @param positiveClickListener A listener that will be invoked on the <span>Yes</span> button
   * click.
   * @param negativeClickListener A listener that will be invoked on the <span>No</span> button
   * click.
   * @return The newly created dialog instance.
   */
  @JvmStatic
  @JvmOverloads
  fun newYesNoDialog(
    context: Context,
    title: String,
    message: String? = null,
    positiveClickListener: DialogInterface.OnClickListener? = null,
    negativeClickListener: DialogInterface.OnClickListener? = null
  ): MaterialAlertDialogBuilder {
    val builder = newMaterialDialogBuilder(context)
    builder.setTitle(title)
    builder.setMessage(message)
    builder.setPositiveButton(string.yes, positiveClickListener)
    builder.setNegativeButton(string.no, negativeClickListener)
    return builder
  }

  /**
   * Creates a new MaterialAlertDialogBuilder with the app's default style.
   *
   * @param context The context for the dialog builder.
   * @return The new MaterialAlertDialogBuilder instance.
   */
  @JvmStatic
  @Contract("_ -> new")
  fun newMaterialDialogBuilder(context: Context): MaterialAlertDialogBuilder {
    return MaterialAlertDialogBuilder(context, style.AppTheme_MaterialAlertDialog)
  }

  /**
   * Creates a new single-choice material alert dialog builder.
   *
   * @param context The context used to build the [MaterialAlertDialogBuilder].
   * @param title The title for the dialog.
   * @param choices The choices for the dialog.
   * @param checkedChoice The index of the item which should be checked.
   * @param cancelable Whether the dialog should be cancellable or not.
   * @param onSelected The function which will be called once the user confirms the selection.
   */
  @JvmStatic
  fun newSingleChoiceDialog(
    context: Context,
    title: String,
    choices: Array<CharSequence>,
    checkedChoice: Int,
    cancelable: Boolean = false,
    onSelected: (Int) -> Unit
  ): MaterialAlertDialogBuilder {
    val selection = AtomicInteger(checkedChoice)
    return newMaterialDialogBuilder(context)
      .setTitle(title)
      .setSingleChoiceItems(choices, checkedChoice) { _, which ->
        selection.set(which)
      }
      .setPositiveButton(android.R.string.ok) { dialog, _ ->
        dialog.dismiss()
        onSelected(selection.get())
      }
      .setNegativeButton(android.R.string.cancel, null)
      .setCancelable(cancelable)
  }

  /**
   * Creates a new alert dialog with a custom message and specified button texts.
   *
   * @param context The context for the dialog.
   * @param title The title of the dialog.
   * @param message The message to display in the dialog.
   * @param positiveButtonText Text for the positive button.
   * @param positiveClickListener A listener that will be invoked on the positive button click.
   * @param negativeButtonText Text for the negative button.
   * @param negativeClickListener A listener that will be invoked on the negative button click (optional).
   * @param neutralButtonText Text for the neutral button (optional).
   * @param neutralClickListener A listener that will be invoked on the neutral button click (optional).
   * @param cancelable Whether the dialog is cancelable.
   * @return The newly created dialog builder.
   */
  @JvmStatic
  @JvmOverloads
  fun newCustomMessageDialog(
    context: Context,
    title: String,
    message: String?,
    positiveButtonText: String,
    positiveClickListener: DialogInterface.OnClickListener,
    negativeButtonText: String? = null,
    negativeClickListener: DialogInterface.OnClickListener? = null,
    neutralButtonText: String? = null,
    neutralClickListener: DialogInterface.OnClickListener? = null,
    cancelable: Boolean = true // Default to true
  ): MaterialAlertDialogBuilder {
    val builder = newMaterialDialogBuilder(context)
    builder.setTitle(title)
    builder.setMessage(message)
    builder.setPositiveButton(positiveButtonText, positiveClickListener)

    negativeButtonText?.let {
      builder.setNegativeButton(it, negativeClickListener)
    }

    neutralButtonText?.let {
      builder.setNeutralButton(it, neutralClickListener)
    }
    builder.setCancelable(cancelable)
    return builder
  }
}