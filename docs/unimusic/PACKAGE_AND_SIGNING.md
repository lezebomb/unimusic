# 包标识与签名检查点

当前开发包为 `moe.ouom.neriplayer.debug`，显示名为 `UniMusic DEBUG`；普通名称资源为 `UniMusic`。本轮只更新显示名称、启动图标与源码入口，没有修改applicationId、签名配置、数据库版本或凭据存储键。

Mate60已实际保留数据覆盖安装Debug APK。Release APK未构建/签发，不存在本轮发布包或可验证的新发布证书。后续包名/签名方案应独立设计迁移和验收，不能把本轮显示名更新当作已完成包名或签名迁移。构建版本含真实基础Git修订，dirty代表工作树构建；实际APK与哈希以各阶段证据为准。
