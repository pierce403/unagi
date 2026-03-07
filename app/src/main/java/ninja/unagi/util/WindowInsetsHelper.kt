package ninja.unagi.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object WindowInsetsHelper {
  fun applyToolbarInsets(toolbar: View) {
    val initialTop = toolbar.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
      val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      view.updatePadding(top = initialTop + topInset)
      insets
    }
  }

  fun applyBottomInsets(view: View) {
    val initialBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
      val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
      target.updatePadding(bottom = initialBottom + bottomInset)
      insets
    }
  }

  fun requestApplyInsets(view: View) {
    ViewCompat.requestApplyInsets(view)
  }
}
