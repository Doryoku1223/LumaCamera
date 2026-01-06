package com.luma.camera.presentation.viewmodel

import com.luma.camera.camera.CameraController
import com.luma.camera.camera.MultiCameraManager
import com.luma.camera.data.repository.SettingsRepository
import com.luma.camera.domain.model.AspectRatio
import com.luma.camera.domain.model.CameraMode
import com.luma.camera.domain.model.CameraSettings
import com.luma.camera.domain.model.FlashMode
import com.luma.camera.domain.model.FocalLength
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.lut.LutManager
import com.luma.camera.utils.HapticFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * CameraViewModel 单元测试
 *
 * 测试覆盖:
 * - 相机模式切换
 * - 焦段切换
 * - 闪光灯模式
 * - LUT 滤镜选择
 * - 手动参数调整
 * - 拍照流程
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var cameraController: CameraController

    @Mock
    private lateinit var multiCameraManager: MultiCameraManager

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    @Mock
    private lateinit var lutManager: LutManager

    @Mock
    private lateinit var hapticFeedback: HapticFeedback

    private lateinit var viewModel: CameraViewModel

    private val testSettings = CameraSettings()
    private val testLutFilters = MutableStateFlow<List<LutFilter>>(emptyList())
    private val testCurrentLut = MutableStateFlow<LutFilter?>(null)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        // 配置 Mock 行为
        whenever(settingsRepository.settings).thenReturn(flowOf(testSettings))
        whenever(lutManager.lutFilters).thenReturn(testLutFilters)
        whenever(lutManager.currentLut).thenReturn(testCurrentLut)
        whenever(multiCameraManager.getAvailableFocalLengths()).thenReturn(
            listOf(FocalLength.ULTRA_WIDE, FocalLength.MAIN, FocalLength.TELE, FocalLength.PERISCOPE)
        )

        viewModel = CameraViewModel(
            cameraController = cameraController,
            multiCameraManager = multiCameraManager,
            settingsRepository = settingsRepository,
            lutManager = lutManager,
            hapticFeedback = hapticFeedback
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== 模式切换测试 ====================

    @Test
    fun `初始模式为 AUTO`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(CameraMode.AUTO, viewModel.cameraState.value.mode)
    }

    @Test
    fun `切换到 PRO 模式更新状态`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setMode(CameraMode.PRO)

        assertEquals(CameraMode.PRO, viewModel.cameraState.value.mode)
        verify(hapticFeedback).click()
    }

    @Test
    fun `切换模式触发触觉反馈`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.switchMode(CameraMode.VIDEO)

        verify(hapticFeedback).click()
    }

    @Test
    fun `toggleMode 在 AUTO 和 PRO 之间切换`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        // AUTO -> PRO
        viewModel.toggleMode()
        assertEquals(CameraMode.PRO, viewModel.cameraState.value.mode)

        // PRO -> AUTO
        viewModel.toggleMode()
        assertEquals(CameraMode.AUTO, viewModel.cameraState.value.mode)
    }

    // ==================== 焦段切换测试 ====================

    @Test
    fun `初始焦段为主摄`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(FocalLength.MAIN, viewModel.cameraState.value.focalLength)
    }

    @Test
    fun `切换焦段更新状态`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setFocalLength(FocalLength.TELE)

        assertEquals(FocalLength.TELE, viewModel.cameraState.value.focalLength)
        verify(hapticFeedback).click()
    }

    @Test
    fun `切换到超广角焦段`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.switchFocalLength(FocalLength.ULTRA_WIDE)

        assertEquals(FocalLength.ULTRA_WIDE, viewModel.cameraState.value.focalLength)
    }

    @Test
    fun `切换到潜望长焦`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.switchFocalLength(FocalLength.PERISCOPE)

        assertEquals(FocalLength.PERISCOPE, viewModel.cameraState.value.focalLength)
    }

    // ==================== 闪光灯测试 ====================

    @Test
    fun `初始闪光灯为关闭`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(FlashMode.OFF, viewModel.cameraState.value.flashMode)
    }

    @Test
    fun `设置闪光灯模式`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setFlashMode(FlashMode.AUTO)

        assertEquals(FlashMode.AUTO, viewModel.cameraState.value.flashMode)
        verify(hapticFeedback).tick()
    }

    @Test
    fun `循环切换闪光灯模式`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        // OFF -> AUTO
        viewModel.cycleFlashMode()
        assertEquals(FlashMode.AUTO, viewModel.cameraState.value.flashMode)

        // AUTO -> ON
        viewModel.cycleFlashMode()
        assertEquals(FlashMode.ON, viewModel.cameraState.value.flashMode)

        // ON -> TORCH
        viewModel.cycleFlashMode()
        assertEquals(FlashMode.TORCH, viewModel.cameraState.value.flashMode)

        // TORCH -> OFF
        viewModel.cycleFlashMode()
        assertEquals(FlashMode.OFF, viewModel.cameraState.value.flashMode)
    }

    // ==================== 画面比例测试 ====================

    @Test
    fun `设置画面比例`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAspectRatio(AspectRatio.RATIO_1_1)

        assertEquals(AspectRatio.RATIO_1_1, viewModel.cameraState.value.aspectRatio)
        verify(hapticFeedback).tick()
    }

    // ==================== Live Photo 测试 ====================

    @Test
    fun `切换 Live Photo 状态`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.cameraState.value.isLivePhotoEnabled)

        viewModel.toggleLivePhoto()
        assertTrue(viewModel.cameraState.value.isLivePhotoEnabled)

        viewModel.toggleLivePhoto()
        assertFalse(viewModel.cameraState.value.isLivePhotoEnabled)
    }

    // ==================== LUT 滤镜测试 ====================

    @Test
    fun `选择 LUT 滤镜更新状态`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectLut("cinematic_01")

        assertEquals("cinematic_01", viewModel.cameraState.value.currentLutId)
        verify(lutManager).selectLut("cinematic_01")
        verify(hapticFeedback).tick()
    }

    @Test
    fun `清除 LUT 选择`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectLut("cinematic_01")
        viewModel.selectLut(null as String?)

        assertNull(viewModel.cameraState.value.currentLutId)
    }

    @Test
    fun `设置 LUT 强度`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setLutIntensity(75)

        assertEquals(75, viewModel.cameraState.value.lutIntensity)
    }

    @Test
    fun `设置 LUT 强度使用 Float`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setLutIntensity(0.8f)

        assertEquals(0, viewModel.cameraState.value.lutIntensity)
    }

    @Test
    fun `滤镜面板开关`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isFilterPanelOpen.value)

        viewModel.openFilterPanel()
        assertTrue(viewModel.isFilterPanelOpen.value)

        viewModel.closeFilterPanel()
        assertFalse(viewModel.isFilterPanelOpen.value)
    }

    // ==================== Pro 模式参数测试 ====================

    @Test
    fun `设置曝光补偿`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setExposureCompensation(1.5f)

        assertEquals(1.5f, viewModel.cameraState.value.manualParameters.exposureCompensation)
    }

    @Test
    fun `设置 ISO`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setIso(400)

        assertEquals(400, viewModel.cameraState.value.manualParameters.iso)
    }

    @Test
    fun `设置快门速度`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setShutterSpeed(1.0 / 60.0)

        assertEquals(1.0 / 60.0, viewModel.cameraState.value.manualParameters.shutterSpeed!!, 0.0001)
    }

    @Test
    fun `设置对焦距离`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setFocusDistance(0.5f)

        assertEquals(0.5f, viewModel.cameraState.value.manualParameters.focusDistance!!, 0.0001f)
    }

    @Test
    fun `切换 AE 锁定`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.cameraState.value.manualParameters.isAeLocked)

        viewModel.toggleAeLock()
        assertTrue(viewModel.cameraState.value.manualParameters.isAeLocked)
        verify(hapticFeedback).doubleClick()
    }

    @Test
    fun `切换 AF 锁定`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.cameraState.value.manualParameters.isAfLocked)

        viewModel.toggleAfLock()
        assertTrue(viewModel.cameraState.value.manualParameters.isAfLocked)
    }

    // ==================== 触摸对焦测试 ====================

    @Test
    fun `触摸对焦触发 Controller`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onTouchFocus(0.5f, 0.5f)

        verify(cameraController).triggerFocus(0.5f, 0.5f)
        verify(hapticFeedback).tick()
    }

    @Test
    fun `长按锁定 AE 和 AF`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onLongPress(0.5f, 0.5f)

        assertTrue(viewModel.cameraState.value.manualParameters.isAeLocked)
        assertTrue(viewModel.cameraState.value.manualParameters.isAfLocked)
    }
}
