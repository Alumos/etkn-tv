# ETKN Mobile：从 TV 客户端反查 HTTP 接口

基线：Fork 提交 `ca6145be666432fb506ad093655f6ec1b987c3ac`，ETKN TV Revised 0.1.131 / versionCode 132；静态核对开始于 2026-11-18T09:19:19+08:00。源码链接为同仓库路径；精确基线以该提交为准。版本及镜像配置见 [app/build.gradle](../app/build.gradle#L4-L16)。**这是客户端观察，不是服务端 OpenAPI、兼容性承诺或生产验证。** 文中的“响应”表示这版 TV 实际读取的字段；未读到处理逻辑的字段、状态码、权限和副作用不作推断。范围是运行时 HTTP(S) 请求，不含 CI 发布工作流、许可证中的 URL、系统安装器或本地广播。

## 快速导航与实施顺序

| 优先级 | 建议交付 | 路由分组 |
| --- | --- | --- |
| P0 | 手动连接及实例校验、登录/安全恢复、首页最小集合、库与搜索、详情、直接播放和进度同步 | [连接与设置](#连接登录与设置)、[首页与资料库](#首页资料库与详情)、[播放](#播放选集及增强)、[图片](#图片和服务端供给的-url) |
| P1 | 带状态的列表、收藏/已看、续播/跨季下一集、音乐/歌单/歌词、共享偏好、失效联动；若目标库依赖转码，则转码升为 P0 | [首页与资料库](#首页资料库与详情)、[播放](#播放选集及增强)、[音乐](#音乐与歌单)、[事件](#事件通知与轮询) |
| P2 | 有授权时才接入探索、订阅及确认获取；另排局域网自动发现、媒体管理、字幕翻译/偏移、弹幕、寻址缩略图、ISO 及 Mobile 自己的发布渠道 | [探索](#影视探索订阅与云资源)、[兼容与发布](#兼容发布和非后端契约) |

## 请求共性

- 简写：`U` = `/api/tv/Users/{userId}`，`I` = `/api/tv/Items/{itemId}`，`S` = `/api/tv/Shows/{seriesId}`，`D` = `/api/tv/discover`。下文的 `U/Items/{itemId}` 与 `I` **不是**同一路径。除公共探针、登录、外部资源和单独标出的传输方式外，表中 API 使用当前会话的 JSON 客户端；`{}` 是实际发出的空 JSON 对象，DELETE 无体。
- JSON：`Accept: application/json`、`User-Agent: ETKN TV/<version> (Android)`、`X-Emby-Authorization: MediaBrowser Client="ETKN TV", Device="<model>", DeviceId="<stable-id>", Version="<version>"`；有令牌时并发 `X-Emby-Token: <token>` 与 `Cookie: etkn_session=<token>`。请求体为 UTF-8 `application/json; charset=utf-8`。这是观察到的 TV 发送方式，不证明两个令牌载体都必需，也不证明 Mobile 可擅自更改客户端产品标识。
- `EtknApi.encode` 是 Java UTF-8 `URLEncoder`（空格成 `+`）；源码许多动态路径段与查询值共用它。参数名大小写和布尔值拼写以各行实际值为准；分页/游标、ID/Key/tag 按不透明值处理，避免双重编码或拼接未转义搜索输入。视频时长/进度常用 100ns tick（`1 ms = 10000 ticks`），弹幕和字幕校准/翻译使用毫秒。
- JSON 客户端不跟 3xx。先尝试解析 UTF-8 JSON：空响应成为 `{}`，以 `[` 开头的数组包装成 `Items`；列表辅助函数依次识别 `Items / results / resources / items / data`，且可递归进入这些同名对象。解析成功后的 401 才是 `AuthException`；TV 路由 404 被泛化为升级提示，**不等于据此确定服务端太旧**；其他 HTTP 错误读 `detail` 或采用状态码提示。解析失败（包括 HTML 错页）是响应异常。连接 7s、常规读 35s、封面/分类图 POST 读 180s，JSON 文本上限 8 MiB，均为 TV 客户端策略。
- 安全边界：管理员/模板权限是 TV UI 门禁，`UserId` 或用户路径不是授权凭据；服务端仍须验证。保存登录、网络重定向、用户/服务切换要独立处理；不要把密码、令牌、带凭证预签 URL 写进日志、状态上报或共享缓存。

源码：[EtknApi.java](../app/src/main/java/xyz/etkn/tv/EtknApi.java#L17-L87)（请求、编码），[EtknApi.java](../app/src/main/java/xyz/etkn/tv/EtknApi.java#L197-L206)（列表），[Http.java](../app/src/main/java/xyz/etkn/tv/Http.java#L7-L26)（图片）。

## 连接、登录与设置

| 方法 / 路径 | 请求与鉴权 | 客户端可见响应及条件 |
| --- | --- | --- |
| GET `/api/health` | 公开，严格 HTTP 200、无跳转；无查询 | `status == "ok"`。不能单独证明是 ETKN。 |
| GET `/api/tv/System/Info/Public` | 公开，探针严格 HTTP 200；登录页还用无 token 的 JSON 客户端单独读取 | 探针要求 `ProductName == "ETKN"`、`TvApiVersion == 1`；取 `ServerName, Id`。登录页仅取服务器名称，**不能**替代身份校验。 |
| GET `/api/system/tv-discovery` | 公开，原始入口在前两项通过后请求 | 要求 `ProductName == "ETKN"`；读 `MediaServerAddress, ApiPort`（未给时 TV 默认 5257）、需要重建地址时读 `MediaServerPort`。见下方实例校验。 |
| POST `/api/tv/Users/AuthenticateByName` | 不带会话 token；先 resolve，再向确认后的媒体地址发 `{Username, Pw}` | 必读 `AccessToken, User.Id`；另读 `User.Name, User.Policy.IsAdministrator, PrivateAccount`（此处 `PrivateAccount` 在顶层）。 |
| GET `/api/tv/Users/Me` | 当前 token；恢复/选择账号时也可用该账号已保存 token | `Id, Name, Policy.IsAdministrator, PrivateAccount, TvPermissions.CanRefreshMetadata, TvPermissions.CanDeleteMedia, PrimaryImageTag`；恢复时校验返回 `Id` 与待恢复 ID 一致。 |
| GET `/api/tv/auth/check_status` | 当前 token；首页、详情、音乐、探索读取 | `user.can_access_discovery`；门禁判定不能拿管理员或登录状态替代。 |
| GET / PUT `/api/tv/Users/Me/AccountPreferences` | TV 仅为非私密管理员读写；PUT `{remember_admin_account: bool}`；GET 的字段缺失或为 null 时才以 `{remember_admin_account: <本机旧值>, initialize_only: true}` 迁移 | 读 `remember_admin_account`（包括初始化 PUT 的响应）。在线查询优先，已有 false 不可覆盖；关闭只去掉持久 token，不注销当前会话；不受外观锁定影响。 |
| GET / PUT `/api/tv/Users/Me/ClientPreferences` | GET 当前账号；写为 GET → PUT → GET，PUT 从最新对象拷贝 `revision, mode, theme_color, background_blur, glass_opacity, shadow_strength, shadow_color, card_size, show_home_libraries, home_carousel, skip_mode` 中已存在字段，再设置目标字段 | GET 返回共享外观、卡片、首页/播放偏好，另读 `background_url` 与嵌套 `playback_preferences`；TV 不调用壁纸上传；`skip_mode` 的 TV 选项是 0=按钮、1=自动、2=关闭。色值支持字符串如 `#RRGGBB`；透明/阴影强度传 0–1、卡片号 0–4、背景模糊 TV UI 限 0–30；这些不是后端全量取值定义。 |
| POST `/api/tv/Users/Me/PlaybackPreferences` | 一次发送一个键：`audio_lang_priority` / `subtitle_priority` 为顺序字符串数组（JSON null 代表跟随全局）；`hide_watched_latest` 为布尔 | 不消费 POST 字段，重读 `ClientPreferences.playback_preferences` 下 `preferences, global_preferences, effective_preferences, hide_watched_latest`；不存在单独对 `PlaybackPreferences` 的 GET 调用。 |
| POST `/api/tv/Sessions/Logout` | 当前 token、`{}`；TV 尝试请求后不等待即可清理本地并返回设置入口 | 无业务字段；网络失败仍会本机注销，不能断言服务器注销成功。 |

- **连接身份**：输入只允许 HTTP(S)、有主机、无用户信息、查询或片段；未填写 scheme 的 TV 默认加 HTTP 并去尾斜线。若公布地址与输入同主机，可尝试公布地址；无地址或公布地址跨主机时，非管理端口保留已验证原入口，若当前端口等于 `ApiPort` 则用原 scheme/host/path 与 `MediaServerPort` 构造候选。候选和原入口不同，需重跑健康及公开信息探针并校验原 `Id` 非空且一致。不要直接信任管理地址下发的跨主机 URL，也不要把普通 Emby/Jellyfin 当作 ETKN。
- **凭据策略**：TV 正常用户保存加密 token；管理员默认只保存账号以便下次输入密码，只有在线开关允许保存且非私密才能保存 token；私密会话仅在内存，不保存账号/令牌。无全量持久化而密码为空，TV 提示先设密码。不从服务端拉账号列表，选择器为本机历史。恢复前对候选保存账号在线验证；到后台/切换账号丢弃旧结果。Mobile 的安全存储、明文 HTTP 风险和前后台登录门禁需按自己的平台设计。
- **锁定偏好**：锁定是本机按服务端和账号隔离的快照及背景文件，不是服务器写入；前台正常每 3s 尝试同步偏好，锁定停用共享偏好同步/写入，但账号开关照常独立同步。仅当同源当前会话、`/api/tv/` 且有快照 `effective_preferences` 时，TV JSON 请求加入 `X-ETKN-Locked-Preferences`（拷贝该对象并附 `hide_watched_latest`）。不要把它当鉴权头。普通的字幕大小、码率、整季音轨序号、解码/遥控操作、登录选择器、库视图/排序记忆、弹幕显示和入库通知开关是本机偏好，不臆造设置路由。

源码：[LanDiscovery.java](../app/src/main/java/xyz/etkn/tv/LanDiscovery.java#L21-L75)、[SetupActivity.java](../app/src/main/java/xyz/etkn/tv/SetupActivity.java)（登录/服务器名称/本地账号）、[EtknApi.java](../app/src/main/java/xyz/etkn/tv/EtknApi.java#L89-L155)、[SessionStore.java](../app/src/main/java/xyz/etkn/tv/SessionStore.java#L18-L126)、[TvAppearance.java](../app/src/main/java/xyz/etkn/tv/TvAppearance.java#L19-L63)、[SettingsActivity.java](../app/src/main/java/xyz/etkn/tv/SettingsActivity.java#L200-L246)、[TvActivity.java](../app/src/main/java/xyz/etkn/tv/TvActivity.java#L35-L53)、[TvPreferences.java](../app/src/main/java/xyz/etkn/tv/TvPreferences.java#L18-L58)。

## 首页、资料库与详情

| 方法 / 路径 | 实际变体与请求 | 主要响应 / 客户端处理 |
| --- | --- | --- |
| GET `U/Views` | 无查询 | 可访问的媒体库；`Id, Name, CollectionType, SourceType, ImageTags` 等，用 `CollectionType=music` 和 `SourceType=group` 分流。 |
| GET `U/Items/Resume` | `Limit`：按首页视口/页容量；不发偏移 | `Items` 供继续观看；读条目身份、`UserData.PlaybackPositionTicks` 等。 |
| GET `U/Items/Latest` | `ParentId, IncludeItemTypes, Limit`；首页跳过合集库，音乐库改用 `U/Music` | 每个库一组列表，不是全局 Latest；`hide_watched_latest` 走偏好，无该查询参数。 |
| GET `U/Items/PersonalRecommendations` | `Stream=true&Limit=1&Fields=Overview&ShuffleSeed=<UUID>&Cursor=<cursor>`；首请求 cursor 为空 | `Items, NextCursor`；缺失/null 结束，按需以新 UUID 开启新一轮。这里是分页 JSON **不是 SSE**；TV 跳过无背景图或同条目重复。当前源码直接拼 `Cursor`，Mobile 应在 fixture 中验证不透明值的编码与服务端预期一致。 |
| GET `U/Items` | ①分组只发 `ParentId`；②搜索 `Recursive=true, IncludeItemTypes=Movie,Series, Limit, StartIndex, SortBy=SortName, Fields=PrimaryImageAspectRatio,Overview, SearchTerm`（通用分支可附 `ParentId`）；③探索入库匹配 `Recursive=true, IncludeItemTypes=Movie,Series, Limit=60, Fields=ProviderIds, SearchTerm`；④海报墙见下文 | 列表 `Items, TotalRecordCount, EpisodeCountMode`（不保证每种均返回）；入库匹配以 `ProviderIds.Tmdb` 比对探索条目 `id`，不是只按标题。 |
| GET `U/Items/{itemId}` | 无查询为详情/队列及刷新用；轮播补 `Fields=Overview`；分集补 `Fields=MediaSources,MediaStreams,Overview` | 对象；`Id, Type, Name, Overview, UserData, People, ProviderIds, MediaSources, SeriesId, SeasonId` 和评分/图片字段；不要把列表弱字段当完整详情。 |
| GET `U/Browse` | `Limit, StartIndex, SortBy, SortOrder`；收藏页加 `Filters=IsFavorite`，分类库加 `ParentId` 及 `{Kind}={Value}` | `Items, TotalRecordCount, EpisodeCountMode`。详情的 `FavoriteItems` 是写状态，并非这版收藏列表的 GET。 |
| GET `U/Libraries/{libraryId}/Folders` | `FolderId`（根为空）、`Limit, StartIndex`；物理库目录查询，可带分类和 `Filters=IsFavorite`；生成播放队列分支额外 `Recursive=true` | 同列表，`Type=Folder` 分流。队列取 `Limit=1000, StartIndex=0`（非全量保证）；普通海报队列也一次限量 1000。 |
| GET `U/Libraries/{libraryId}/Collections` / `U/Items/{itemId}/Collections` | 分别 `Limit=200` / `Limit=60`，未发送偏移 | 合集列表；合集成员仍走 `U/Items?ParentId=<合集ID>…`，TV 用 `IncludeItemTypes=Movie`；合集库本身列表用 `BoxSet`。 |
| GET `U/Facets` | `ParentId, Kind`；入口值 `Types / Networks / Companies` | 列表行 `Id, Name, Kind, Value, ImageTags`；分类卡片回传自身 `Kind/Value` 到 Browse。封面 GET、生成 POST 见图片/管理。 |
| GET `I/Similar` | `UserId, Limit=20`，无客户端分页 | 相似影视 `Items`，缺失/空列表允许隐藏区块。 |
| GET `/api/tv/Persons/{personId}` | `UserId` | 本地人物 `Name, Overview, Id, ImageTags` 等；与探索 TMDb 人物不是同一身份。 |
| GET `/api/tv/Persons/{personId}/Items` | `UserId, IncludeItemTypes=Movie,Series, StartIndex, Limit=40` | 可见作品 `Items, TotalRecordCount, EpisodeCountMode`；偏移按实际返回数量推进。 |

海报墙 `U/Items` 变体：`Recursive=true, ParentId, IncludeItemTypes, Limit, StartIndex, SortBy, SortOrder, Fields=PrimaryImageAspectRatio,Overview,CommunityRating,TMDbRating,DoubanRating,IMDbRating,CriticRating,ProductionYear,RecursiveItemCount,UserData`；可附条目分类键（如 `Genres` / `Tags`）及 `Filters=IsFavorite`。普通库类型映射：`movies→Movie`、`tvshows→Series`、`homevideos→Video`、其他 `Movie,Series,Video`。TV 可发 `Ascending`/`Descending`，随机用 `SortBy=Random&SortOrder=Ascending`；TMDb、豆瓣、IMDb、烂番茄分别使用 `TMDbRating, DoubanRating, IMDbRating, CriticRating`，不要合并为 `CommunityRating`。UI 的完整排序取值见 [HomeActivity.java](../app/src/main/java/xyz/etkn/tv/HomeActivity.java#L28-L46)。海报墙和搜索 `StartIndex` 从零开始，正常按返回条数推进，用 `TotalRecordCount` 及空页终止；图片宽度、每页容量随 TV 视口变化，不是后端上限。首页、列表和详情失效联动见[事件](#事件通知与轮询)。

| 写操作 | 请求 | 响应及 UI 门禁（非后端授权） |
| --- | --- | --- |
| POST / DELETE `U/FavoriteItems/{itemId}`、`U/PlayedItems/{itemId}` | POST `{}` 设置、DELETE 清除 | 响应直接当作 `UserData` 字段合并，如 `IsFavorite, Played`；季/剧已看状态改变后重取分集。 |
| POST `U/Items/{itemId}/HideFromResume` | `{}` | 不用业务字段，刷新列表。 |
| POST `I/Refresh` | `Action=replace_metadata / replace_images / replace_metadata_images`，`{}` | `submitted, reason`（认识 `task_already_running`）、`RefreshRunId`；管理员或 `TvPermissions.CanRefreshMetadata`；详情/普通卡管理检查 `item-` 前缀，首页库刷新排除 `personal_recommendation`。 |
| GET `I/Refresh/{runId}` | 仅对已提交且 ID > 0 的任务，前台约 3s 轮询 | `RefreshRunId, finished, status`，认识 `succeeded, cancelled, partial`，其他完成态显示失败。 |
| POST `I/Scan` | `{}`；此处 `itemId` 是所选库条目 ID | 不使用业务字段；TV 仅对管理员、`SourceType` 为 `path` 或 `list` 的库显示。 |
| DELETE `I` | 无请求体；TV 仅确认后调用 | 未读删除结果字段，只显示“已提交”；管理员或 `CanDeleteMedia`，TV 普通条目还检查 `item-`；可能影响季/剧全部分集、媒体所有版本及关联文件。Mobile 默认不上线此能力。 |
| POST `U/Libraries/{libraryId}/FolderCover` | `FolderId`；`{}` | 不使用响应字段；管理员或 `CanRefreshMetadata`，生成后重取列表和图标签。 |
| POST `U/FacetArtwork` | `ParentId, Kind, Value`；`{}`；`Networks / Companies` 附 `Action=icon` | 管理员或 `CanRefreshMetadata`；逐项提交，重读分类；没有独立批量接口调用。 |

源码：[HomeActivity.java](../app/src/main/java/xyz/etkn/tv/HomeActivity.java#L184-L231)、[HomeActivity.java](../app/src/main/java/xyz/etkn/tv/HomeActivity.java#L288-L368)、[HomeActivity.java](../app/src/main/java/xyz/etkn/tv/HomeActivity.java#L398-L569)、[FacetActivity.java](../app/src/main/java/xyz/etkn/tv/FacetActivity.java#L35-L86)、[PersonActivity.java](../app/src/main/java/xyz/etkn/tv/PersonActivity.java#L16-L30)、[DetailActivity.java](../app/src/main/java/xyz/etkn/tv/DetailActivity.java#L387-L441)、[TvActivity.java](../app/src/main/java/xyz/etkn/tv/TvActivity.java#L177-L198)、[MediaActions.java](../app/src/main/java/xyz/etkn/tv/MediaActions.java#L12-L32)。

## 播放、选集及增强

| 方法 / 路径 | 请求 | 主要响应 / 行为 |
| --- | --- | --- |
| GET `S/Seasons` | `UserId` | 列表，季的 `Id, Name, ImageTags` 用来显示季切换。 |
| GET `S/PlaybackTarget` | `UserId, Fields=MediaSources,MediaStreams,Overview`；从季进入附 `SeasonId` | 取 `Items[0]` 为推荐/续播目标；非选集入口无目标时试 `EpisodePage&Limit=1`。 |
| GET `S/EpisodePage` | `UserId`，通常 `Limit=40`（找首集时 1），按需 `SeasonId`；首次定位 `AnchorId`，之后用 `StartIndex` 取前/后页 | `Items, StartIndex, NextStartIndex, TotalRecordCount`。前页请求按锚点前剩余量缩小 limit，避免与已加载页重叠；列表条目可能没有 `MediaSources`，点播时补条目详情。 |
| GET `S/Episodes`（兼容/局部流程） | `UserId`；详情选集首次 `EpisodePage` 失败时以 `Fields=MediaSources,MediaStreams,Overview` 回退；详情播放状态刷新也会用该查询；播放器未解析剧/季队列使用 `IsMissing=false` | 整组分集 `Items`，TV 可按季过滤、本地找首个未看可播或首个可播。不是 `NextEpisode` 的通用回退；不要为每次切集载入整剧。 |
| GET `I/NextEpisode` | `UserId`（播放器有剧 ID 时请求） | `Items[0]` 或空；空与请求失败区分，客户端按服务端给出的跨季下一集推进；非认证错误不回退到全剧查询。 |
| POST `I/PlaybackInfo` | 视频带 `UserId`、音乐不带；均 `{}` | `PlaySessionId, MediaSources[]`；可附 `Danmaku.Available`。媒体源按需读 `Id, DirectStreamUrl, Path, Container, RunTimeTicks, MediaStreams, DefaultAudioStreamIndex, DefaultSubtitleStreamIndex, Chapters, BlurayPlaylist, SubtitleTranslation`；音频取第一源，视频按选中版本。 |
| POST `/api/tv/Sessions/Playing`、`/api/tv/Sessions/Playing/Progress`、`/api/tv/Sessions/Playing/Stopped` | 三者共用 `ItemId, UserId, MediaSourceId, PlaySessionId, PositionTicks, IsPaused`；视频额外 `RunTimeTicks, CanSeek: true, PlayMethod: "DirectStream", Client: "ETKN TV", TranscodeSessionId`；有条件带 `DanmakuActive`，用片尾跳过推进时停止报告附 `PlayedToCompletion: true`；音乐总是带 `PlayedToCompletion` 布尔 | 视频可读回复 `Danmaku` 对象；音乐不读业务字段。TV 就绪开始、约每 10s 进度（音乐亦在播放状态/seek 时报告）然后停止，按实际位置上报。切源、退出、切集建议序列化，避免旧源停止覆盖新源。HTTP 已返回成功和本地 UI 先刷新是两回事。 |
| POST `I/Transcode` | JSON：`MediaSourceId, PlaySessionId, PositionTicks, Reason, Bitrate, MaxWidth, MaxHeight, NetworkBitrate, H264, HEVC, HevcHdr10, AudioStreamIndex, SubtitleIndex`；三组设备能力数组元素 `Width, Height, FrameRate, Bitrate`；码率为 bit/s。触发值如 `user_bitrate, hardware_limit, network_limit, no_first_frame, audio_error, track_switch` | `DirectPlay=true` 保留/恢复直接流，可读 `AssistanceAllowed`；否则要 `SessionId, MediaSource`，可读 `Method, Notice`。返回的 `MediaSource` 仍用播放地址解析，不自造转码 URL。客户端检查在途结果对应原会话及条目。 |
| DELETE `/api/tv/Transcodes/{sessionId}` | 无体 | 停止协助/退出或收到陈旧的成功转码响应时清理；不消费返回字段。源码媒体层对 `/api/tv/Transcodes/` 下的 `.ts` 单独延长读超时，不提供固定清单/分段模板。 |
| GET `I/PreviewThumbnails` | `MediaSourceId` | `MediaSourceId, AspectRatio, Thumbnails`；匹配当前源才使用。缩略图项 `Index, PositionTicks, ImageTag` 按位置选最近帧；图片地址见下节。 |
| GET / POST `I/{mediaSourceId}/SubtitleOffsets` | GET/POST 可带 `TranslationKey`；只有用户确认才 POST `{SubtitleKey, OffsetMs}` | GET `Offsets`（键取轨道/翻译计划中的 `SubtitleOffsetKey`，数值为毫秒）；POST 不消费响应字段；读轨道 `SubtitleOffsetMs, IsBurnedIn`，已烧录轨不能偏移。 |
| POST `I/{mediaSourceId}/SubtitleTranslation` | 选用媒体源 `SubtitleTranslation` 计划时发 `{key, position, cues:[{start,end,text}]}`；时间毫秒，每批至多 64 个提示 | `Enabled, Cues[]`，译文 cue 读 `start, end, source, text`；计划读 `Key, Mode, Target, SourceIndex, SourceOrdinal`。TV 的节流、批次限制及本地渲染不是服务端配额。 |
| GET `/api/tv/System/Danmaku` | 在用户开启弹幕、现有状态尚不可用时读取 | `Available`；不调用服务端的功能开关。 |
| GET `I/Danmaku` | `local=true, session=<PlaySessionId>, start=<窗口起始毫秒>`；TV 用 120000 ms 窗口，按播放位置加载并预取相邻窗口 | `enabled, status, comments[]`；识别 `stale, busy, unavailable, unmatched`；comment `time` 毫秒、`text, mode, color`。仅在服务端可用/本机启用且正在播放时拉取。 |

- 剧集、季、分集与播放源是不同 ID。保留 `UserData`、集/季编号、`IsMissing`/`LocationType` 及 `RunTimeTicks`；跳过仅使用明确的 `Chapters[].MarkerType`（`IntroStart / IntroEnd / CreditsStart`）与 `StartPositionTicks`，没有另外的跳过 API。原盘轨道用 `TransportStreamId`（TS PID）加编解码格式认轨，不要用跨集服务器 `Index` 代替本机整季音轨序号。`-2` 为 TV 选择器的自动值，外部字幕和下发索引不应盲套到另一版本或设备。
- 实际媒体流不走 JSON 客户端，见下节。对 `PlaySessionId`、`MediaSourceId` 和转码 `SessionId` 分别建模，不能互换。先把直接播放与报告做好，再做转码、HDR/ISO、字幕增强；支持列表不代表目标设备已实测解码、字幕或 HDR 输出。

源码：[DetailActivity.java](../app/src/main/java/xyz/etkn/tv/DetailActivity.java#L545-L635)、[PlayerEpisodePanel.java](../app/src/main/java/xyz/etkn/tv/PlayerEpisodePanel.java#L50-L109)、[PlayerActivity.java](../app/src/main/java/xyz/etkn/tv/PlayerActivity.java#L162-L288)、[PlayerActivity.java](../app/src/main/java/xyz/etkn/tv/PlayerActivity.java#L319-L353)、[PlayerActivity.java](../app/src/main/java/xyz/etkn/tv/PlayerActivity.java#L580-L724)、[TranscodeChoice.java](../app/src/main/java/xyz/etkn/tv/TranscodeChoice.java#L59-L97)、[SubtitleOffsetSession.java](../app/src/main/java/xyz/etkn/tv/SubtitleOffsetSession.java#L36-L94)、[SubtitleTranslation.java](../app/src/main/java/xyz/etkn/tv/SubtitleTranslation.java#L44-L127)、[DanmakuSession.java](../app/src/main/java/xyz/etkn/tv/DanmakuSession.java#L14-L58)、[DanmakuTimeline.java](../app/src/main/java/xyz/etkn/tv/DanmakuTimeline.java#L9-L24)、[SkipSegments.java](../app/src/main/java/xyz/etkn/tv/SkipSegments.java)、[DiscTrackIdentity.java](../app/src/main/java/xyz/etkn/tv/DiscTrackIdentity.java)。

## 图片和服务端供给的 URL

图片 GET 用独立 transport：只向与媒体 base 同 scheme/host/有效端口的地址附 `X-Emby-Token`，**不发 JSON Cookie 或设备头**；手动处理跳转（循环至多六次连接，外源目标只接受 HTTPS）且逐跳重判鉴权，最终要求 200，消费二进制可解码图片。不要将媒体源鉴权传给外部图片/CDN。

| GET 目标 | 构造规则和观察 |
| --- | --- |
| `I/Images/{type}` | `maxWidth, quality, tag`；常用 `Primary, Backdrop, Logo`。读取 `ImageTags, BackdropImageTags / BackdropImageTag`，优先沿用 `ParentBackdropItemId / ParentBackdropImageTags` 或 `ParentLogoItemId / ParentLogoImageTag`。库卡/季/人像都可走此路径。 |
| `I/Images/Backdrop/{index}` | 音乐艺人图回退，ID/序号/tag 来自 `ArtistImage.{ItemId,Index,Tag}`；`maxWidth, tag, variantGroup=avatar`。 |
| `I/Images/Chapter/{chapterIndex}` 或返回的 `Chapters[].ImagePath` | 默认路径按章节索引；给 `ImagePath` 时，已以 `/api/` 开头则原样前缀保留，否则在相对路径前加 `/api/tv`；附 `maxWidth, quality=85, tag=<ImageTag>`。这是源码路径兼容行为，非固定另一套章图 API。 |
| `U/Images/Primary` | `maxWidth=160`；当前头像有 `tag=<PrimaryImageTag>`，账号选择器无 tag 且使用目标已存账号 token；缺图或无权时用本地占位。 |
| `U/Libraries/{libraryId}/FolderCover` | `FolderId, maxWidth, tag`，仅有图标签的目录才尝试读取；此 GET 和生成 POST 不同。 |
| `U/FacetArtwork` | `ParentId, Kind, Value, tag`，tag 来自 `ImageTags.Primary`；二进制图，与 POST 的生成/拉取不同。 |
| `I/PreviewThumbnails/{index}` | `MediaSourceId, tag=<ImageTag>`，索引来自缩略图清单。 |
| `/api/image_proxy` | 音乐 `ArtworkHash` 非空优先用 `cache=<hash>, maxWidth`，歌手另带 `variantGroup=avatar`。保留此非 `/api/tv` 路径。 |
| `{server}{background_url}` | 由共享偏好返回的路径、非固定路由；正常背景与本机锁定下载复用图片 transport。 |
| `https://image.tmdb.org/t/p/{size}{poster_path 或 profile_path}` | TMDb 图片 CDN，TV 实际使用 `w185, w342, w500`；非 ETKN JSON API，不加 ETKN token。 |

视频、音乐、字幕走 Media3/OkHttp 的 GET（可追加 Range），默认不注入 ETKN 头或 Cookie，使用源 URL 自带凭证/查询鉴权：

| 来源 / 动态 GET | 解析、鉴权及兼容 |
| --- | --- |
| `MediaSources[].DirectStreamUrl`（也包括转码 `MediaSource`） | 必须存在，接受绝对 URL 或拼接服务器 base 的相对路径；普通直接流必须同源。视频普通流客户端**不额外**追加 token；音乐同一解析后主动设置查询 `X-Emby-Token, UserId, PlaySessionId, MediaSourceId`。不从 ID 推导 `/Videos/…` 或 `/Audio/…`。 |
| 原盘 `Path` / `DirectStreamUrl` | 源 `Container=iso` 或 `Path` 含 `.iso` 时 URL 解析优先非空 `Path`，同源且缺失时补查询 `X-Emby-Token` 和 `UserId`；外源原盘允许 HTTP(S)，不补 ETKN 凭证。实际原盘工厂识别 URL 路径后缀 `.iso`，用 `BlurayPlaylist`；请求 `Range: bytes=<offset>-` 及 UA，必须得 `206` 且 `Content-Range: bytes <offset>-<end>/<size>` 起点匹配。TV 能重用有效重定向目标，若重用的签名地址返回 401/403 再用原地址。 |
| `MediaSource.OriginalVideoUrl` | 转码源 `AudioOnly` 且带此字段时，其同源视频/字幕流与转码音频分别取流后合并。 |
| `MediaStreams[].DeliveryUrl` | 仅外部字幕、同源；相对路径以 `/` 开头才拼 base；从查询移除全部 `UserId`，设置 `X-Emby-Token`，保留其余查询。按流 `Codec` 选择 PGS、ASS/SSA、VTT 或 SRT 解析。 |
| 播放器内部跟随动态流地址 | 本仓库没有拼接固定视频文件、HLS 清单或分段路由；使用返回地址以及媒体解析器跟随的实际资源。`/api/tv/Transcodes/` 下 `.ts` 使用 65s 读超时，其他流通常 10s，属客户端策略。不把 TV OkHttp 行为直接当作 Mobile 跨源/重定向安全策略。 |

源码：[EtknApi.java](../app/src/main/java/xyz/etkn/tv/EtknApi.java#L155-L195)、[Http.java](../app/src/main/java/xyz/etkn/tv/Http.java#L14-L25)、[Images.java](../app/src/main/java/xyz/etkn/tv/Images.java#L99-L122)、[SeekThumbnailPreview.java](../app/src/main/java/xyz/etkn/tv/SeekThumbnailPreview.java#L48-L98)、[AppearanceBackground.java](../app/src/main/java/xyz/etkn/tv/AppearanceBackground.java#L19-L36)、[MusicViews.java](../app/src/main/java/xyz/etkn/tv/MusicViews.java#L17-L22)、[PlaybackAddress.java](../app/src/main/java/xyz/etkn/tv/PlaybackAddress.java#L12-L41)、[TvPlayerFactory.java](../app/src/main/java/xyz/etkn/tv/TvPlayerFactory.java#L33-L54)、[BlurayMediaSourceFactory.java](../app/src/main/java/xyz/etkn/tv/BlurayMediaSourceFactory.java#L25-L45)、[BlurayInput.java](../app/src/main/java/xyz/etkn/tv/BlurayInput.java#L49-L74)、[MusicPlayback.java](../app/src/main/java/xyz/etkn/tv/MusicPlayback.java#L48-L75)。

## 音乐与歌单

| 方法 / 路径 | 请求变体 | 主要响应 / 处理 |
| --- | --- | --- |
| GET `U/Music` 推荐 | `ParentId, Key`（可为空）、`Tab=recommended, SortBy=Name, SortOrder=Ascending, FrequentLimit=<视口大小，TV 选择 6–200>` | 顶层 `Latest, Recent, Frequent` 数组，最近/常听基于登录账号。 |
| GET `U/Music` 分类/列表 | `Tab=albums / artists / songs / folders / playlists`、`ParentId, Key`；列表 `StartIndex, Limit=60`；艺人只看歌曲附 `Tracks=1`。根专辑/艺人/歌曲 `SortBy=DateCreated&SortOrder=Descending`，其余 `SortBy=Name&SortOrder=Ascending` | `Items, TotalRecordCount, Title`，可带 `Artist`（含 `Overview`）、`Folders`；分组 `Kind, Key, Name`，歌曲 `Type=Audio`、`Id, Artists/Artist, Album, AlbumKey, ArtistLinks, UserData, RunTimeTicks`。空页终止，失败展示重试。 |
| GET `U/Music` 首页最新 / 专辑队列 | 首页 `Tab=albums, SortBy=DateCreated, SortOrder=Descending, ParentId, Limit`，不带 Key；打开专辑 `Tab=albums, ParentId, Key, SortBy=Name, SortOrder=Ascending, StartIndex=0, Limit=200`；队列追加对当前列表路径用 `StartIndex, Limit=200` | 首页读列表；专辑过滤 `Audio`、按返回顺序播放首曲及后续，不拿名称在本地重新排序。队列按已加载条数和 `TotalRecordCount` 追加，TV 去重后无新增则停止。 |
| GET `U/Music` 歌单摘要 / 成员检查 | 摘要走根 `Tab=playlists`（按通用路径带 `ParentId, Key, SortBy, SortOrder`，不分页）；检查某歌曲成员关系为 `Tab=playlists&ItemId=<歌曲ID>`；按 `Key` 打开歌单成员仍走分类请求 | 顶层 `Playlists[]` 含 `Name, Key, Count`，成员检查取 `Included`；歌单成员列表见 `Items` 分支。 |
| POST `U/Music/Playlists` | `{Name}`（TV 去空白、UI 最长 80 字符） | 创建后若要立即添加曲目，以响应 `Key` 作为歌单 ID。 |
| DELETE `U/Music/Playlists/{key}` | 无体 | TV 仅确认删除歌单，提示保留音乐文件；不使用业务字段，重读歌单。 |
| POST / DELETE `U/Music/Playlists/{key}/Items/{itemId}` | POST `{}` 加歌曲，DELETE 移除 | 不使用业务字段；音乐收藏使用与视频相同的 `FavoriteItems`。从临时播放队列移除无需服务器请求。 |
| GET / POST `/api/tv/Music/{itemId}/Lyrics` | `MediaSourceId=<正在播放的源>`，先 GET，**只有 GET 成功且 `text` 为空**才 POST `{}` 尝试在线补取，不以任意错误触发 POST | `text` 为 LRC 或纯文本。LRC 本地解析/按播放位置高亮；切歌、退出、换账号后取消/丢弃在途结果。 |

音乐的 PlaybackInfo、媒体流取法和进度报告见[播放](#播放选集及增强)和[直接 URL](#图片和服务端供给的-url)；队列、循环、随机及状态展示是客户端逻辑。

源码：[MusicActivity.java](../app/src/main/java/xyz/etkn/tv/MusicActivity.java#L47-L125)、[MusicActions.java](../app/src/main/java/xyz/etkn/tv/MusicActions.java#L9-L43)、[MusicPlayback.java](../app/src/main/java/xyz/etkn/tv/MusicPlayback.java#L48-L134)、[MusicPlayerActivity.java](../app/src/main/java/xyz/etkn/tv/MusicPlayerActivity.java#L98-L113)、[MusicLyrics.java](../app/src/main/java/xyz/etkn/tv/MusicLyrics.java)。

## 影视探索、订阅与云资源

所有 `D` 路由使用会话鉴权。TV 先看 `user.can_access_discovery`，并按仓库的 [AGENTS.md](../AGENTS.md) 将实例会员及用户模板权限最终交由服务端 `require_discovery`；本库不包含该校验实现。探索用 TMDb ID，和媒体库 `item-…`、播放源、歌单 Key 不能混用。注意同一剧集的几种 wire 值：查询类型 `series`，资源搜索/媒体 credits 类型 `tv`，获取请求跟踪查询 `MediaType=series`。

| 方法 / 路径 | 请求 | 主要响应 / 客户端行为 |
| --- | --- | --- |
| GET `D/options` | `media_type=movie` 或 `series` | `genres, keywords, studios, countries, languages, ratings` 等选项数组，用 `label/value`；分级值以整数发送。 |
| POST `D/query/{movie 或 series}` | JSON `page`（从 1）、`query, sort_by, hide_in_library, keywords, studios`；另有可选 `with_genres, without_genres`（逗号连接值）、`with_origin_country, with_original_language, release_date_gte, release_date_lte, vote_average_gte, rating`。`keywords, studios` 为字符串数组，可空；年份范围转为 `YYYY-01-01 / YYYY-12-31`，日期排序电影用 `primary_release_date`，剧集用 `first_air_date` 后缀 `.asc / .desc` | 列表及 `total_pages`（TV 最多翻 500 页；空页停止，未发 page size）。条目读 `id, title/name, media_type, overview, poster_path, backdrop_path, genre_ids, in_library, subscription_status` 等。 |
| GET `D/series/{tmdbId}` | 无查询 | `seasons` 含 `season_number, episode_count, air_date, poster_path, overview, in_library, subscription_status`；顶层 `overview, tagline, backdrop_path` 补齐详情。 |
| GET `D/media/{movie 或 tv}/{tmdbId}/credits` | 无查询 | `cast`，成员含 `id, name, character, profile_path`；同时读 `backdrop_path, overview`。 |
| GET `D/person/{personId}/credits` | 无查询 | `person`（如 `name, biography, profile_path`）、`works`；不是本地作品接口 `/api/tv/Persons`。 |
| POST `D/subscribe` | 用户确认；JSON `{tmdb_id, name, entity_type, poster_path, release_date, first_air_date, priority: 50}`，电影 `entity_type=movie`，季度 `entity_type=season` 加 `season_number`；`tmdb_id` 在此是字符串 | 读 `demand_id`，本地标 `WANTED`，表示提交而不是入库。批量缺季是逐季 POST、首错停止，已有成功不回滚；TV 不调用取消订阅 API。 |
| GET `D/shared-resources` | `tmdb_id, item_type`（`movie / tv` 或定季时 `season`）、`q, limit=100`；**定季不发送季号** | 资源行可含聚合 `versions`；精确版本可使用 `logical_group_id, content_version_key, best_asset_map` 等，客户端按明确季号或标题识别季并过滤；个别缺少季号的共享池结果不进入定季列表。 |
| GET `D/re0-resources` | `tmdb_id, media_type=movie/tv, require_complete=false`，可选 `season` | 资源列表；TV 读取 `slug`（回退 `id`）、`already_owned, unlock_points, quality` 等。 |
| GET `D/channel-resources` | `tmdb_id, media_type=movie/tv, q, year`，可选 `season_number` | 资源列表；TV 读取 `message_date, source_channel` 等。`year` 可以是空字符串。 |
| POST `D/shared-resources/acquire` | 共用跟踪查询，见下；JSON `{source: <确切所选版本对象>}` | 读 `workflow_run_id`；共享池选择不能误用聚合父项的文件或源 ID。规范化逻辑组字段，`best_asset_map` 若为字符串尝试解析为对象后再发送。 |
| POST `D/re0-resources/acquire` | 共用跟踪查询；JSON `{slug, title, tmdb_id, media_type}`，可选 `season_number`；body `media_type=movie/tv` | 读 `workflow_run_id`；不能把跟踪查询 `MediaType=series` 复制到 body。 |
| POST `D/channel-resources/acquire` | 共用跟踪查询；JSON `{resource: <所选资源对象>}` | 读 `workflow_run_id`。 |
| GET `D/acquire-status/{runId}` | 无查询，当前账号（本机仅保存正的 `workflow_run_id`） | `ingest_items[]` 去重入库提醒；`finished` 为真停止跟踪，另读 `failed, error`。TV 本机跟踪 7 天，无据可推为服务器保留期。 |

三个获取 POST 都附 `TmdbId=<id>&MediaType=movie/series`，定季时附 `SeasonNumber`，且都先确认；获取可能消耗服务端资源积分，不能预取或无确认重试。三种搜索并行且分别显示失败，无客户端继续翻页机制；共享池优先保留确切版本的资源信息，不能从聚合父项继承 SHA1、文件或来源 ID。提交和已入库是两个阶段，也不是幂等性保证；与 `U/IngestUpdates` 的提醒去重。未观察到 TV 直接调用共享池、re0、频道或 TMDb 的 JSON 服务，浏览和获取接收的均是 ETKN 路由，TMDb 图片例外。

源码：[DiscoveryActivity.java](../app/src/main/java/xyz/etkn/tv/DiscoveryActivity.java#L81-L91)、[DiscoveryActivity.java](../app/src/main/java/xyz/etkn/tv/DiscoveryActivity.java#L216-L264)、[DiscoveryActivity.java](../app/src/main/java/xyz/etkn/tv/DiscoveryActivity.java#L345-L468)、[DiscoveryActivity.java](../app/src/main/java/xyz/etkn/tv/DiscoveryActivity.java#L511-L650)、[DiscoveryActivity.java](../app/src/main/java/xyz/etkn/tv/DiscoveryActivity.java#L705-L724)、[CloudResources.java](../app/src/main/java/xyz/etkn/tv/CloudResources.java#L18-L66)、[DiscoveryPresentation.java](../app/src/main/java/xyz/etkn/tv/DiscoveryPresentation.java#L8-L39)、[AcquireTracker.java](../app/src/main/java/xyz/etkn/tv/AcquireTracker.java#L19-L59)。

## 事件通知与轮询

| 方法 / 路径 | 请求、认证与传输 | 主要响应与消费 |
| --- | --- | --- |
| GET `U/Home/Events` | **SSE**，发送 `Accept: text/event-stream` 及 `X-Emby-Token`（不走 JSON transport，无 Cookie/设备鉴权头）；详情加 `ItemId`，首页无查询；要求 200 且内容类型以 `text/event-stream` 开头，禁止跳转 | 首页消费 `event: home` 的 `data.revision`（`data:` 为单行 JSON）；详情消费 `event: items` 的 `Initial, Versions, Updated, Added, Removed`（区分行更新或列表结构变动）；统认 `event: auth-expired` 或 HTTP 401。前台订阅、暂停断开、失败约 3s 重连，读超时 25s；无 `Last-Event-ID` 调用。 |
| GET `U/IngestUpdates` | 当前 token 的 JSON；`After=<cursor>`，初始为空，按账号隔离 | `Cursor`、`HasMore`、`Initial`、列表。忽略初始批的旧提醒但保存 Cursor；每轮最多 5 页。事件读 `EventId`（回退 `VersionId`）去重，另读 `Id, Name, SeriesName, Type, Outcome`；`replaced` / `version_added` 区分洗版/新增版本或更新。 |

首页返回和前台每分钟执行重新检查，首页 shelf 可用按服务端/账号分隔的 24h 本机缓存先显示，仍在线重取。SSE revision 变化触发已加载区域失效，不是全套首页实时数据；详情按 versions 或事件条目重取。入库通知及获取状态另由 TV 约每 60s 的前台检查/Android JobScheduler（周期 15min，请求任意网络）驱动；刷新任务有自己的前台轮询。以上是 TV 计划，不是服务器 SLA、推送订阅、Mobile 后台时效或 SSE 完整规范。Mobile 要分别处理 SSE 中断、登录失效、JSON 游标和后台权限，避免高频空轮询，并在切账号时清理旧任务与缓存。

源码：[HomeUpdates.java](../app/src/main/java/xyz/etkn/tv/HomeUpdates.java#L15-L47)、[HomeActivity.java](../app/src/main/java/xyz/etkn/tv/HomeActivity.java#L134-L174)、[HomeCache.java](../app/src/main/java/xyz/etkn/tv/HomeCache.java#L7-L26)、[DetailChanges.java](../app/src/main/java/xyz/etkn/tv/DetailChanges.java#L6-L28)、[DetailActivity.java](../app/src/main/java/xyz/etkn/tv/DetailActivity.java#L450-L489)、[IngestNotifications.java](../app/src/main/java/xyz/etkn/tv/IngestNotifications.java#L29-L82)、[TvActivity.java](../app/src/main/java/xyz/etkn/tv/TvActivity.java#L90-L103)。

## 兼容、发布和非后端契约

- **ETKN 专用接口**：按当前源码继续用 `/api/tv`；没有发向普通 Emby/Jellyfin 路由的通用 API 回退。明确兼容行为是 `S/Episodes`、章节 `ImagePath` 前缀、`/api/image_proxy`、公开连接探针和服务端下发原始地址等；不要用其它旧文档中的路由补足未知。版本探针只证明这版客户端识别 `TvApiVersion=1`，不是每个路由在所有服务端安装上的可用性保证。
- **LAN 发现不是 Mobile 必须复制的握手**：TV 自动扫描读取设备私有 IPv4 链路，范围不大于 /24，逐候选用无鉴权 HTTP 尝试 8097、8096、5257，复用上文的严格 ETKN 身份校验；这不是 mDNS/SSDP 或服务器广播。Mobile 建议手动连接优先，自动扫描仅在有明确用户授权、目标平台有局域网访问许可时启用。来源：[LanDiscovery.java](../app/src/main/java/xyz/etkn/tv/LanDiscovery.java#L17-L75)。
- **Fork 更新和媒体服务分离**：公开 GET `https://gh-proxy.com/https://github.com/Alumos/etkn-tv/releases/latest/download/update.json`（`Cache-Control: no-cache`），无 ETKN 凭据。读 `packageName, versionCode, versionName, size, sha256, apkUrl` 及可选 `releaseNotes`。清单内 `apkUrl` 须为指定仓库的 HTTPS GitHub Release APK 资产地址 `https://github.com/Alumos/etkn-tv/releases/download/{tag}/{file}.apk`；实际 APK GET 给该地址加 `https://gh-proxy.com/`，不直连 GitHub，跳转限同一 HTTPS 镜像 authority、最终要求 HTTP 200。下载检查大小、SHA-256、包标识 `xyz.etkn.tv.revised`、递增版本和与已安装包相同的签名；这是 TV 独立发行机制，不是 Mobile 更新地址或签名规则。来源：[Updater.java](../app/src/main/java/xyz/etkn/tv/Updater.java#L13-L84)、[app/build.gradle](../app/build.gradle#L4-L16)。
- **测试/规格空白**：Mobile 启动前建议用脱敏 fixture 或经授权的隔离服务验证：代理 base path 与 UTF-8/空格、HTTP 错误和跳转、账号/权限变化、列表游标与空页、SSE 断流、直出 Range/字幕、进度最终落地以及异步写接口的失败/重复提交表现。源码不能证明必填字段全集、服务器分页/速率上限、错误码矩阵、重试幂等、token 生命周期、Web CORS、第三方媒体 URL 空间或 Mobile 播放器兼容性。本次未实际访问服务，没有运行 Mac Gradle、没有生成 APK。
