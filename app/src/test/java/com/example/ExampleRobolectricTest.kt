package com.example

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.TableListNoteComponent
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PINotes", appName)
  }

  @Test
  fun test_add_column_and_input() {
    composeTestRule.setContent {
      MyApplicationTheme {
        val contentState = remember { mutableStateOf("[ ] First Item") }
        TableListNoteComponent(
          content = contentState.value,
          onContentChange = { contentState.value = it },
          isEditMode = true
        )
      }
    }

    // Click Add Column
    composeTestRule.onNodeWithTag("add_column_button").performClick()
    composeTestRule.waitForIdle()

    // Click on the second column input and type text
    composeTestRule.onNodeWithTag("task_input_0_1").performClick()
    composeTestRule.onNodeWithTag("task_input_0_1").performTextInput("100")
    composeTestRule.waitForIdle()
  }
}
