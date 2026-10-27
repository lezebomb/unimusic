# Phase 3D.8：GitHub独立歌曲标识同步

日期：2026-10-28。GitHub设置卡片加入与WebDAV相同的显式标识同步/冲突入口，使用已有加密仓库配置及配置代次guard。不自动开启，不读取开发电脑gh凭据，不创建仓库或改旧同步文件。

后台在固定提交读取独立`unimusic-identities-v1.json`原始字节，限8MiB；只暂存该文件的blob、基于原tree的一条更新及以原HEAD为父的commit，再复用已有GraphQL原子引用更新，以beforeOid/force=false发布。分支变化返回冲突，由既有45秒/3次协调器重读合并；没有强推、REST无条件退路或旧文件删除。暂存失败/竞争可能留下未引用Git对象，不会提前改变可见分支。

上传前仍验证portable协议，blob回执须等于本机计算的Git blob SHA-1；版本绑定API/仓库，不能跨目标使用。所有请求可取消、单请求15秒、不跟随重定向，REST固定2026-03-10版本。错误界面不展示服务器私有正文或令牌。配置适配器归入local/unified/sync子目录，符合目录文件数上限。

协议依据为[GitHub原始内容接口](https://docs.github.com/en/rest/repos/contents)、[Git blob接口](https://docs.github.com/en/rest/git/blobs)及[GraphQL updateRefs条件规则](https://docs.github.com/en/graphql/reference/git#updaterefs)：原始内容避免大文件的JSON内联限制，Git对象用base64暂存，引用使用明确旧值条件。这里只引用接口规则，没有真实账号验收。

## 实际验证

- 2项定向JVM合成HTTP检查：固定ref原始读取、base_tree/唯一文件/commit父节点、beforeOid/force=false；过期引用不强推、错误blob回执在发布前终止、异仓库版本不发请求。全部通过。
- 初始`:sync:testDebugUnitTest --tests '*UnifiedIdentityGitHubTransportTest' :app:assembleDebug --offline`成功。目录结构检查首次报告17文件超过16上限，已将两个配置适配器归类；最终`:app:assembleDebug --offline`与边界通过。未扩大或重复测试集合。
- 原始XML/最终APK哈希见[证据](evidence/2026-10-28/phase3d8/validation.json)。3D.7的[远端CI](https://github.com/lezebomb/unimusic/actions/runs/40594234079)已成功，覆盖此前新增身份关键集；3D.8远端结果另确认。
- 没有访问实际用户GitHub配置/令牌，没有真实数据上传、手机安装/UI、双设备/旧客户端往返、音频或性能验证。Room仍v23，没有完整Room远端重写或播放权同步。
