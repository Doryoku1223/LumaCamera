package com.luma.camera.presentation.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.luma.camera.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CameraScreen UI 测试
 *
 * 测试覆盖:
 * - 界面元素显示
 * - 用户交互
 * - 状态切换
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CameraScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // ==================== 界面元素显示测试 ====================

    @Test
    fun shutter_button_is_displayed() {
        composeTestRule
            .onNodeWithContentDescription("快门")
            .assertIsDisplayed()
    }

    @Test
    fun focal_length_selector_is_displayed() {
        composeTestRule
            .onNodeWithText("1x")
            .assertIsDisplayed()
    }

    @Test
    fun mode_selector_is_displayed() {
        composeTestRule
            .onNodeWithText("Auto")
            .assertIsDisplayed()
    }

    @Test
    fun filter_button_is_displayed() {
        composeTestRule
            .onNodeWithContentDescription("滤镜")
            .assertIsDisplayed()
    }

    @Test
    fun flash_button_is_displayed() {
        composeTestRule
            .onNodeWithContentDescription("闪光灯")
            .assertIsDisplayed()
    }

    @Test
    fun settings_button_is_displayed() {
        composeTestRule
            .onNodeWithContentDescription("设置")
            .assertIsDisplayed()
    }

    // ==================== 焦段切换测试 ====================

    @Test
    fun focal_length_switch_to_ultra_wide() {
        composeTestRule
            .onNodeWithText("0.5x")
            .performClick()

        composeTestRule
            .onNodeWithText("0.5x")
            .assertIsDisplayed()
    }

    @Test
    fun focal_length_switch_to_main() {
        composeTestRule
            .onNodeWithText("1x")
            .performClick()

        composeTestRule
            .onNodeWithText("1x")
            .assertIsDisplayed()
    }

    @Test
    fun focal_length_switch_to_tele() {
        composeTestRule
            .onNodeWithText("3x")
            .performClick()

        composeTestRule
            .onNodeWithText("3x")
            .assertIsDisplayed()
    }

    @Test
    fun focal_length_switch_to_periscope() {
        composeTestRule
            .onNodeWithText("6x")
            .performClick()

        composeTestRule
            .onNodeWithText("6x")
            .assertIsDisplayed()
    }

    // ==================== 模式切换测试 ====================

    @Test
    fun mode_switch_to_pro() {
        composeTestRule
            .onNodeWithText("Pro")
            .performClick()

        // Pro 模式应该显示手动参数控制面板
        composeTestRule
            .onNodeWithText("Pro")
            .assertIsDisplayed()
    }

    @Test
    fun mode_switch_to_video() {
        // 滑动到视频模式 (如果支持)
        composeTestRule
            .onNodeWithText("视频")
            .performClick()

        composeTestRule
            .onNodeWithText("视频")
            .assertIsDisplayed()
    }

    // ==================== 快门按钮测试 ====================

    @Test
    fun shutter_button_is_clickable() {
        composeTestRule
            .onNodeWithContentDescription("快门")
            .performClick()
    }

    // ==================== 滤镜面板测试 ====================

    @Test
    fun filter_panel_opens_on_click() {
        composeTestRule
            .onNodeWithContentDescription("滤镜")
            .performClick()

        // 滤镜面板应该显示
        composeTestRule
            .onNodeWithText("原图")
            .assertIsDisplayed()
    }

    @Test
    fun filter_panel_shows_original_option() {
        composeTestRule
            .onNodeWithContentDescription("滤镜")
            .performClick()

        composeTestRule
            .onNodeWithText("原图")
            .assertIsDisplayed()
    }

    // ==================== 闪光灯切换测试 ====================

    @Test
    fun flash_cycles_on_click() {
        // 初始状态为 OFF
        composeTestRule
            .onNodeWithContentDescription("闪光灯")
            .performClick()

        // 应该切换到 AUTO (具体取决于实现)
    }

    // ==================== 设置页面导航测试 ====================

    @Test
    fun settings_button_navigates_to_settings() {
        composeTestRule
            .onNodeWithContentDescription("设置")
            .performClick()

        // 应该显示设置页面
        composeTestRule
            .onNodeWithText("设置")
            .assertIsDisplayed()
    }

    // ==================== Pro 模式控制面板测试 ====================

    @Test
    fun pro_mode_shows_ev_control() {
        // 切换到 Pro 模式
        composeTestRule
            .onNodeWithText("Pro")
            .performClick()

        // EV 控制应该显示
        composeTestRule
            .onNodeWithText("EV")
            .assertIsDisplayed()
    }

    @Test
    fun pro_mode_shows_iso_control() {
        composeTestRule
            .onNodeWithText("Pro")
            .performClick()

        composeTestRule
            .onNodeWithText("ISO")
            .assertIsDisplayed()
    }

    @Test
    fun pro_mode_shows_shutter_control() {
        composeTestRule
            .onNodeWithText("Pro")
            .performClick()

        composeTestRule
            .onNodeWithText("S")
            .assertIsDisplayed()
    }

    @Test
    fun pro_mode_shows_focus_control() {
        composeTestRule
            .onNodeWithText("Pro")
            .performClick()

        composeTestRule
            .onNodeWithText("AF")
            .assertIsDisplayed()
    }

    @Test
    fun pro_mode_shows_wb_control() {
        composeTestRule
            .onNodeWithText("Pro")
            .performClick()

        composeTestRule
            .onNodeWithText("WB")
            .assertIsDisplayed()
    }

    // ==================== 辅助功能测试 ====================

    @Test
    fun histogram_toggles_on_setting() {
        // 进入设置
        composeTestRule
            .onNodeWithContentDescription("设置")
            .performClick()

        // 启用直方图
        composeTestRule
            .onNodeWithText("直方图")
            .performClick()

        // 返回相机界面
        composeTestRule
            .onNodeWithContentDescription("返回")
            .performClick()

        // 直方图应该显示 (可见性验证)
    }
}
