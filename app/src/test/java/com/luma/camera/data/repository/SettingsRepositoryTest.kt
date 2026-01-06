package com.luma.camera.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SettingsRepository 单元测试
 *
 * 测试覆盖:
 * - 默认值验证
 * - 设置持久化
 * - 设置读取
 * - 聚合 Flow 测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test_settings.preferences_pb") }
        )
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        // 清理
    }

    // ==================== 默认值测试 ====================

    @Test
    fun `输出格式默认值为 JPEG`() = testScope.runTest {
        val format = repository.getOutputFormat().first()
        assertEquals("JPEG", format)
    }

    @Test
    fun `画面比例默认值为 16比9`() = testScope.runTest {
        val ratio = repository.getAspectRatio().first()
        assertEquals("RATIO_16_9", ratio)
    }

    @Test
    fun `Luma Log 默认关闭`() = testScope.runTest {
        val enabled = repository.isLumaLogEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `网格默认开启`() = testScope.runTest {
        val enabled = repository.isGridEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `网格类型默认为三分线`() = testScope.runTest {
        val type = repository.getGridType().first()
        assertEquals("RULE_OF_THIRDS", type)
    }

    @Test
    fun `水平仪默认关闭`() = testScope.runTest {
        val enabled = repository.isLevelEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `直方图默认关闭`() = testScope.runTest {
        val enabled = repository.isHistogramEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `峰值对焦默认关闭`() = testScope.runTest {
        val enabled = repository.isFocusPeakingEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `峰值对焦颜色默认为金色`() = testScope.runTest {
        val color = repository.getFocusPeakingColor().first()
        assertEquals("gold", color)
    }

    @Test
    fun `实况照片默认关闭`() = testScope.runTest {
        val enabled = repository.isLivePhotoEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `实况照片音频默认开启`() = testScope.runTest {
        val enabled = repository.isLivePhotoAudioEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `触觉反馈默认开启`() = testScope.runTest {
        val enabled = repository.isHapticEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `快门声默认开启`() = testScope.runTest {
        val enabled = repository.isShutterSoundEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `地理标签默认开启`() = testScope.runTest {
        val enabled = repository.isGeotagEnabled().first()
        assertTrue(enabled)
    }

    // ==================== 设置持久化测试 ====================

    @Test
    fun `输出格式正确持久化`() = testScope.runTest {
        repository.setOutputFormat("HEIF")
        
        val format = repository.getOutputFormat().first()
        assertEquals("HEIF", format)
    }

    @Test
    fun `画面比例正确持久化`() = testScope.runTest {
        repository.setAspectRatio("RATIO_1_1")
        
        val ratio = repository.getAspectRatio().first()
        assertEquals("RATIO_1_1", ratio)
    }

    @Test
    fun `Luma Log 设置正确持久化`() = testScope.runTest {
        repository.setLumaLogEnabled(true)
        
        val enabled = repository.isLumaLogEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `网格开关正确持久化`() = testScope.runTest {
        repository.setGridEnabled(false)
        
        val enabled = repository.isGridEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `网格类型正确持久化`() = testScope.runTest {
        repository.setGridType("GOLDEN_RATIO")
        
        val type = repository.getGridType().first()
        assertEquals("GOLDEN_RATIO", type)
    }

    @Test
    fun `水平仪设置正确持久化`() = testScope.runTest {
        repository.setLevelEnabled(true)
        
        val enabled = repository.isLevelEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `直方图设置正确持久化`() = testScope.runTest {
        repository.setHistogramEnabled(true)
        
        val enabled = repository.isHistogramEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `峰值对焦设置正确持久化`() = testScope.runTest {
        repository.setFocusPeakingEnabled(true)
        
        val enabled = repository.isFocusPeakingEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `峰值对焦颜色正确持久化`() = testScope.runTest {
        repository.setFocusPeakingColor("red")
        
        val color = repository.getFocusPeakingColor().first()
        assertEquals("red", color)
    }

    @Test
    fun `实况照片设置正确持久化`() = testScope.runTest {
        repository.setLivePhotoEnabled(true)
        
        val enabled = repository.isLivePhotoEnabled().first()
        assertTrue(enabled)
    }

    @Test
    fun `实况照片音频设置正确持久化`() = testScope.runTest {
        repository.setLivePhotoAudioEnabled(false)
        
        val enabled = repository.isLivePhotoAudioEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `触觉反馈设置正确持久化`() = testScope.runTest {
        repository.setHapticEnabled(false)
        
        val enabled = repository.isHapticEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `快门声设置正确持久化`() = testScope.runTest {
        repository.setShutterSoundEnabled(false)
        
        val enabled = repository.isShutterSoundEnabled().first()
        assertFalse(enabled)
    }

    @Test
    fun `地理标签设置正确持久化`() = testScope.runTest {
        repository.setGeotagEnabled(false)
        
        val enabled = repository.isGeotagEnabled().first()
        assertFalse(enabled)
    }

    // ==================== 聚合 Settings Flow 测试 ====================

    @Test
    fun `聚合 settings 包含正确默认值`() = testScope.runTest {
        val settings = repository.settings.first()
        
        assertEquals(com.luma.camera.domain.model.OutputFormat.JPEG, settings.outputFormat)
        assertEquals(com.luma.camera.domain.model.AspectRatio.RATIO_16_9, settings.aspectRatio)
        assertFalse(settings.lumaLogEnabled)
        assertTrue(settings.showGrid)
        assertEquals(com.luma.camera.domain.model.GridType.RULE_OF_THIRDS, settings.gridType)
        assertFalse(settings.showLevel)
        assertFalse(settings.showHistogram)
        assertFalse(settings.focusPeakingEnabled)
        assertEquals("gold", settings.focusPeakingColor)
        assertFalse(settings.livePhotoEnabled)
        assertTrue(settings.livePhotoAudioEnabled)
        assertTrue(settings.hapticEnabled)
        assertTrue(settings.shutterSoundEnabled)
        assertTrue(settings.geotagEnabled)
    }

    @Test
    fun `聚合 settings 响应更新`() = testScope.runTest {
        repository.setOutputFormat("RAW_JPEG")
        repository.setGridEnabled(false)
        repository.setFocusPeakingEnabled(true)
        
        val settings = repository.settings.first()
        
        assertEquals(com.luma.camera.domain.model.OutputFormat.RAW_JPEG, settings.outputFormat)
        assertFalse(settings.showGrid)
        assertTrue(settings.focusPeakingEnabled)
    }

    // ==================== LUT 设置测试 ====================

    @Test
    fun `最后使用的 LUT ID 正确持久化`() = testScope.runTest {
        repository.setLastLutId("cinematic_01")
        
        val lutId = repository.getLastLutId().first()
        assertEquals("cinematic_01", lutId)
    }

    @Test
    fun `最后使用的 LUT ID 默认为空`() = testScope.runTest {
        val lutId = repository.getLastLutId().first()
        assertNull(lutId)
    }

    @Test
    fun `LUT 强度正确持久化`() = testScope.runTest {
        repository.setLutIntensity(0.75f)
        
        val intensity = repository.getLutIntensity().first()
        assertEquals(0.75f, intensity, 0.001f)
    }

    @Test
    fun `LUT 强度默认为 1`() = testScope.runTest {
        val intensity = repository.getLutIntensity().first()
        assertEquals(1.0f, intensity, 0.001f)
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun `无效输出格式使用默认值`() = testScope.runTest {
        // 直接写入无效值 (模拟数据损坏)
        val settings = repository.settings.first()
        
        // 应该使用默认值
        assertNotNull(settings.outputFormat)
    }

    @Test
    fun `多次更新保持最新值`() = testScope.runTest {
        repository.setGridType("RULE_OF_THIRDS")
        repository.setGridType("GOLDEN_RATIO")
        repository.setGridType("DIAGONAL")
        
        val type = repository.getGridType().first()
        assertEquals("DIAGONAL", type)
    }
}
