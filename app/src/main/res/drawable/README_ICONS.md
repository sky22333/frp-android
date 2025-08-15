# FRP Android 应用图标说明

## 图标设计理念

本应用使用基于Router图标的设计，完美契合内网穿透(FRP)的功能定位。

## 图标文件说明

### 应用启动图标 (Adaptive Icons)
- `ic_launcher_background_router.xml` - 渐变背景，使用应用主题色 (#6750A4 → #8E7CC3)
- `ic_launcher_foreground_router.xml` - Router前景图标，白色设计，包含天线和指示灯
- `ic_router_simple.xml` - 简化版Router图标，备用设计

### 通知图标
- `ic_notification.xml` - 单色Router图标，用于系统通知栏

## 设计特点

1. **功能语义**: Router图标天然代表网络转发功能，与FRP内网穿透完美匹配
2. **视觉一致性**: 与应用内部使用的Icons.Default.Router保持一致
3. **现代化设计**: 符合Material Design 3规范
4. **品牌色彩**: 使用应用主题的紫色渐变
5. **纯Vector设计**: 完全基于Vector Drawable，支持任意缩放

## 兼容性策略

- **Android 8.0+ (API 26+)**: 使用Adaptive Icons，显示自定义Router图标
- **Android 6.0-7.1 (API 23-25)**: 显示系统默认图标 (已移除旧PNG图标)
- **现代化导向**: 专注于为主流用户提供最佳体验

## 技术优势

- **APK体积优化**: 移除了冗余的PNG图标文件
- **维护简化**: 只需维护Vector Drawable文件
- **高清适配**: Vector格式在所有屏幕密度下都完美显示
- **主题兼容**: 支持浅色/深色主题自动适配

## 颜色规范

- 主色: #6750A4 (GradientStart)
- 辅色: #8E7CC3 (GradientEnd)  
- 前景: #FFFFFF (白色)
- 指示灯: 绿色(#4CAF50), 橙色(#FF9800), 蓝色(#2196F3), 红色(#F44336)