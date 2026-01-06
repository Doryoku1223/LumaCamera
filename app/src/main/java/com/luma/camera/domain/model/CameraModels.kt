package com.luma.camera.domain.model

/**
 * 相机模式
 */
enum class CameraMode {
    AUTO,   // 全自动模式
    PRO,    // 专业手动模式
    PHOTO,  // 照片模式
    VIDEO   // 视频模式
}

/**
 * 闪光灯模式
 */
enum class FlashMode {
    OFF,      // 关闭
    AUTO,     // 自动
    ON,       // 开启
    TORCH     // 常亮（手电筒）
}

/**
 * 画面比例
 */
enum class AspectRatio(val widthRatio: Int, val heightRatio: Int) {
    RATIO_4_3(4, 3),     // 4:3 默认
    RATIO_16_9(16, 9),   // 16:9
    RATIO_1_1(1, 1),     // 1:1 正方形
    RATIO_FULL(0, 0);    // 全屏
    
    companion object {
        // 为了向后兼容，FULL 作为 RATIO_FULL 的别名
        @JvmField
        val FULL = RATIO_FULL
    }
}

/**
 * 焦段/摄像头
 */
enum class FocalLength(val multiplier: Float, val displayName: String) {
    ULTRA_WIDE(0.5f, "0.5x"),   // 超广角
    WIDE(1.0f, "1x"),           // 广角/主摄
    MAIN(1.0f, "1x"),           // 主摄
    TELEPHOTO(3.0f, "3x"),      // 长焦
    TELEPHOTO_3X(3.0f, "3x"),   // 3倍长焦
    TELEPHOTO_6X(6.0f, "6x"),   // 6倍长焦
    PERISCOPE(6.0f, "6x")       // 潜望长焦
}

/**
 * 白平衡模式
 */
enum class WhiteBalanceMode(val kelvin: Int?) {
    AUTO(null),           // 自动
    DAYLIGHT(5500),       // 日光
    CLOUDY(6500),         // 阴天
    TUNGSTEN(2800),       // 白炽灯
    FLUORESCENT(4000),    // 荧光灯
    SHADE(7500),          // 阴影
    MANUAL(null),         // 手动
    CUSTOM(null)          // 自定义
}

/**
 * 输出格式
 */
enum class OutputFormat {
    JPEG,
    HEIF,
    RAW_DNG,
    RAW_JPEG
}

/**
 * 网格线类型
 */
enum class GridType {
    NONE,           // 无
    RULE_OF_THIRDS, // 三分线
    GRID_4X4,       // 4x4 网格
    GOLDEN_RATIO,   // 黄金分割
    DIAGONAL,       // 对角线
    CENTER_CROSS    // 中心十字
}

/**
 * 峰值对焦颜色
 */
enum class FocusPeakingColor {
    RED,
    YELLOW,
    BLUE,
    WHITE
}

/**
 * 直方图位置
 */
enum class HistogramPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

/**
 * 倒计时选项
 */
enum class TimerDuration(val seconds: Int) {
    OFF(0),
    SECONDS_3(3),
    SECONDS_5(5),
    SECONDS_10(10)
}

/**
 * 水印位置
 */
enum class WatermarkPosition {
    BOTTOM_LEFT,    // 左下角
    BOTTOM_CENTER,  // 底部居中
    BOTTOM_RIGHT    // 右下角
}
