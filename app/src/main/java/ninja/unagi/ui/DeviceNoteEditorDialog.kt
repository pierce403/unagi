package ninja.unagi.ui

import android.text.InputFilter
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ninja.unagi.R
import ninja.unagi.util.DeviceNoteFormatter

object DeviceNoteEditorDialog {
  fun show(
    activity: AppCompatActivity,
    currentNote: String?,
    onSave: (String?) -> Unit
  ) {
    val input = EditText(activity).apply {
      setText(DeviceNoteFormatter.normalize(currentNote).orEmpty())
      hint = context.getString(R.string.rename_device_hint)
      filters = arrayOf(InputFilter.LengthFilter(DeviceNoteFormatter.MAX_LENGTH))
      maxLines = 1
      selectAll()
    }
    val container = FrameLayout(activity).apply {
      val margin = (16 * resources.displayMetrics.density).toInt()
      setPadding(margin, margin / 2, margin, 0)
      addView(input)
    }
    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.rename_device_title)
      .setView(container)
      .setMessage(R.string.rename_device_clear_hint)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        onSave(DeviceNoteFormatter.normalize(input.text))
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
