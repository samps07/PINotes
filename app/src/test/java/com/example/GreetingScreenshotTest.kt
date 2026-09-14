package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val note = com.example.data.Note(
      id = 1,
      title = "Ideas for my next project",
      content = "1. Build a simple notes app with Room DB\n2. Add custom themes and colors\n3. Implement search & filter.",
      category = "Ideas",
      colorIndex = 3,
      isPinned = true
    )
    composeTestRule.setContent {
      MyApplicationTheme {
        NoteCard(
          note = note,
          onEdit = {},
          onDelete = {},
          onTogglePin = {},
          onPushNotification = {},
          onUpdate = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
