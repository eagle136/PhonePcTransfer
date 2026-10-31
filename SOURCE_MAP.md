# PhonePcTransfer · 源码地图（Android 端 + PC 端）

> 用途：改代码前先在本文件定位目标文件，避免全库搜索。
> 生成时间：2026-09-22
> 覆盖：`android/app/src/main/java/**` 43 个 .kt（约 19.3k 行）＋ `pc/src/**` 36 个 .java（约 18.0k 行）＋ 构建脚本/资源/工具。
> 结构：**第一部分 = Android 端**（含 3 个大文件拆分方案）；**第二部分 = PC 端**（含 2 个大文件拆分方案）。
> 配套：协议唯一权威源 `docs/PROTOCOL.md`。
> ⚠ 本文件只描述现状与方案，**不含任何已完成的重构**——拆分尚未执行。

---

## 第一部分　Android 端

## 手机电脑互传（Android）· 中文源码索引

> 工程：`<repo>/android`
> 包名：`com.phonepctransfer` · Jetpack Compose（Material3）· Kotlin 2.2.10 / AGP 9.3.0 · minSdk 28 / targetSdk 37 / compileSdk 37
> 覆盖：**43 个 `.kt`**（19314 行，已逐个真读，非按文件名猜测）+ 构建配置 + 资源 + `tools/` + `build-ext/`。
> 文件行数以 `wc -l` 实测为准。

---

### 一、文件作用索引

#### 1.1 根包 `com.phonepctransfer`（4 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `AppCore.kt` | 1958 | 全局单例 `object`：UI 状态中枢 + 装配入口，持有全部 Compose 状态 | `object AppCore`：`init` / `refreshLan` / `startRun` / `startScan` / `autoConnectLast` / `connectTo` / `reloadConversation` / `addMessage` / `updateMessage` / `deleteMessage` / `clearHistory` / `planConversation` / `planAllConversations` / `copyForAdd` / `copyTextToClipboard` / `toast`；`data class ClearPart` / `ClearPlan` | `videoRemoteRot` / `videoLocalRot` 初值**不能**写 `= prefs.xxx`：object 属性初始化器在 `<clinit>` 期跑，此时 `prefs` 还没注入 → 闪退。故恒初始化为 `-1` |
| `MainActivity.kt` | 469 | 唯一 Activity：系统分享入口、冷启动权限引导、锁屏来电意图、剪贴板生命周期联动 | `class MainActivity : ComponentActivity`：`onCreate` / `onNewIntent` / `handleShareIntent` / `applyCallIntent` / `maybeRequestPermissions` / `nextBootPerm` / `filePicker` / `folderPicker` / `ensureService` | `singleTask`；`bootPermFlow` 用来停用旧的「单点引导」防止连环跳设置页；`EXTRA_CALL_ACTION` 取完必须 `removeExtra`，否则配置变更重建会重复接听/拒绝 |
| `TransferService.kt` | 274 | 前台服务：后台保活链路；ColorOS 速冻豁免全靠它 | `class TransferService : Service`：`onStartCommand` / `startForegroundSafely` / `currentFgsType` / `refreshFgsType` / `setupMediaSession` / `buildNotification` / `statusLine` | 假 `MediaSession`(STATE_PLAYING) + `mediaPlayback` 类型防 OplusHans 速冻；`camera` 类型**仅在 CAMERA 权限已授予时**才申报，否则 `startForeground` 抛 SecurityException |
| `CallNotif.kt` | 278 | 锁屏来电通知（全屏意图 + 接听/挂断广播按钮）+ 广播接收器 | `object CallNotif`：`show` / `cancel` / `wakeScreen` / `activityIntent` / `build`；`class CallActionReceiver` | 通道 `IMPORTANCE_HIGH` + `VISIBILITY_PUBLIC` 且**不出声**（铃声由 CallManager 自己播，避免双响）；广播型按钮锁屏点一下即生效、不需解锁 |

#### 1.2 `call/`（1 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `call/AppCoreCall.kt` | 411 | `AppCore` 的通话 / 剪贴板扩展函数（拆分范式样板） | `internal fun AppCore.onAppResumed` / `onAppPaused` / `onWindowFocus` / `onUserLeaveHint` / `pushClipIfChanged` / `writeClipboard` / `startCall` / `startCallWithConsent` / `acceptCall` / `rejectCall` / `hangupCall` / `cancelCall` / `addCallRecord` / `formatDuration` / `toggleCallSpeaker` / `toggleCallChannel` / `setVideoRemoteRot` / `setVideoLocalRot` / `videoRemoteRotFor` / `videoLocalRotFor` / `rotOwnerKey` / `peerSupportsVideo` / `startVideoCall` / `switchCamera` / `toggleMute` | 扩展函数**只能访问 public/internal 成员**，不能碰 private；这是 AppCore 继续拆分时可复用的唯一范式 |

#### 1.3 `data/`（4 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `data/MsgItem.kt` | 158 | 消息模型与文案工具 | `data class MsgItem`；`enum Dir` / `Kind`（含 `CALL`）/ `Status`（含 `DENIED`）；`object TextUtil`：`humanSize` / `speed` / `middleEllipsis` / `headTail` / `time` / `isTruncated` | `Dir/Kind/Status` 的 `.name` 写进 `history/*.jsonl`、读回用 `valueOf` → R8 必须保枚举 name（见 `rules.keep`） |
| `data/Prefs.kt` | 788 | SharedPreferences 封装：设备分组 / 备注 / 旋转角 / 端口 / 开关 / 一次性迁移 | `data class Peer` / `PeerGroup`；`class Prefs`：`convKeyOf` / `bindDevice` / `groups` / `groupOfIp` / `upsertPeer` / `setRotRemoteOf` / `setRotLocalOf` / `rotRemoteOf` / `rotLocalOf` / `pruneStaleIps` / `resetRotSchemeOnce` / `resetRotRemoteOnce` / `deviceId` / `deviceName` | `convKeyOf` 是「地址 → 组键」的**唯一解析点**；旋转角按「设备 + 前/后摄」两套分开存 |
| `data/Store.kt` | 439 | 落盘：会话 JSONL / 待发队列 / 半成品续传 | `class Store`：`history/<组键>.jsonl`、`pending.jsonl`、`partials.json`；注入 `keyResolver`；`mergeLater` 去重 | 读旧文件依赖枚举 name；半成品按 key 记 uri（续传依据） |
| `data/TrustedPeers.kt` | 226 | 已知设备名单（后台连接闸门 / 信任名单） | `class TrustedPeers`：`summary` / `count` / `clear` / `isTrusted` / `note` | 清空聊天记录**不影响**此名单 |

#### 1.4 `net/`（8 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `net/Link.kt` | 1815 | TCP 多设备独立长连接核心：会话、协议分发、收发入口、数据流量确认 | `class Link`；`enum State{STOPPED,IDLE,CONNECTING,CONNECTED}`；`interface Listener`；`class PeerCaps`；`internal inner class Session`；`handleFrame` / `onHello` / `refreshState` / `sessionByGroup` / `connectTo` / `selectAndConnect` / `sendText` / `sendClip` / `sendFile` / `sendFolder` / `ensureDataConsent` / `sendVideoTo` / `flushPending` / `deleteMessages` / `hasActiveTransfer` | `Listener` 是 10+ 回调的大接口，AppCore 匿名实现它 → 改签名波及 AppCore.init；`Session` 是 **inner class**（持有 Link 引用） |
| `net/LinkConn.kt` | 396 | Link 扩展：连接 / 保活重连 / 监听连接循环 / 读循环 / 心跳 | `internal fun Link.scheduleKeepAlive` / `noteSessionDrop` / `cancelKeepAlive` / `acceptLoop` / `localLanIpv4` / `connectLoop` / `waitHandshake` / `adopt` / `readLoop` / `heartbeatLoop` / `sendOrDrop` / `dropSession` | 线程红线：`readLoop` 是唯一解码线程，只做入队 |
| `net/LinkSend.kt` | 643 | Link 扩展：发送管线 / 断点续传 / MD5 核对 | `internal fun Link.enqueueFileSend` / `wireListFits` / `doSendFolder` / `doSendFolderInner` / `negotiateResume` / `md5Of` / `startVerifyWatch` / `onFileVerify` / `compareVerify` / `digestPrefix` / `doSendFile` / `doSendFileInner` / `openSource` | 1MB 分片；断点续传走 hsync 协商 |
| `net/LinkReceive.kt` | 834 | Link 扩展：接收管线 Rx/DirRx / 双缓冲落盘 / 文件夹闭环 | `internal fun Link.startWriterThread` / `handOffFullBuffer` / `appendToRx` / `flushRxNow` / `finishRx` / `cancelRx` / `abortRx` / `createRx` / `onFileQuery` / `onFileResume` / `onFileFrame` / `onChunk` / `onDirMeta` / `onDirEnd` / `onDirMissing` / `finishDirReceiving` / `pruneDirs` | `dir_meta`/`dir_end`/`dir_missing` 构成文件夹完整性闭环 |
| `net/LinkSync.kt` | 323 | Link 扩展：待发队列 / hsync 历史同步 | `internal fun Link.resendPendingFile` / `queuePending` / `flushPendingNow` / `startHsync` / `onHsyncMids` / `onHsyncPull` / `onHsyncMsg` / `resendHistoryFile` / `hsyncSyncable` / `onRemoteMsgDel` | 「清空」必须逐条删除，否则对端一连上 hsync 又把文本/图片补回来 |
| `net/Discovery.kt` | 312 | UDP 局域网广播发现 | `class Discovery`：`scanOnce` / `subnetPrefixOf`；UDP 28766 | 启动时主动扫一轮（仅本机在 192.168.* 时生效），之后纯被动 |
| `net/Protocol.kt` | 593 | 协议 v2 帧编解码（4B 长度 + JSON/二进制子类型）+ 数据流量文案 | `object Protocol`：常量 `VIDEO_FLAG_CONFIG` / `VIDEO_FLAG_KEYFRAME`；`write` / `writeChunk` / `writeAudio` / `writeVideo` / `ping`；`object DataConsent` / `DataConsentText` | 二进制帧与 JSON 控制帧分流；写都过 `Session.writeLock` |
| `net/SpeedMeter.kt` | 52 | 传输速率计量（供 UI 显示） | `class SpeedMeter` | 与 `AppCore.SPEED_FRESH_MS = 1500` 过期窗口配套 |

#### 1.5 `ui/`（9 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `ui/ChatScreen.kt` | 1927 | 主界面 Composable：侧栏 + 聊天区 + 通话/视频全屏 + 拟合矩阵 | `fun ChatScreen(onPickFile,onPickFolder)`；`SidePanel` / `HeaderBar` / `AddressMenu` / `LongHamburger` / `Banner` / `CallBanner` / `IncomingCallDialog` / `BottomArea` / `SendButton` / `MenuIcon` / `InputBar` / `VideoLevelPickDialog` / `VideoCallScreen` / `RoundControl` / `RoundTextControl` / `BigTransferHint`；`internal fun videoFitMatrix`；`internal val TOP_ROW_H`；`internal fun sendTextLineHeight`；`Modifier.drawerSwipe` | 侧栏动画**自驱动**（本机系统 animation scale=0，`animate*AsState` 会瞬跳）；`sideAnim` 状态必须留在 ChatScreen 根层，否则子组件退出组合被重置 |
| `ui/Bubble.kt` | 451 | 消息气泡（文本/文件/文件夹/图片/剪贴板/通话记录） | `fun MessageBubble`；`FileBubbleBody` / `TransferLine` / `DenyRetryIcon` / `statusSuffix` | 整气泡统一 `combinedClickable`（子组件不再各自消费点击）；长按弹小菜单，复制文本仅纯文本消息有 |
| `ui/DeviceList.kt` | 524 | 左侧设备列表 + 编辑设备弹窗 | `fun DeviceListPanel`；`fun PeerEditDialog`；`LocalIpBlock` / `DeviceRow`；`internal fun middleShorten` | `BoxWithConstraints` 本质是 Box（子项叠放）→ 里面必须再套 `Column`，否则多行 IP 糊成一坨；`middleShorten` 与聊天顶栏同口径 |
| `ui/Icons.kt` | 292 | 手绘矢量图标（去 emoji，等价 SVG） | `internal enum Glyph`；`internal fun GlyphIcon`；`drawClipboard` / `drawFolder` / `drawFile` / `drawCheck` / `drawWarn` / `drawImageGlyph` / `drawClose` / `drawTriangleDown` / `drawGear`；`internal val SETTINGS_GEAR_SIZE` | 固定 24×24 设计稿坐标系；与文字同行**必须** `Row(verticalAlignment=CenterVertically)`，不能塞进字符串 |
| `ui/ImageViews.kt` | 372 | 图片解码 / 缩略图 / 全屏查看器 | `fun ImageThumb`；`fun ImageViewerDialog`；`fun rememberImageSource`；`fun isImageName` | 解码失败按 0/250/500/1000ms 重试 4 次；`readyKey`（收完才变）驱动重新解码，修「要切窗口才显示」 |
| `ui/SettingsSheet.kt` | 239 | 设置弹窗（端口 / 剪贴板 / 保活 / 存储 / 已知设备 / 日志 / 清空） | `fun SettingsDialog`；`A11yRow` / `SwitchRow` | 清空为**逐条删除**（与 PC 端同口径）；A11yRow 读无障碍开关状态并在 ON_RESUME 刷新 |
| `ui/ShareDialog.kt` | 184 | 系统分享后的目标设备选择框 | `fun ShareTargetDialog`；`PeerChoiceRow` / `ThinDivider` | 状态在 `AppCore.pendingShare`，冷启动与后台唤起两条路径落同一弹窗 |
| `ui/Theme.kt` | 31 | Compose 主题 | `fun PPTheme` | 继承 `Material.Light.NoActionBar` |
| `ui/BootPermDialog.kt` | 91 | 冷启动权限说明弹窗 | `fun BootPermDialog` | 与 `BootPerms.list()` 顺序严格对齐 |

#### 1.6 `util/`（8 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `util/BootPerms.kt` | 192 | 启动权限清单模型（运行时权限 + 特殊权限） | `object BootPerms`：`list` / `granted` / `settingsIntent`；`enum PermKind`；`class BootPerm`（id/title/kind/runtime） | 逐个申请，**不能批量**（部分 ROM 会合并成不可读的长列表） |
| `util/Clip.kt` | 106 | 剪贴板读写 + sha1 去重 | `object Clip`：`read` / `sha1` | 读剪贴板需应用在前台/持有焦点 |
| `util/ClipCaptureService.kt` | 45 | 无障碍保活服务（不读屏幕/剪贴板） | `class ClipCaptureService`；`isEnabled` | `canRetrieveWindowContent=false`，事件一律忽略 |
| `util/FileIO.kt` | 348 | 落盘 / MIME / SAF 目录树枚举 / 文件名清洗 | `object FileIO`：`Sink` / `TreeFile` / `TreeScan`；`createSink` / `appendSink` / `deletePartial` / `uniqueFileName` / `uniqueFolderName` / `listTree` / `walkTree` / `sanitize` / `mimeOf` / `queryName` / `sizeOf` / `isImageFileName`；`DOWNLOAD_SUBDIR="手机电脑互传"` | 枚举失败**绝不冒充空目录**（计入 `skipped`）；Windows 保留名 / emoji / 结尾点空格清洗；重名判据按实况（`liveMediaConflict` 清幽灵条目、回退目录 `createExclusiveIn` 原子换号）；`isImageFileName` 三处（本处 / ImageViews / PC 端）同口径 |
| `util/LanGate.kt` | 138 | 网络门禁：是否在私网、是否蜂窝 | `object LanGate`：`Info`；`check` / `onCellular` / `isLanPeerIp` | 唯一反射点：`WifiManager.getMethod("isWifiApEnabled")`，作用在平台类，R8 不会动 |
| `util/LocalIps.kt` | 178 | 本机地址枚举与排序 | `object LocalIps`：`list` | 顺序：局域网 v4 → 169.254 → 内网 v6 一个 → 公网 v6 |
| `util/OpenFile.kt` | 124 | 打开收到的文件 / APK 安装引导 | `object OpenFile`：`openReceivedFile` | APK 安装需 `REQUEST_INSTALL_PACKAGES` + 用户开「允许安装应用」 |
| `util/ShareIn.kt` | 70 | 解析系统分享 Intent | `object ShareIn`：`parse`；`SharedPayload` / `SharedItem` | 非分享 Intent（ACTION_MAIN 等）直接滤掉返回 null |

#### 1.7 `video/`（6 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `video/VideoCall.kt` | 747 | 视频通话会话编排（发送半 + 接收半 + 自适应 + 反馈） | `class VideoCall`：`startSending` / `stopSending` / `onRemoteCfg` / `onRemoteFrame` / `attachRemoteSurface` / `attachLocalPreview` / `detachLocalPreview` / `switchCamera` / `stop` / `summary`；`outDim` / `doApplyLevel` / `restartEncoder` / `onCaptureAngleChanged` / `onOrientationFlipped` / `sendCfgNow` / `handleEncoded` | **切档必须在独立线程**：`QualityController.onChange` 在编码器输出线程回调，就地 `stop()` 等于自己 join 自己 → 超时后 release 还在跑的 codec（崩/花）；换场必须清零远端编码参数（否则新通话黑屏） |
| `video/VideoCapture.kt` | 413 | Camera2 封装（枚举/开关/切摄/方向/吸附尺寸） | `class VideoCapture`：`hasCamera` / `hasFront` / `hasBack` / `isFront` / `cameraLabel` / `rotationDegrees` / `pickPreviewSize` / `start` / `updateTargets` / `switchCamera` / `stop`；`onSessionReady` / `onRotationChanged` | **不能用 `ctx.display`**（应用上下文 API 30+ 恒抛）→ display 旋转走 `DisplayManager`；预览尺寸必须吸附到相机支持的流尺寸（854×480 两台机都没有） |
| `video/VideoEncoder.kt` | 211 | HEVC/H.264 硬件编码器（Surface 输入，零拷贝） | `class VideoEncoder`：`start` / `setBitrate` / `requestKeyFrame` / `stop`；`emitCsdFromFormat` | **必须 CBR**（VBR 峰值打爆共用 writeLock）；输出是 Annex-B 裸流，直接喂 PC 端 `ffmpeg -f hevc`；GOP=1s |
| `video/VideoDecoder.kt` | 249 | 硬件解码器（输出到 Surface） | `class VideoDecoder`：`attachSurface` / `prepareIfNeeded` / `prepare` / `enqueue` / `stop`；`decodedFrames` / `droppedFrames` / `lastQueueDelayMs` / `ready` | **入队即返回**（读线程里初始化会阻塞 → 对端 35s 判死）；`prepare` 必须 `@Synchronized`（UI 挂 Surface 与收 cfg 两线程并发 configure/release 会 IllegalArgumentException） |
| `video/VideoQuality.kt` | 367 | 视频档位表 + 编解码器选择 + 升/降档状态机 | `enum VideoLevel`（L0 480P/500k / L1 720P/1.5M / L2 1080P/2M = 推荐·起始基准 / L3 1080P/5M = 超清）；`object VideoCodecs`：`pickEncoderMime` / `findHardwareEncoder` / `findDecoder` / `decodable` / `name` / `mime`；`class QualityController`：`reset` / `onFrameSent` / `onFeedback` | 480P 用 **640×480（VGA）**，不用 854×480（相机 StreamConfigurationMap 无此尺寸且 854 非 16 对齐，硬编会拒）；升档五重闸门；用户选的档 = **上限**，L2↔L3 同分辨率只换码率 |
| `video/GlRotatePipe.kt` | 512 | GL 转正中间层：相机 → GL（转正+等比）→ 编码器 | `class GlRotatePipe`：`start` / `setOutput` / `stop`；`cameraSurface` / `baseRotationDeg` / `frontCam`；`applyGeometry` / `computeEffRot` / `drawFrame` | **所有 EGL/GL 调用必须在私有 `pp-video-gl` 线程**，对外用 CountDownLatch 同步（≤2s）；`uFit` 等比 contain 兜底：即便 out 尺寸选错也只留黑边、绝不变形 |

#### 1.8 `voice/`（3 个）

| 文件 | 行数 | 作用（≤60 字） | 关键类/函数 | 备注坑 |
|---|---|---|---|---|
| `voice/CallManager.kt` | 768 | 通话状态机（语音/视频统一编排） | `class CallManager`：`enum State{IDLE,OUTGOING,INCOMING,TALKING}`；`interface Listener`；`enum AudioChannel{MEDIA,CALL}`；`startOutgoing` / `onIncoming` / `acceptIncoming` / `rejectIncoming` / `onAccept` / `hangup` / `onRemoteEnd` / `onPeerDisconnected` / `onAudio` / `onVideoFrame` / `onVideoCfg` / `setAudioChannel` / `toggleSpeaker` / `toggleMute` / `switchCamera` / `elapsedMs`；`startTalking` / `startVideo` / `startVoiceAutoQuality` / `endCall` | **`startTalking` 的重活必须在独立线程 `pp-call-audio`**：主线程做 = ANR，读线程做 = 卡死会话（对端 35s 判死）；`onPeerDisconnected` 必须显式处理，否则掉线后永久卡 TALKING |
| `voice/VoiceEngine.kt` | 600 | Opus 采集/编码/解码/抖动缓冲播放 | `class VoiceEngine`：`configure` / `startRecord` / `startPlay` / `stopRecord` / `stopPlay` / `stopAll` / `setMuted` / `setMediaOutput` / `onAudioReceived` / `setListener`；`EncodedFrame` / `Listener`；常量 `VOICE_FRAME_MS`(200) / `VOICE_FRAME_MS_LOWLAT`(20) / `VIDEO_FRAME_MS`(40) / `BITRATE_VOICE`(64k) | 帧长是**收发硬对称常量**，只有对端也声明才可启用 20ms（协商字段 `af`）；积压超 600ms 快进到最新帧（修「弱网后延迟几十秒」）；静音=不发帧但 seq 仍递增 |
| `voice/VoiceBitrate.kt` | 166 | 纯语音 Opus 码率自适应（64k ⇄ 32k） | `class VoiceBitrateController`：`reset` / `onFrameSent` / `evaluate`；`HI` / `LO` | **仅纯语音通话启用**：视频通话的音频码率跟随视频档位，两套自适应同时改同一路 Opus 会打架 |

#### 1.9 构建与配置（5 个）

| 文件 | 行数 | 作用（≤60 字） | 关键点 | 备注坑 |
|---|---|---|---|---|
| `build.gradle.kts`（根） | 5 | 顶层构建脚本 | 仅 `alias(libs.plugins.android.application) apply false` 与 `kotlin.compose` | 无子项目配置 |
| `settings.gradle.kts` | 26 | 仓库与模块声明 | `pluginManagement` 白名单；`rootProject.name="PhonePcTransfer"`；`include(":app")` | `FAIL_ON_PROJECT_REPOS` |
| `gradle/libs.versions.toml` | 28 | 版本目录 | `agp=9.3.0`、`kotlin=2.2.10`、`composeBom=2025.12.00`、`activityCompose=1.8.0` | 无 compose-plugin 版本（由 kotlin 插件带动） |
| `app/build.gradle.kts` | 89 | 应用模块：签名 / 版本 / R8 / 依赖 | `namespace="com.phonepctransfer"`；`applicationId` 同；`minSdk=28`、`targetSdk=37`、`compileSdk=release(37)`；`versionCode=10005`、`versionName="1.0.5"`；`optimization.enable = (property("r8") ?: "true")`；`keepRules { files.add(file("keepRules/rules.keep")) }`；依赖 compose-bom + `libs/concentus-1.0.2.jar` | **keepRules 必须显式 `files.add`**：只写 `enable=true` 时规则文件是死的（AGP 9 的 optimization 块语义）；`-Pr8=false` 可出非 R8 包做真机回归（~39MB） |
| `app/keepRules/rules.keep` | 26 | R8 自定义 keep 规则 | `-keepnames class com.phonepctransfer.** { *; }`；`-keepclassmembers,allowoptimization enum com.phonepctransfer.** { *; }`；`-keepattributes SourceFile,LineNumberTable` | 应用**无反射依赖**（唯一反射在 LanGate，作用于 framework 平台类）；保留名字纯为真机排障可读栈 |

#### 1.10 资源（`res/`）

| 文件 | 行数 | 作用 | 备注 |
|---|---|---|---|
| `res/values/strings.xml` | 18 | `app_name`=手机电脑互传；通知/来电/无障碍文案 | `notif_keep_hint`、`call_notif_*`、`clip_capture_*` |
| `res/values/themes.xml` | 4 | `Theme.PhonePcTransfer` | 继承 `android:Theme.Material.Light.NoActionBar` |
| `res/values/colors.xml` | 4 | `ic_launcher_background`=#0E4C8A | 仅一项 |
| `res/xml/clip_capture_service_config.xml` | 10 | 无障碍服务配置 | `canRetrieveWindowContent=false`，事件忽略（仅保活） |
| `res/xml/file_paths.xml` | 10 | FileProvider 暴露路径 | `external-files-path:received` / `external-cache-path:.` / `files-path:.` |
| `res/xml/network_security_config.xml` | 6 | 明文流量许可 | `cleartextTrafficPermitted="true"`（局域网 socket，非 HTTP） |

`res/drawable/ic_*.xml`（11 个，逐一点名，均为矢量 drawable）：

| 文件 | 行数 | 用途 |
|---|---|---|
| `ic_call.xml` | 9 | 接听 / 来电通知 smallIcon |
| `ic_call_end.xml` | 9 | 挂断 / 拒绝 |
| `ic_file.xml` | 10 | 文件 |
| `ic_folder.xml` | 10 | 文件夹 |
| `ic_launcher_foreground.xml` | 40 | 自适应图标前景 |
| `ic_rotate.xml` | 16 | 旋转（远端画面） |
| `ic_rotate_self.xml` | 19 | 旋转（本地预览） |
| `ic_switch_camera.xml` | 23 | 切换前后摄 |
| `ic_videocam.xml` | 10 | 视频通话 |
| `ic_volume_off.xml` | 9 | 静音 |
| `ic_volume_on.xml` | 9 | 取消静音 |

> 注：`mipmap/ic_launcher`、`mipmap/ic_launcher_round` 由 `colors.xml` 的 `ic_launcher_background` + `ic_launcher_foreground` 合成（清单引用）。

#### 1.11 `tools/` 与外挂 `build-ext/`

| 文件 | 行数/大小 | 用途 | 备注 |
|---|---|---|---|
| `tools/fit_matrix_check.py` | 162 | 纯数学复刻 `ChatScreen.videoFitMatrix()` 的「等比 + 留边 + 居中」，校验三条不变量；并对照旧实现（只旋转）暴露的压扁问题 | 对齐 `android.graphics.Matrix` 的 **post 前乘**语义；`check_degenerate` 验证「缓冲区尺寸未定 → 回落单位矩阵」；用真实尺寸（a16 1080×2400 / 本地小窗 286×412）跑用例 |
| `build-ext/` | 本地目录（未入库） | Shizuku 相关的第三方参考源码、预编译 AAR/APK 与真机截图存档；不参与 `:app` 编译（`settings.gradle.kts` 只 `include(":app")`） | 需要时按 RikkaApps/Shizuku-API 上游自行获取 |

---

### 二、三个大文件的拆分方案

> 总原则：**只挪代码、不改逻辑、不改公开 API 签名**。Kotlin **没有 partial class / partial object** —— 这是下面所有方案的硬约束。

#### 2.1 `ui/ChatScreen.kt`（1927 行）

##### 2.1.1 现状测绘（顶层声明清单 + 行数区间）

| 行号区间 | 内容 |
|---|---|
| 1–110 | imports + 文件头说明 |
| 111–284 | `@Composable fun ChatScreen(onPickFile, onPickFolder)`：根 `Surface→Row`（`SidePanel` + `Column(HeaderBar/CallBanner/Box(LazyColumn)/BottomArea)`）+ 全部弹窗挂载点（SettingsDialog / PeerEditDialog / DeleteMessageDialog / ImageViewerDialog / ClearHistoryDialog / ClearWarnDialog / DataNetConfirmDialog / IncomingDataConfirmDialog / ShareTargetDialog / IncomingCallDialog / BigTransferHint / VideoCallScreen） |
| 296–318 | `private fun BigTransferHint()` |
| 319 / 322 / 325 | `private const val BIG_HINT_MS=5000L`；`private val LIST_W=138.dp`；`private const val SIDE_ANIM_MS=190f` |
| 342–375 | `private fun SidePanel(anim, onSettings, onPick, onEdit, onAdd)` |
| 377–393 | `private fun DeleteMessageDialog` |
| 395–437 | `private fun ClearHistoryDialog(plan: ClearPlan, …)` |
| 439–454 | `private fun ClearWarnDialog` |
| 456–481 | `private fun DataNetConfirmDialog` |
| 483–507 | `private fun IncomingDataConfirmDialog` |
| 508 / 524 / 534 | `private val HAMBURGER_SLOT=52.dp`；`internal val TOP_ROW_H=36.dp`；`private const val IP_HEADER_BUDGET=20` |
| 562–688 | `private fun HeaderBar(onToggleList, showRemark)` |
| 690–702 | `private fun connectedColor()` |
| 704–736 | `private fun AddressMenu(open, g, onDismiss)` |
| 738–763 | `private fun LongHamburger()` |
| 764–788 | `private fun Modifier.drawerSwipe(open, onOpen, onClose)` |
| 790–815 | `private fun Banner(text, warn)` |
| 817–930 | `private fun CallBanner()` |
| 932–1019 | `private fun IncomingCallDialog()` |
| 1021–1057 | `private fun BottomArea(compact, onPickFile, onPickFolder)` |
| 1059–1078 | `@Composable internal fun sendTextLineHeight(): Dp`（跨文件 API，`DeviceList` 用） |
| 1080–1107 | `private fun SendButton(compact, onClick)` |
| 1109–1116 | `private fun MenuIcon(res)` |
| 1118–1252 | `private fun InputBar(compact, onPickFile, onPickFolder)` |
| 1254–1278 | `private fun VideoLevelPickDialog` |
| 1279–1288 | `private fun kbpsText(kbps)` |
| 1290–1633 | `private fun VideoCallScreen()`（视频全屏；内含本地/远端 `TextureView` 与变换） |
| 1635–1665 | `private fun RoundControl(iconRes, desc, onClick)` |
| 1667–1718 | `private fun RoundTextControl(label, desc, active, onClick)` |
| 1719–1740 | `@Composable internal fun videoFitMatrix(...)`（跨文件 API，`fit_matrix_check.py` 对照） |
| 1741–1745 | `private const val TAG_TF`；`private var lastRemoteTfKey`；`private var lastLocalTfKey`（**文件级可变全局**） |
| 1755–1772 | `private fun logStProbe(...)` |
| 1773 / 1781 / 1789 | `private const val ROT_SIGN=-1`；`LOCAL_PREVIEW_EXTRA=90`；`LOCAL_FRONT_EXTRA_ROT=180` |
| 1792–1841 | `private fun normDeg` / `videoCamFront` / `videoPeerFront` / `adaptiveRemoteRot` / `adaptiveLocalRot` |
| 1842–1857 | `private class TvRef` |
| 1858–1879 | `private fun stTransposed(st)` |
| 1880–1908 | `private fun applyRemoteTransform(...)` |
| 1909–1927 | `private fun applyLocalTransform(...)` |

##### 2.1.2 建议拆成哪些新文件 + 各装什么

| 新文件 | 装什么（源行号） | 预计行数 |
|---|---|---|
| `ui/ChatScreen.kt`（保留） | `fun ChatScreen` 根布局 + 全部弹窗挂载点 + `SidePanel` + 布局常量（LIST_W/SIDE_ANIM_MS/HAMBURGER_SLOT/TOP_ROW_H） | ~520 |
| `ui/ChatDialogs.kt` | `DeleteMessageDialog` / `ClearHistoryDialog` / `ClearWarnDialog` / `DataNetConfirmDialog` / `IncomingDataConfirmDialog`（377–507） | ~150 |
| `ui/ChatHeader.kt` | `HeaderBar` / `connectedColor` / `AddressMenu` / `LongHamburger` / `Modifier.drawerSwipe` / `Banner` / `IP_HEADER_BUDGET`（508–815） | ~300 |
| `ui/ChatCallUi.kt` | `CallBanner` / `IncomingCallDialog`（817–1019） | ~210 |
| `ui/ChatInput.kt` | `BottomArea` / `sendTextLineHeight` / `SendButton` / `MenuIcon` / `InputBar` / `kbpsText`（1021–1288） | ~270 |
| `ui/VideoCallScreen.kt` | `VideoCallScreen` / `RoundControl` / `RoundTextControl` / `BigTransferHint` / `BIG_HINT_MS`（296–318, 1290–1718） | ~480 |
| `ui/VideoFit.kt` | `videoFitMatrix` + 全部旋转/矩阵辅助 + `TvRef`（1719–1927） | ~210 |

新增文件数：**6 个**（ChatDialogs / ChatHeader / ChatCallUi / ChatInput / VideoCallScreen / VideoFit）。

##### 2.1.3 共享可变状态归属（必须只有一个持有者）

- **必须留在 `ChatScreen.kt` 根 composable 的本地状态**：`showSettings`、`listOpen`、`sideAnim`、`editing`、`adding`、`viewingImage`、`deleting`、`listState`。其中 **`sideAnim` 最关键**：它用 `withFrameNanos` 自驱动画，值必须在根层持有并通过 `anim = { sideAnim }`（**lambda，不是值**）下发给 `SidePanel`；若挪进 `SidePanel` 内部，收起动画跑完后 `SidePanel` 退出组合 → 状态被重置为初值 → 下次展开瞬跳。
- **业务状态全部在 `AppCore`（唯一持有者）**，子文件只读（`AppCore.messages` / `peerGroups` / `callState` / `videoActive` / `clearConfirm` …），不得在新文件里再建一份。
- `VideoCallScreen` 里的 `TextureView`/`SurfaceTexture` 引用（`TvRef`）是视频会话运行期的**单例资源**，随 `VideoFit.kt` 一起走即可，但**不能**在 `ChatScreen.kt` 与 `VideoFit.kt` 各留一份 —— `TvRef` 必须只有一处定义。

##### 2.1.4 哪些必须留原文件及理由

| 必须留 | 理由 |
|---|---|
| `fun ChatScreen` | `MainActivity.setContent { ChatScreen(...) }` 直接调用，公开 API，签名不可变 |
| `SidePanel` | 与根 `Row` 的 `sideAnim` 直接耦合（deferred read），挪走会把动画状态一起带出根层 |
| `internal val TOP_ROW_H` / `internal fun sendTextLineHeight` | 跨文件 API（`DeviceList` 引用），必须保持 `internal`；挪文件后引用方补 `import` |
| `internal fun videoFitMatrix` | 跨文件 API（`fit_matrix_check.py` 对照 + `VideoCallScreen` 使用），`internal` 不可降级 |
| `private var lastRemoteTfKey` / `lastLocalTfKey` | 文件级 `private var` 编译进 `ChatScreenKt` 静态字段，是**进程级单例**（变换去重日志用）；挪到 `VideoFit.kt` 会变成 `VideoFitKt` 的静态字段，语义一致但需确认无他处引用 |

##### 2.1.5 风险与回退

- **R8 keep**：`rules.keep` 用 `-keepnames class com.phonepctransfer.** { *; }` 全包覆盖，拆文件不改类名前缀 → 无影响。Compose 函数的 `@Composable` 由编译器生成，无需 keep。
- **AndroidManifest 引用**：`ChatScreen` 不被 manifest 引用（只被 `MainActivity` 代码引用）→ 无影响。
- **反射**：无。
- **Compose 重组作用域**：拆出的子 composable 各自独立 scope；`SidePanel` 的 `anim` 参数**必须保持 lambda**（deferred read），否则每帧会带上聊天区一起重组（性能回退）。`LaunchedEffect` 不跨文件共享。
- **协程作用域**：无共享 scope，安全。
- **可见性变更**：`private` 成员跨文件不可见 → 被两个以上文件用的必须改 `internal`（如 `connectedColor` 若被 ChatHeader 与 ChatCallUi 共用）。`TOP_ROW_H` / `sendTextLineHeight` / `videoFitMatrix` 已是 `internal`，无需改。
- **回退**：纯挪动、无逻辑改动 → `git checkout -- <单文件>` 即可回退；建议一次只挪一个 composable 组、编译通过再挪下一组。

##### 2.1.6 验证步骤

1. 沙箱：`./gradlew :app:assembleRelease`（默认带 R8；必要时 `-Pr8=false` 先排除 R8 干扰）。
2. 真机（交用户）：侧栏右滑开/左滑关/点空白关 + 收起动画不瞬跳；发送文本/文件/文件夹；长按气泡菜单；设置弹窗；清空确认；分享进来选设备；语音来电弹窗；视频通话全屏 + 旋转/切摄/静音。

---

#### 2.2 `AppCore.kt`（1958 行）

##### 2.2.1 现状测绘（`object AppCore` 成员 + 行号区间）

| 行号区间 | 内容 |
|---|---|
| 1–59 | imports + 文件头 |
| 60–94 | 常量：`TAG` / `MAX_UI_MSGS` / `LAN_TICK_MS` / `SCAN_WINDOW_MS` / `INBOX_DIR` / `INBOX_TTL_MS` / `BIG_TRANSFER_BYTES` / `BIG_TRANSFER_HINT` / `SHARE_CONNECT_WAIT_MS` / `DATA_CONSENT_WAIT_SEC` |
| 96–124 | 装配字段：`app` / `prefs` / `store` / `trusted` / `clip` / `gate` / `link` / `call` / `discovery` / `main` / `initialized` |
| 126–282 | **Compose 状态**：`messages` / `peers` / `peerGroups` / `selectedPeerIp` / `connectedPeers` / `peerCapsTick` / `linkState` / `peerLabel` / `lanInfo` / `localIp` / `localIps` / `running` / `pendingCount` / `lastLog` / `inputText` / `clearConfirm` / `clearWarn` / `callState` / `callPeerIp` / `callPeerName` / `peerCamTick` / `selfCamTick` / `incomingCall` / `videoActive` / `videoMuted` / `callSpeakerOn` / `callMediaChannel` / `peerSendsVideo` / `scanMessage` / `scanning` |
| 284–320 | 速率状态：`speeds` / `speedsAt` / `SPEED_FRESH_MS` / `speedBpsOf` / `clearSpeed` |
| 321–342 | `bigTransferHint` / `bigTransferHintTick` / `dismissBigTransferHint` / `bigHintMid` |
| 343–362 | `clipSyncOn` / `appVisible` / `foregroundActive` |
| 364–672 | **`init(ctx)`**：装配 + `Link.Listener`（462–591）+ `CallManager.Listener`（593–670）两个大匿名对象 |
| 673–679 | `internal inline fun ui(block)`（切主线程执行） |
| 680–759 | `refreshLan` / `startRun` / `stopRun` / `lanTick`（`Runnable`） |
| 760–853 | `startScan` / `autoConnectLast` |
| 854–899 | `reloadPeers` / `convKeySnapshot` / `reopenConversationIfKeyChanged` / `refreshGroups` |
| 901–951 | `currentGroup` / `isGroupConnected` / `switchGroupIp` / `onPeerDiscovered` |
| 953–1020 | `connectTo(Peer)` / `connectToGroup` / `connectTo(ip,label)` / `reloadConversation` |
| 1022–1104 | `savePeer` / `saveGroupRemark` / `addGroupIp` / `removeGroupIp` / `deleteGroup` / `deletePeer` / `isGroupOnline` |
| 1106–1126 | `myDeviceName` / `peerLabelOf` |
| 1128–1153 | `midPeer`（LRU 邻接表）/ `belongsToCurrent` |
| 1155–1287 | `maybeHintBigTransfer` / `addMessage` / `updateMessage` / `removeMessage` / `deleteMessage` / `merge` / `requireSelectedPeer` |
| 1289–1369 | `sendText`(×2) / `sendFile` / `sendFileInternal` / `sendFolder` / `retryFileSend` / `moveMessageToLatest` / `cancelFile` / `syncNow` |
| 1371–1471 | 数据流量确认：`DataConsentAsk` / `dataNetConfirm` / `DataConsentIncoming` / `dataNetIncoming` / `onCellular` / `askLocalDataConsent` / `acceptDataNet` / `cancelDataNet` / `finishDataConsent` / `acceptIncomingData` / `rejectIncomingData` / `onDataAskInternal` |
| 1473–1597 | 分享：`pendingShare` / `onShared` / `cancelShare` / `shareTo` / `stashToInbox` / `cleanInbox` |
| 1598–1628 | `onClipSyncModeChanged` / `lastForegroundAt` |
| 1629–1768 | 清空历史：`clearHistory` / `cancelClearHistory` / `confirmClearHistory` / `planConversation`(**private**) / `planAllConversations`(**private**) / `isAddressable` / `syncableKind` / `dismissClearWarn` |
| 1769–1859 | `trustedSummary` / `trustedCount` / `clearTrusted` / `clearPending` / `appendLog` / `appendLogPublic` / `copyForAdd` / `copyTextToClipboard` / `toast` |
| 1861–1899 | `recordPermRequester` / `cameraPermRequester` / `videoEnabled` / `videoMaxLevel` |
| 1900–1923 | **`videoRemoteRot` / `videoLocalRot`**（初值 `-1`，见「备注坑」）/ `videoSession` |
| 1925–1934 | `shortClipPreview` / `isFileMessage` |
| 1936–1944 | `data class ClearPart` |
| 1946–1958 | `data class ClearPlan` |

##### 2.2.2 建议拆成哪些新文件 + 各装什么

> **关键前置结论**：`AppCore` 是 `object`，Kotlin **无 partial object**，不能把「一个 object 的字段」分散到多个文件。因此**所有可变/Compose 状态与装配字段必须留在 `AppCore.kt` 本体**；能被外迁的只有**不直接持有状态的纯逻辑**，且只能用 **扩展函数**（`call/AppCoreCall.kt` 已是此范式的既有样板）。**这意味着 AppCore.kt 无法通过拆分裂变成小文件** —— 它必须继续持有全部状态，预计外迁后仍有 ~1400+ 行。

| 新文件 | 装什么（源行号） | 前置改动 | 预计行数 |
|---|---|---|---|
| `AppCore.kt`（保留） | object 声明 + 全部常量/装配字段/**全部 Compose 状态** + `init` + `ui` + 网络运行 + 连接 + 设备管理 + 消息管理 + 发送 | 无 | ~1450 |
| `data/ClearModels.kt` | `data class ClearPart` / `ClearPlan`（1936–1958） | 无（顶层 data class 可自由挪） | ~25 |
| `AppCoreClear.kt` | `planConversation` / `planAllConversations` / `isAddressable` / `syncableKind` / `clearHistory` / `confirmClearHistory`（1629–1750, 1757） | `planConversation`/`planAllConversations` 由 **private → internal**（扩展函数不能访问 private） | ~180 |
| `AppCoreShare.kt` | `onShared` / `cancelShare` / `shareTo` / `stashToInbox` / `cleanInbox`（1477–1597） | 同上（若拆，需把触达的 private 放宽） | ~130 |
| `AppCoreUtil.kt` | `shortClipPreview` / `isFileMessage` / `copyForAdd` / `copyTextToClipboard` / `toast` / `appendLog`（1769–1859, 1925–1934） | 触达的 private 放宽为 internal | ~120 |
| `call/AppCoreCall.kt`（已有） | 通话 + 剪贴板（保持） | 无 | 411 |
| `call/AppCoreCallRecord.kt`（可选） | `addCallRecord` / `formatDuration` | 无 | ~40 |

新增文件数：**建议 2–4 个**（`data/ClearModels.kt` + `AppCoreClear.kt` + 可选 `AppCoreShare.kt` / `AppCoreUtil.kt`）。

##### 2.2.3 共享可变状态归属（必须只有一个持有者）

- **全部 Compose 状态**（`mutableStateOf/List/Map`）、装配字段（`prefs`/`store`/`trusted`/`clip`/`gate`/`link`/`call`/`discovery`/`main`/`initialized`）、`midPeer`/`speeds`/`speedsAt`/`lastForegroundAt`/`bigHintMid`/`convKeySnapshot` 等 —— **唯一持有者 = `AppCore` object 本体，一律不外迁**。
- `videoRemoteRot` / `videoLocalRot` 也留本体（且初值恒 `-1`，**绝不能**写成读取 `prefs` —— object 属性初始化器在 `<clinit>` 期执行，`prefs` 尚未注入）。
- 外迁的扩展函数**只读/只经 public/internal API 修改**这些状态；**严禁**把某一组状态搬到新 `object` 再由扩展函数读写（会造成两份状态 + lateinit 生命周期错乱）。

##### 2.2.4 哪些必须留原文件及理由

| 必须留 | 理由 |
|---|---|
| `object AppCore` 声明 | 全局单例，无 partial 语法 |
| 全部 Compose 状态 + 装配字段 | 「唯一持有者」约束；且 Composable 直接读这些状态驱动重组 |
| `init(ctx)` | 装配顺序敏感（`prefs→store→trusted→clip→gate→link→call`），且内含 `Link.Listener`/`CallManager.Listener` 两个匿名对象，闭包捕获大量状态 —— 一拆就失效 |
| `ui()` | 所有异步回调都依赖它切主线程；是 object 内部基础设施 |
| `linkState` / `callState` / `videoActive` / `incomingCall` / `dataNetConfirm` / `dataNetIncoming` / `clearConfirm` / `clearWarn` / `pendingShare` | 被 `ChatScreen` 等直接读取的公开 Compose 状态 |

##### 2.2.5 风险与回退

- **Kotlin 无 partial object**：这是本方案最大的硬约束 —— **不能**用「多文件拼一个 object」。替代方案只有两种：① **扩展函数**（本项目已在用，仅能访问 public/internal 成员，无法触碰 private 状态）② 把整块逻辑（连同状态）搬进一个**新 class** 再由 AppCore 委托 —— 但那等于改架构、违反「只挪代码不改逻辑」，**不做**。要如实告知：AppCore 本体瘦身空间有限。
- **可见性变更**：外迁的函数若原为 `private`，必须先改 `internal`（公开 API 签名不变，但可见性变宽，属可接受的"挪动"成本）。
- **R8 keep**：`-keepnames class com.phonepctransfer.** { *; }` 覆盖全部；`<clinit>` 顺序不受「新增顶层 data class」影响。但**主 object 的属性初始化器顺序必须原样保留**（尤其 `videoRemoteRot=-1`）。
- **反射**：无。
- **Compose 重组**：AppCore 状态被外部读取的入口不变，重组行为不变；外迁的只是被调用的函数，不影响快照订阅。
- **协程作用域**：AppCore 用 `main` Handler + `ui{}`（非 CoroutineScope），扩展函数仍调 `ui{}`（internal），无 scope 归属问题。
- **回退**：逐块外迁、每块编译通过再下一块；回退为 `git checkout --` 对应文件。

##### 2.2.6 验证步骤

1. 沙箱：`./gradlew :app:assembleRelease`。
2. 真机（交用户）：冷启动权限弹窗 → 连接设备 → 发文本/文件/文件夹 → 断点续传 → 语音/视频通话 → 剪贴板同步 → 分享进来发送 → 清空聊天记录（逐条删除）→ 已知设备名单清空。

---

#### 2.3 `net/Link.kt`（1815 行）

> **重要现状**：`Link.kt` **已经完成过一轮拆分** —— `LinkConn.kt`(396) / `LinkSend.kt`(643) / `LinkReceive.kt`(834) / `LinkSync.kt`(323) 四个扩展函数文件已把「连接/发送/接收/同步」管线外迁。剩下的 `Link.kt` 主要是**类本体**（状态、`Listener`、`Session`、握手、协议分发、发送入口）。继续拆分的空间比另外两个文件小。

##### 2.3.1 现状测绘（顶层声明 + 行号区间）

| 行号区间 | 内容 |
|---|---|
| 1–53 | imports + 文件头 + `class Link(internal val ctx, internal val prefs)` |
| 55 | `enum class State { STOPPED, IDLE, CONNECTING, CONNECTED }` |
| 57–160 | `interface Listener`（`onState`/`onPeersChanged`/`onMessageAdded`/`onMessageUpdated`/`onMessageRemoved`/`onHsyncApplied`/`onClipToClipboard`/`onPeerClipEmpty`/`shouldSkipPeerClip`/`onLog`/`onCallInvite`/`onCallAccept`/`onCallInviteVideo`/`onCallAcceptVideo`/`onVideoFrame`/`onVideoCfg`/`onVideoFb`/`onVideoReq`/`onCallReject`/`onCallBusy`/`onCallEnd`/`onCallCancel`/`onCallAudio`/`onDataAsk`/`onMessageMoved`） |
| 162–188 | `class PeerCaps(...)` |
| 189–378 | 状态字段：`localVidCap` / `localCamCap` / `localVCodecs` / `listener` / `state` / `peerLabel` / `running` / `serverSocket` / `sessions` / `byPeer` / `selectedIp` / `selectedLabel` / `pendingConnectIps` / `onGroupFallback` / `onSpeed` / `speedMeters` / `markSpeed` / `endSpeed` / `groupCandidates` / `clearPendingFor` / `outgoingGate` / `localOnCellular` / `localDataConsent` / `trusted` / `trustContent` / `connectingIp` / `stateNote` / `peerIp` / `reconnectLock` / `acceptThread` / `connectThread` / `heartbeatThread` / `myId` / `myName` |
| 380–522 | **`internal inner class Session`**（`input`/`output`/`writeLock`/`remoteIp`/`peerId`/`peerName`/`handshaked`/`caps`/`lastRx`/`lastTx`/`connectedAt`/`pingOutstanding`/`closed`；`touchRx`/`send`/`sendChunk`/`sendAudio`/`sendVideo`/`tryPing`/`close`） |
| 525–557 | 会话查询：`sessionOf` / `target` / `isConnectedIp` / `isDeviceConnected` / `connectedIps` |
| 559–679 | 生命周期/连接入口：`start` / `stop` / `isConnected` / `selectTarget` / `selectAndConnect` / `connectTo`；`keepAliveExec` / `keepAliveJobs` / `keepAliveFastFails` |
| 702–714 | `fileSendExec` / `fileSendQueueDepth` |
| 715–859 | **`internal fun handleFrame(s, j)`**（协议分发总入口） |
| 860–970 | **`internal fun onHello(s, j)`**（握手协商） |
| 971–1018 | `isSelected` / `setState` / `refreshState` / `notifyPeers` |
| 1019–1063 | 接收表：`rxMap` / `dirMap` / `MAX_KEEP_DIRS` / `resumeWaiters` / `dataAnsBoxes` / `sendExec` |
| 1064–1117 | 发送入口：`sendText` / `sendClip` |
| 1117–1138 | `cancelled` / `dirSendState` / `isCancelled` / `newConsentMid` |
| 1139–1212 | 数据流量确认：`ensureDataConsent` / `askPeerDataConsent` / `replyDataAsk` |
| 1213–1414 | 文件/文件夹发送入口：`sendFile` / `sendFolder` / `LOCAL_DENY_TEXT` / `denyFileByData` / `retryFileSend` |
| 1415–1441 | `sessionForPending` / `sessionByGroup` |
| 1442–1470 | `dirMissingBox` / `DIR_RESEND_ROUNDS` / `DIR_MISSING_WAIT_MS` / `DIR_LIST_MAX_CHARS` / `verifyMap` / `verifyTimeoutMs` |
| 1471–1494 | `cancelFile` |
| 1495–1634 | 音频/视频/通话发送入口：`sendAudioTo` / `sendCallInvite` / `sendCallAccept` / `sendCallReject` / `sendCallBusy` / `sendCallEnd` / `sendCallCancel` / `peerCapsOf` / `resendHello` / `peerVidCap` / `sendVideoTo` / `sendVideoCfg` / `sendVideoFb` / `sendVideoReq` / `currentPeerIp` / `syncNow` |
| 1651–1740 | `store` / `flushPending`(×2) / `deleteMessage` / `canSendMsgDel` / `deleteMessages` / `hasActiveTransfer` |
| 1741–1790 | `lastClipEmitSha1` / `isDuplicateClip` / `emit` / `okUpdate` / `markStatus` / `waitFor` / `wakeUp` / `newMid` / `hex` / `log` |
| 1791–1815 | `companion object`：`PARTIAL_MAX_AGE_MS` / `STALE_RX_MS` / `HSYNC_SCAN` / `DATA_ANS_WAIT_MS` / `MSG_DEL_CHUNK` |

##### 2.3.2 建议拆成哪些新文件 + 各装什么

| 新文件 | 装什么（源行号） | 说明 | 预计行数 |
|---|---|---|---|
| `net/Link.kt`（保留） | `class Link` 本体 + `enum State` + `interface Listener` + `class PeerCaps` + `inner class Session` + 全部状态字段 + 会话查询 + 生命周期/连接入口 + 发送入口(`sendText`/`sendClip`/`sendFile`/`sendFolder`) + `companion object` | 状态唯一持有者 | ~1100 |
| `net/LinkProto.kt` | `handleFrame`(715–859) + `onHello`(860–970) | 二者已是 `internal fun` 成员；外迁为 `internal fun Link.handleFrame/onHello`（可访问 internal 成员）。**不改线程模型** | ~260 |
| `net/LinkIo.kt` | `markSpeed`/`endSpeed`/`groupCandidates`/`clearPendingFor`/`trustContent`/`isSelected`/`setState`/`refreshState`/`notifyPeers`/`sessionForPending`/`currentPeerIp`/`isDuplicateClip`/`emit`/`okUpdate`/`markStatus`/`waitFor`/`wakeUp`/`newMid`/`hex`/`log` | 查询与状态辅助 | ~230 |
| `net/LinkMedia.kt` | `sendAudioTo`/`sendCallInvite`/`sendCallAccept`/`sendCallReject`/`sendCallBusy`/`sendCallEnd`/`sendCallCancel`/`peerCapsOf`/`resendHello`/`peerVidCap`/`sendVideoTo`/`sendVideoCfg`/`sendVideoFb`/`sendVideoReq`（1495–1634） | 音视频/通话发送入口（**公开 API 签名不变**） | ~150 |
| `net/LinkData.kt` | `ensureDataConsent`/`askPeerDataConsent`/`replyDataAsk`/`denyFileByData`/`LOCAL_DENY_TEXT`（1139–1212, 1333–1350） | 数据流量确认 | ~110 |
| `net/LinkPending.kt` | `cancelFile`/`flushPending`(×2)/`deleteMessage`/`canSendMsgDel`/`deleteMessages`/`hasActiveTransfer`（1471–1494, 1651–1740） | 待发/删除/活跃传输查询 | ~130 |

新增文件数：**5 个**（LinkProto / LinkIo / LinkMedia / LinkData / LinkPending）。

##### 2.3.3 共享可变状态归属（必须只有一个持有者）

- **`Link` 实例本身是唯一持有者**。所有状态字段（`sessions`/`byPeer`/`rxMap`/`dirMap`/`cancelled`/`dirSendState`/`verifyMap`/`resumeWaiters`/`dataAnsBoxes`/`dirMissingBox`/`keepAlive*`/`speedMeters`/`selectedIp`/`state`/…）**必须留 `class Link` 内**。
- 外迁的扩展函数**经 `internal` 成员读写**这些状态（它们多数已是 `internal`，满足扩展函数可见性要求）。
- **严禁**把任意一组状态搬进新 `object` 或伴生对象（会出现两份状态）。

##### 2.3.4 哪些必须留原文件及理由

| 必须留 | 理由 |
|---|---|
| `class Link` 声明与构造参数 | 单例装配点（`AppCore.init` 里 `Link(app, prefs)`） |
| `enum State` | `AppCore.linkState`（Compose 状态）与 `TransferService.statusLine()` 直接引用 |
| `interface Listener` | `AppCore.init` 里有匿名实现（~130 行），改签名会波及 AppCore |
| `class PeerCaps` | `Session.caps` 与外层 `peerCapsOf` 的返回类型，公开类型 |
| `inner class Session` | **inner**（持有 Link 引用），是收发线程模型的载体，不外迁 |
| 全部状态字段 | 「唯一持有者」约束 |
| `companion object` 常量 | 被 LinkConn/LinkSync/LinkSend/LinkReceive 引用（`internal`），挪动需注意包内可见性 |
| 公开发送入口 `sendText`/`sendClip`/`sendFile`/`sendFolder`/`connectTo`/`selectAndConnect`/`currentPeerIp` | 公开 API，签名不可变 |

##### 2.3.5 风险与回退

- **R8 keep**：`net` 包被 `-keepnames class com.phonepctransfer.** { *; }` 覆盖 → 无影响。扩展函数编译为静态方法，R8 正常处理。
- **AndroidManifest**：`Link` 不被 manifest 引用 → 无影响。
- **反射**：无（JsonObject 走 `org.json`，无反射构造）。
- **线程模型**：`LinkConn.readLoop` 是唯一解码线程并调用 `handleFrame`；把 `handleFrame`/`onHello` 从成员函数改成扩展函数**不改线程**，但**不得**在扩展函数里新起线程或加锁顺序（`Session.writeLock` + `reconnectLock` 的既定顺序必须保持）。
- **可见性变更**：`handleFrame`/`onHello` 已是 `internal` 成员 → 变扩展函数后可见性等同；但它们内部若调用 `private` 成员（需逐行核对），要先把那些成员放宽为 `internal`。
- **Compose 重组**：net 层无 Compose 状态，无重组风险。
- **协程作用域**：`Link` 用 `Executor`/`reconnectLock`/`Thread`，无 CoroutineScope 归属问题。
- **回退**：纯挪动，`git checkout --` 单文件回退；建议按 LinkProto → LinkIo → LinkMedia → LinkData → LinkPending 顺序逐文件挪、每步编译。

##### 2.3.6 验证步骤

1. 沙箱：`./gradlew :app:assembleRelease`。
2. 真机（交用户）：本机地址发现 → 手动连接/自动连接 → 发文本/文件/文件夹（含 >2GiB 大文件与断点续传）→ 接收（含文件夹完整性闭环）→ 清空逐条删除 + 对端 hsync 不补回 → 语音/视频通话 → 剪贴板同步 → 数据流量确认（收/发两向）。

---

### 三、速查：要改 X → 去哪个文件

| 要改的东西 | 去哪个文件 |
|---|---|
| UI 上多一个状态 / 全局状态新增字段 | `AppCore.kt`（唯一状态持有者；新状态必须是 `mutableStateOf/List/Map` 且放 object 本体） |
| 主界面布局、顶栏、输入框、侧栏、气泡 | `ui/ChatScreen.kt`（布局）/ `ui/Bubble.kt`（气泡）/ `ui/DeviceList.kt`（列表） |
| 消息气泡里的图标 | `ui/Icons.kt`（`Glyph` + `GlyphIcon`，加一个新 `Glyph` 枚举 + `draw*` 函数） |
| 图片缩略图 / 全屏查看 / 「收完才显示」 | `ui/ImageViews.kt` |
| 视频画面被压扁 / 旋转角不对 / 拟合矩阵 | `ui/ChatScreen.kt` 的 `videoFitMatrix` 与 `applyRemote/LocalTransform`、`adaptiveRemote/LocalRot`；验证脚本 `tools/fit_matrix_check.py` |
| TCP 连接、断线重连、心跳、保活 | `net/LinkConn.kt`（+ `net/Link.kt` 的连接入口 `connectTo`/`selectAndConnect`） |
| 文件/文件夹发送、断点续传、MD5 核对 | `net/LinkSend.kt`（入口 `net/Link.kt` 的 `sendFile`/`sendFolder`） |
| 文件接收、落盘、文件夹完整性、续传 | `net/LinkReceive.kt` |
| 待发队列、历史同步（hsync）、删除同步 | `net/LinkSync.kt` |
| 协议帧格式、二进制/JSON 子类型、数据流量文案 | `net/Protocol.kt` |
| 局域网设备发现（UDP 广播） | `net/Discovery.kt` |
| 设备分组 / 备注 / 组键解析 / 旋转角记忆 | `data/Prefs.kt`（`convKeyOf` 组键解析） |
| 聊天记录落盘 / 待发 / 半成品 | `data/Store.kt` |
| 已知设备名单（后台连接闸门） | `data/TrustedPeers.kt` |
| 消息模型 / 状态枚举 / 文案工具 | `data/MsgItem.kt` |
| 通话状态机 / 来电处理 / 音频路由 | `voice/CallManager.kt`（+ `call/AppCoreCall.kt` 的 `startCall`/`acceptCall`/…） |
| Opus 采集/播放、帧长、抖动缓冲、静音 | `voice/VoiceEngine.kt` |
| 纯语音码率自适应 | `voice/VoiceBitrate.kt` |
| 视频档位 / 编解码器选择 / 升降档 | `video/VideoQuality.kt` |
| 相机打开/切换/方向/预览尺寸吸附 | `video/VideoCapture.kt` |
| 视频编码 / 解码 | `video/VideoEncoder.kt` / `video/VideoDecoder.kt` |
| 视频发送端「转正」（GL） | `video/GlRotatePipe.kt` |
| 视频会话编排 | `video/VideoCall.kt` |
| 锁屏来电通知 / 全屏意图 / 接听挂断按钮 | `CallNotif.kt` |
| 后台保活前台服务 / ColorOS 豁免 / 常驻通知 | `TransferService.kt` |
| 冷启动权限引导 / 分享入口 / 生命周期联动 | `MainActivity.kt`（权限清单 `util/BootPerms.kt` + `ui/BootPermDialog.kt`） |
| 落盘目录 / MIME / 文件名清洗 / SAF 枚举 | `util/FileIO.kt` |
| 网络门禁（私网/蜂窝判断） | `util/LanGate.kt`（本机地址枚举 `util/LocalIps.kt`） |
| 打开收到的文件 / 装 APK | `util/OpenFile.kt` |
| 剪贴板读写 / 去重 | `util/Clip.kt`（保活 `util/ClipCaptureService.kt`） |
| 系统分享解析 | `util/ShareIn.kt` |
| R8 保留规则 / 类名可读 | `app/keepRules/rules.keep`（挂载在 `app/build.gradle.kts` 的 `keepRules.files.add`） |
| 签名 / 版本号 / minSdk / 依赖 | `app/build.gradle.kts`（版本目录 `gradle/libs.versions.toml`） |
| 权限 / 组件声明（Activity/Service/Receiver/Provider） | `app/src/main/AndroidManifest.xml` |
| 文案 / 主题 / 颜色 | `res/values/strings.xml` / `themes.xml` / `colors.xml` |

---

*本索引由真读全部源码生成；行数为 `wc -l` 实测，文件数为 43（任务描述中的「44 个」与实际不符，实际为 43 个 `.kt`，已逐个读取，无跳过）。*

---

## 第二部分　PC 端

## PhonePcTransfer PC 端 —— 源码索引与超大文件拆分方案

> 对象：`<repo>/pc`（Java 21 + Swing 桌面端；手机侧为 Android 应用）
> 编译目标：`--release 17`；`build.bat`（工程根目录）用 Android Studio JBR，`make-msi.sh`（工程根目录）用系统 Adoptium JDK 21（找不到时回退 `pc/tools/jdk21/jdk-21.0.2`）
> 本文只描述**现状**与「**只挪代码、不改逻辑、不改公开签名与协议**」的拆分建议，**未改动项目任何源文件**。
> 包名统一为 `com.phonepctransfer.pc`。

---

### 一、文件作用索引

#### 1.1 源码 `pc/src/com/phonepctransfer/pc/`（36 个 .java，合计约 18046 行）

##### A 组 · 链路核心（TCP 长连接 / 协议 / 收发 / 通话信令）

| 文件 | 行数 | 作用 | 关键类 / 函数 | 备注坑 |
|---|---|---|---|---|
| `Link.java` | 2337 | TCP 链路总控：会话建立与去重、帧分发、连接/保活重连、剪贴板、各类发送公开入口 | `Link`、`State`(enum)、`Listener`(interface)、`PeerCaps`、`Session`(非静态内部类)、`BlockReader`、`DirProgress`；`handleFrame`/`connectTo`/`sessionOf`/`sessionByGroup`/`sendText`/`sendFolder`/`flushPending`/`cancelFile`/`newMid`/`trySend`/`emit` | **全项目最大文件**。已把收发/hsync/通话/同意/文件名拆给 `LinkXxx` 子模块；本文件仍留连接层 + `handleFrame` + 剪贴板 + 公开转发口。`Session` 是**非静态内部类**，外部以 `import com.phonepctransfer.pc.Link.Session` 引用 |
| `LinkReceive.java` | 1232 | 文件/文件夹**接收**管线：双 4MB 缓冲落盘、断点续传、`dir_end` 完整性核对 | `LinkReceive`；静态内部类 `Rx`/`DirRx`/`VerifyState`；`onFileQuery`/`onFileResume`/`onFileFrame`/`onChunk`/`onDirMeta`/`onDirEnd`/`onDirMissing`/`finishDirReceiving`/`pruneDirs`/`abortRxsFor` | 持 `final Link L`。`Rx`/`DirRx`/`VerifyState` 是**它的静态内部类**（不是顶层类），`Link.java` 用 `import ...LinkReceive.Rx` 等引用；注释里"转为顶层包私有类"的说法与实情不符 |
| `LinkSend.java` | 708 | 文件/文件夹**发送**管线：续传协商、分块发送、失败补发、按流量同意拒发 | `LinkSend`、内部类 `SourceException`；`sendFile`/`retryFileSend`/`doSendFolder`/`doSendFolderInner`/`negotiateResume`/`resendPendingFile`/`denyFileByData` | 持 `final Link L`；通过 `L.inFlight`/`L.dirSendState`/`L.dirMissingBox`/`L.rx` 访问共享态（**这些成员必须保持包私有**） |
| `LinkHsync.java` | 384 | 历史同步（hsync）与消息删除（msg_del） | `LinkHsync`；`startHsync`/`onHsyncMids`/`onHsyncPull`/`onHsyncMsg`/`deleteMessage`/`deleteMessages`/`canSendMsgDel`/`onRemoteMsgDel`/`resendHistoryFile` | 持 `final Link L`；依赖 `Store.loadHistory`/`removeFromHistoryEverywhere` |
| `LinkCall.java` | 202 | 语音/视频帧与通话信令发送 | `LinkCall`；`sendAudioTo`/`sendCallInvite`/`sendCallAccept`/`sendVideoTo`/`peerCapsOf` | PC 端**只收不发视频**：`sendVideoTo` 恒拒绝；只按帧粒度抢 socket 写锁，不进文件发送队列 |
| `LinkConsent.java` | 107 | 数据流量确认（`data_ask`/`data_ans`） | `LinkConsent`；`ensureDataConsent(Async)`/`replyDataAsk`/`deliverDataAns`/`newConsentMid`；信箱 `dataAnsBoxes` | `Link` 里保留同名 `public` 转发方法（如 `replyDataAsk`/`newConsentMid`），是"公开签名留壳、实现下移"的现成范例 |
| `LinkNames.java` | 250 | 纯静态文件名净化与落盘去重 | `LinkNames`；`sanitize`/`segSanitize`/`sanitizeRelPath`/`createUniqueFile`/`createUniqueDir`/`listFilesRecursive`/`relPathOf` | 无实例状态、纯函数，最安全的一类拆分产物 |
| `Protocol.java` | 776 | 协议 v2 帧构造与解析 | `Protocol`、嵌套 `Frame`(`JsonF`/`Chunk`/`Audio`/`Video`)；`hello`/`dataAsk`/`dataAns`/`fileMeta`/`fileDone`/`dirMeta`/`dirEnd`/`dirMissing`/`fileVerify`/`hsyncMids`/`msgDel`/`callInvite`/`callAccept`/`writeChunk`/`writeAudio`/`writeVideo` | 帧 = `[4B 大端 uint32 长度][载荷]`；二进制分片最高位 `0x80000000` 当标志位；改动协议字段即破坏兼容 |
| `PcMain.java` | 927 | 程序入口：装配 Store/Link/Clip/Discovery/CallManager、系统托盘、单实例守卫 | `PcMain`、内部类 `Tray`；`main`/`acquireSingleInstance`/`GUARD_PORT=8767`/`callAsync`/`installVideoUi`/`makeIcon` | 单实例靠**守卫端口 127.0.0.1:8767**（与业务 28765 不同）；托盘事件坐标在「系统缩放 ≠ 程序钉定值」时可能是设备像素，`toLogicalScreen` 按钉定值换算（2026-09-29 起钉 1.0） |
| `Log.java` | 74 | 文件日志 | `Log`；`init`/`setConsole`/`i`/`w`/`e(msg[,Throwable])`/`write` | 写 `logs/pc.log`；`Log.w` 在保活重连等热路径被调用 |

##### B 组 · 存储与数据模型

| 文件 | 行数 | 作用 | 关键类 / 函数 | 备注坑 |
|---|---|---|---|---|
| `Store.java` | 1957 | 本地存储：配置、设备列表、设备分组键、聊天历史 jsonl、待发队列、续传半成品、目录迁移 | `Store`、`Group`(静态内部类)；`load`/`save`/`groups`/`groupOfIp`/`convKeyOf`/`bindDevice`/`peers`/`addPeerIp`/`pruneStaleIps`/`appendHistory`/`loadHistory`/`removeFromHistory`/`removeFromHistoryEverywhere`/`addPending`/`loadPending`/`putPartial`/`purgeStalePartials`/`defaultRoot`/`migrateLegacyData` | **全项目第二大文件**。`convKeyOf` 走**发送热路径**（任意线程可调），权威来源是 `ConcurrentHashMap ckeys`，**不能改成"去碰受锁的 peerList"**；历史 IO 用独立 `histLock`，与 `synchronized(this)` 是两把锁 |
| `Msg.java` | 93 | 消息模型 | `Msg`；常量 `DIR_SELF`/`DIR_PEER`、`KIND_TEXT`/`KIND_FILE`/`KIND_DIR`、`ST_*`；`toJson` | 字段名即协议/历史 json 键，改名 = 破坏兼容 |
| `Peer.java` | 57 | 设备条目（备注 + IP + 最近在线） | `Peer`；`displayName`/`toJson`/`fromJson` | `lastId`/`lastSeenAt` 参与分组键解析与老化裁剪 |
| `Json.java` | 347 | 极简 JSON 解析/序列化/取值助手 | `Json`；`str`/`bool`/`num`/`obj`/`arr`/`parse`/`write` | 自研实现，无第三方依赖；数值/转义边界需谨慎 |
| `Texts.java` | 69 | 文案工具 | `Texts`；`headTail`/`humanSize`/`middleEllipsis` | 纯函数 |
| `Speeds.java` | 106 | 传输速率采样与展示 | `Speeds`；`sample`/`bpsOf`/`end`/`text` | 被收发两侧同时调用 |
| `DataConsent.java` | 75 | 流量同意结果码 | `DataConsent`；`OK`/`LOCAL_REFUSED`/`REMOTE_REFUSED`/`NO_SESSION`；`peerRefusedText` | 结果码语义与气泡红色叹号联动 |
| `LocalIps.java` | 306 | 本机地址枚举与 IPv6 压缩 | `LocalIps`；`list`/`compressV6` | 地址枚举顺序影响设备列表展示 |
| `Discovery.java` | 473 | UDP 广播发现手机 | `Discovery`；`scanOnce`/`subnetPrefixOf`/`localIpv4`/`recvLoop` | `subnetPrefixOf` 有独立自检 `SubnetCheck`（见 1.3） |

##### C 组 · 聊天 UI

| 文件 | 行数 | 作用 | 关键类 / 函数 | 备注坑 |
|---|---|---|---|---|
| `ChatWindow.java` | 1399 | 主聊天窗口：左设备列表 + 右会话；设置对话框；5s 状态心跳 | `ChatWindow`、内部类 `SettingsDialog`；`switchConversation`/`buildHeader`/心跳；引用 `ChatBubbles W`、`ChatDevices` | 已拆出 `ChatBubbles`/`ChatDevices`；Swing 全部操作必须在 EDT；`SettingsDialog.buildContent` 供 `tools/ChromeProbe` 离屏单独验证 ⇒ 别在里面引 `W.frame`。设置页四分区：保存位置 / 剪贴板 / 开机自启 / **视频接收画质上限**（10-04 加，四档常驻钮；`Store.setVideoMaxLevel` 的唯一调用方） |
| `ChatBubbles.java` | 998 | 聊天气泡渲染：文本/文件/文件夹进度、缩略图、手势与右键菜单 | `ChatBubbles`、`RoundedPanel`、`DenyRetryIcon`；速率心跳 `LIVE_BARS`；短按打开、删除只走右键菜单（10-15 撤掉左键长按 600ms 直达删除，它和划选文本撞车） | 持 `final ChatWindow W`；缩略图走缓存，注意 EDT 阻塞 |
| `ChatDevices.java` | 567 | 左侧设备面板：自绘行、刷新设备/本机 IP、改备注 | `ChatDevices`、`DeviceRow`；`buildDevicePanel`/`refreshDevices`/`refreshLocalIps`/`editPeer`/`probeThen`（10-30） | 持 `final ChatWindow W`；添加地址前 `addrReachable` 探测 3s（ICMP 或应用端口 TCP 任一通即放行，后台线程跑，不通弹单按钮提示且不入库） |
| `FileDrop.java` | 287 | 拖拽发送（文件/文件夹） | `FileDrop`；`confirmAndSend` | 依赖非管理员运行（管理员下 UIPI 静默拦截拖拽）；设备离线不再阻塞——`sendFile/sendFolder` 自带待发队列，确认后顺手 `connectTo` 一次 |
| `ImageViewer.java` | 222 | 全屏图片查看器 | `ImageViewer`、`ViewerWindow`、`ImagePanel`；`show` | 大图直接解码易 OOM，需缩略优先 |

##### D 组 · 通话与媒体

| 文件 | 行数 | 作用 | 关键类 / 函数 | 备注坑 |
|---|---|---|---|---|
| `CallManager.java` | 764 | 通话状态机（语音/视频），桥接 Link 与窗口 | `CallManager`、`State`(enum)、`VideoUi`(interface)；`onIncoming`/`onAccept`/`onReject`/`hangup`/`onPeerDisconnected`/`startTalking`/`startVideoReceive`/`setMuted` | 持 Link/Store/VoiceEngine；视频基线 `rot` 每次通话重新下发（Store 只存用户叠加角） |
| `VoiceEngine.java` | 523 | Opus 编解码（concentus）与采集/播放 | `VoiceEngine`；`configure`/`startRecord`/`startPlay`/`stopAll`；`BACKLOG_MAX_MS=600` | 依赖 `lib/concentus-1.0.2.jar` 的 `io.*` 类（构建时被抽进 jar） |
| `VoiceCallWindow.java` | 187 | 语音通话窗口 | `VoiceCallWindow` | 与 `VideoWindow` 分工：语音窗口不显示画面 |
| `VoiceBitrateController.java` | 172 | Opus 码率自适应 | `VoiceBitrateController`；`evaluate` | 纯计算，可独立测试 |
| `VideoWindow.java` | 577 | 视频窗口（**只显示不采集**） | `VideoWindow`、`Actions`(interface)；`onFrame`（解码线程调用，最新帧优先） | 旋转角 `(rotation + userRot) % 360`；`onFrame` 在解码线程，切 EDT 由窗口内部处理 |
| `FfmpegDecoder.java` | 675 | 视频解码子进程（ffmpeg），喂流/取帧/回收 | `FfmpegDecoder`、`Sink`(interface)；`probe`/`bundledPath`/`setFormat`/`enqueue`/`start`/`stop`；`MAX_QUEUE=24`；线程 `pp-video-feed/read/kill` | 依赖 `bin/ffmpeg.exe`；`bundledPath()` 从**代码所在位置**向上找 `bin/ffmpeg.exe`（安装版 `<install>/app/bin/`，源码版 `pc/bin/`） |
| `ImageCodec.java` | 332 | 图片解码：ImageIO → WIC 兜底 → ffmpeg 兜底 | `ImageCodec` | 解码链有平台分支，Windows 上易踩 WIC/ffmpeg 缺失 |

##### E 组 · 图标 / 剪贴板 / 系统

| 文件 | 行数 | 作用 | 关键类 / 函数 | 备注坑 |
|---|---|---|---|---|
| `AppIcon.java` | 173 | 应用图标矢量渲染 | `AppIcon`；`render`/`renderSet`/`contactSheet` | 托盘用 `render(32)`；`tools/MakeIcon` 也调它 |
| `CallIcons.java` | 230 | 通话界面矢量图标 | `CallIcons`；`speakerOn`/`hangup`/`fullscreen`/`rotate`/`circleButton` | 纯绘制 |
| `Clip.java` | 194 | 系统剪贴板监听（400ms 轮询）与写入 | `Clip`、`Listener`(interface)；`start`/`stop`/`pollLoop`/`readClipboard`/`setClipboard`/`buffer`/`bufferSha1`/`sha1` | 用 `suppressSha1` 抑制自己写入导致的自环；`sha1` 为公共静态工具 |

##### F 组 · 测试 / 自测（非产品代码，但会被 `dir /s /b` 一起编译进 jar）

| 文件 | 行数 | 作用 | 关键类 / 函数 | 备注坑 |
|---|---|---|---|---|
| `StoreTest.java` | 100 | 保存目录自测 + 重名落盘名断言（`NAME PROBE PASS`） | `StoreTest.main`；直调 `LinkNames.createUniqueFile/createUniqueDir` | 会被编入产物 jar（含测试 main） |
| `TestClient.java` | 192 | 协议测试客户端 | `TestClient.main` | 同上；拆分后若搬动被反射的方法名会静默失效 |

#### 1.2 脚本 / 配置 / 依赖（`pc/` 根目录）

| 文件 | 作用 | 关键点 | 备注坑 |
|---|---|---|---|
| `build.bat`（工程根目录） | 编译 + 打包（javac → jar） | 第 9–10 行 `cd /d "%~dp0"` + `cd /d "%~dp0pc"`；第 29–30 行 `del /q build\sources.txt` + `dir /s /b "src\*.java" > build\sources.txt`；第 35–36 行 `javac -encoding UTF-8 --release 17 -Xlint:none -cp lib\concentus-1.0.2.jar -d build\classes @build\sources.txt`；第 45–57 行抽 concentus 的 `io/` 进 `build\classes`；第 58–60 行 `jar --create --file dist\PhonePcTransfer-PC.jar --main-class com.phonepctransfer.pc.PcMain` | **必须保持 ASCII-only**；`JBR` 变量硬编码到 Android Studio JBR；**源文件清单是动态枚举，不是手工列表**（详见 §2.6/§3.6） |
| `make-msi.sh` | 打 MSI 安装包（jpackage） | 必须用 Git Bash 跑；`APP_VER` 控制对外版本（默认 1.0.1），内部 `ProductVersion` 按 `YYMMDDHHMM` 编码成 `YY.MM.(DD*1440+HH*60+MM)` 并保证严格递增；`jpackage --type msi --name "PhonePcTransfer" --icon tools/app.ico --input installer/input --runtime-image tools/runtime --win-dir-chooser --win-per-user-install --java-options "-Dfile.encoding=UTF-8"`，末尾 `-J-Duser.language=zh -J-Duser.country=CN` 决定安装器语言（zh-CN → `zh_CN.wxl` 的 Codepage=936；改成 `en-US` 会因 `MsiInstallerStrings_en.wxl` 的 1252 覆盖而让中文报 LGHT0311）；产物拷到 `out/手机电脑互传-<APP_VER>.msi`（工程根目录） | **不要用 `sort -n`**（Git Bash 里会被 `C:/Windows/System32/sort.exe` 抢走，管道静默变空）；缺 `bin/ffmpeg.exe` 会直接 exit 1 |
| `run.bat` | **一键启动**（始终非管理员） | `run.bat addrules` 是内部提权分支，仅用 netsh 加 TCP 28765 / UDP 28766 入站规则；已有实例（28765 LISTENING）会拒绝双开；启动后用 `javaw` 起 jar，3s 后 netstat 复查是否真的起来了 | 应用**绝不以管理员启动**（否则 Explorer 拖拽被 UIPI 静默拦截）；日志 `logs\pc.log` |
| `run_admin.bat` | 仅用于**提权加防火墙规则**，再用 explorer（中完整性）转到 `run.bat` 非提权启动 | `net session` 判管理员；`explorer.exe "%~dp0run.bat"` 去提权 | 名字易误解为"以管理员运行应用"，实为"仅加规则" |
| `config.json` | **运行时数据**（本机私有配置，不入库） | 字段示例：`deviceId=pc-xxxxxxxx`、`deviceName=<电脑名>`、`tcpPort=28765`、`udpPort=28766`、`saveDir=<接收目录>`、`lastIp=<对端地址>`、`videoEnabled=true`、`videoMaxLevel=1`、`ffmpegPath=""`、`ffmpegHwaccel=""`，外加 `peers[]`/`peerIps[]`/`bindings{}`/`deviceRemarks{}`/`groupCurIp{}`/`rotRemote{}` | **不要提交/覆盖**；键名与 `Store.save()` 一一对应，改键名 = 老配置失效。真正加载与写回的是 **`%APPDATA%\PhonePcTransfer\config.json`**，见 `Store.defaultRoot()` |
| `partials.json` | **运行时数据**：断点续传半成品表 | 当前内容 `{}`（空） | 由 `Store.putPartial/removePartial/purgeStalePartials` 维护；3 天过期回收 |
| `pending.jsonl` | **运行时数据**：待发（未送达）消息队列 | 当前 **0 字节**（空） | 每行一条 JSON；由 `Store.addPending/loadPending/clearPending` 维护 |
| `history/<组键>.jsonl` | **运行时数据**：按设备分组键分文件的聊天历史（不入库） | 文件名示例：`192.168.1.13.jsonl`、`android-xxxxxxxx.jsonl`、`pc-xxxxxxxx.jsonl` | 文件名 = `Store.safeKey(组键)`（非字母数字下划线换 `_`）；每行一条记录，字段见 §1.2 末尾"历史记录样例" |
| `history.legacy-20260913-163206.jsonl` | **归档数据**：拆分"所有设备混装"的历史前的旧文件，原样留档 | 由 `Store.migrateLegacyHistory()` 改名而来 | 只归档不删除；新体系从空历史开始 |
| `lib/concentus-1.0.2.jar` | 语音编解码依赖（纯 Java Opus） | 421531 字节；构建时只抽 `io/` 包进 classes，再打进 jar 使产物自包含 | 缺它 build.bat 直接 exit 1 |
| `bin/ffmpeg.exe` | 视频解码件（裁剪版静态单文件） | 3030016 字节；`FfmpegDecoder.bundledPath()` 定位；缺失仅"不支持收视频"，不影响音频/文件 | `run.bat` 缺失时只 warn；`make-msi.sh` 缺失则报错 |

**历史记录样例**（`history/192.168.1.13.jsonl`）：
```json
{"mid":"sample-mid","peer":"192.168.1.13","dir":"PEER","kind":"FILE","text":"","fileName":"sample-image.png","fileSize":105784,"filePath":"D://Downloads//sample-image.png","ts":1789562222723,"status":"OK","isDir":false,"dirCount":0}
```

#### 1.3 `pc/tools/` 工具与构建产物（**注意：不是产品源码，不参与 build.bat**）

Java 工具（`pc/tools/*.java`）：

| 文件 | 作用 | 备注 |
|---|---|---|
| `UiWinProbe.java` | 抓取/分析 Swing 窗口（`SunAwtFrame`）外观 | `package com.phonepctransfer.pc;` |
| `UiProbe.java` | UI 探针：取窗口图像快照 | 同上 |
| `UiTree.java` | 打印 Swing 组件树（定位布局问题） | 同上 |
| `PcShot.java` | `java.awt.Robot` 整屏/区域截图 | 无 package |
| `BubbleProbe.java` | 单独渲染气泡/面板用于比对 | 同上 |
| `ClearProbe.java` | 验证"清空会话"后的残留 | 同上 |
| `ChatSepProbe.java` | 验证聊天分隔/布局细节 | 同上 |
| `DataRootProbe.java` | 校验数据根目录（`%APPDATA%\PhonePcTransfer`）内容 | 同上 |
| `InkProbe.java` | 量"文字实际墨迹宽度 vs `FontMetrics` vs 标签宽" | **`public class E`**（文件名与类名不一致，靠单文件源码启动跑） |
| `SpeedProbe.java` | 速率展示探针 | `package com.phonepctransfer.pc;` |
| `FolderPushTest.java` | 文件夹推送联调测试 | 同上 |
| `ReceiveDaemon.java` | 接收端守护/联调进程 | 同上 |
| `MakeIcon.java` | 用 `AppIcon` 生成 `app.ico`/PNG | 调 `AppIcon` |
| `MakePng.java` | 由 `MakeIcon` 导出 PNG | 反射 `Class.forName("MakeIcon")` |

**`ClassCheck.java` / `SubnetCheck.java` 的真实位置（重要纠偏）**：**不在 `pc/tools/` 下，而在 `pc/build/probe/`**（构建临时目录）：
- `pc/build/probe/ClassCheck.java`（对应已编译的 `ClassCheck.class`）
- `pc/build/probe/SubnetCheck.java`

Python 工具（`pc/tools/*.py`，均需在 Git Bash 下用系统 python 跑）：

| 文件 | 作用 |
|---|---|
| `verify_tree.py` | 逐文件比对两棵目录树（名字 + 字节数，可选 `--md5`），独立验证"传文件夹有没有漏/坏" |
| `find_win.py` | 找 PC 端主窗口（`SunAwtFrame`）并打印屏幕矩形 |
| `raise_win.py` | 把 PC 端主窗口抬到最前（供抓屏） |
| `win_dump.py` | 诊断：列出所有可见顶层窗口的类名/标题/矩形/置顶状态 |
| `ui_auto.py` | Android UI 自动化小助手（`adb` + `uiautomator dump`），触发 App 内部操作 |
| `png_ink_scan.py` | 扫 PNG 里某种颜色文字的实际墨迹范围，验证文字是否被容器裁掉 |

**构建产物 / 临时数据（可安全清理，勿当源码）**：
- `pc/build/`：`sources.txt`（动态生成）、`classes/`、`classes-old-*`、`classes-polluted-*`、`classes_check/`、`concentus_tmp/`、`dist/`、`jarstage/`、`stage/`、`tmp/`、`x*`、`vtest*`、`hssync_*`、`probe/`、`shot/`、`logs/`、`javac.log`/`jar.log`/`test.log`/`smoke.log`、`TestTicker.java`/`.class`、`pcmain_dump.txt`/`pcmain_err.txt`、`jarlist.txt`、`*.exit`
- `pc/dist/`：`PhonePcTransfer-PC.jar`（正式产物，735691 字节）+ `PhonePcTransfer-PC.new.jar`
- `pc/installer/`：`input/`（jpackage 输入）、`output/`（生成的 MSI）；`pc/logs/pc.log`、`pc/shots/`（`pc_ui.png`/`pc_hdr5.png`）、`pc/received/`（`pp_file_test_2026.bin`/`测试文件.bin`）、`pc/thumb_cache/`（缩略图缓存）
- `pc/tools/jdk21/`（含 `jdk21.zip`）、`pc/tools/runtime/`（jlink 运行时镜像）、`pc/tools/wix311/`（含 zip）、`pc/tools/app.ico`、`pc/tools/app_2.png`、`pc/tools/preview.png`
- 上级目录的交付副本（`packages/`）、截图归档与任务清单**不入库**

#### 1.4 项目文档（各一行说明）

| 文档 | 用途 |
|---|---|
| `docs/PROTOCOL.md` | 协议唯一权威说明（当前 `v=2`，新特性走**能力位协商**而非版本号闸门）：帧格式、握手、文件分片、断点续传、文件夹完整性校验、hsync 历史同步、msg_del、数据流量确认、语音/视频通话 —— 改动协议必须先改这里 |
| `README.md` | 项目总览：手机 ⇄ 电脑局域网互传，Android 端 + 常驻托盘 PC 端，裸 TCP 长连接 |
| `PLAN.md` / `VIDEO_CALL_PLAN.md` | 本地规划与任务清单（未入库） |
| `docs/FOLDER-TRANSFER-VERIFY-20260917.md` | 文件夹传输双向回归验证记录（额外发现，非你列出的四份，但同属 docs） |

---

### 二、`Link.java` 拆分方案（2337 行 → 目标 ≤ ~1300 行）

#### 2.1 现状测绘（按行号分段）

| 行号区间 | 内容 | 现状 |
|---|---|---|
| 1–36 | import（含 3 条 `import com.phonepctransfer.pc.LinkReceive.{DirRx,Rx,VerifyState};`） | — |
| 38–55 | 类 Javadoc（端口、去重规则、性能特性） | — |
| 57 | `public enum State { STOPPED, IDLE, CONNECTING, CONNECTED }` | **公开** |
| 59–157 | `public interface Listener`（近 20 个回调，含 call/video/clip 的 default 方法） | **公开** |
| 159–201 | `public static final class PeerCaps`（vid/cam/hs/hd/dl/dm + vcodec()） | **公开** |
| 207–225 | `localVidCap`/`localVCodecs` + `setLocalVideoCaps`/`setLocalVidCap` | `setLocalVidCap` **公开** |
| 227–239 | 核心字段：`listener`/`clip`/`store`/`state`/`peerLabel`/`running`/`serverSocket`/`sessions`(COWList)/`byPeer`(CHM) | 跨簇共享 |
| 241–368 | **保活重连簇**：`keepAliveExec`/`keepAliveJobs`/`keepAliveFastFails` + `FAST_FAIL_MS`/`FAST_FAIL_LIMIT` + `scheduleKeepAlive`/`noteSessionDrop`/`cancelKeepAlive` | 可整簇搬走 |
| 369–434 | 选中目标与候选地址簇：`selectedIp`/`selectedLabel`/`pendingConnectIps`/`onGroupFallback` + `groupCandidates` | `groupCandidates` 包私有 |
| 437–457 | `clearPendingFor` | 连接簇 |
| 459–468 | `connectingIp`/`stateNote`/`reconnectLock`/`acceptThread`/`connectThread`/`heartbeatThread` | 连接簇 |
| 470–505 | `setListener`/`setClip`/`setStore` + `state()`/`peerLabel()`/`selectedIp()`/`selectedLabel()`/`isConnectedIp` | 全 **public** |
| 506–672 | 会话查询与连接入口：`connectedIps`/`sessionOf`/`sessionByGroup`/`isDeviceConnected`/`target`/`currentPeerIp`/`selectTarget`/`selectAndConnect`/`connectTo`×3 | 大量 **public** + 包私有 |
| 677–696 | `myId`/`myName`/`port` + **子模块字段** `hsync`/`rx`/`send`/`call`/`consent`（第 691–695 行 `new LinkXxx(this)`） | 装配点 |
| 697–846 | **`final class Session`（非静态内部类，~150 行）**：`send`/`sendChunk`/`sendAudio`/`sendVideo`/`tryPing`/`close` + `writeLock` | 被 4 个兄弟文件 `import ...Link.Session` |
| 848–930 | `start`/`stop`/`isConnected`/`refreshState`/`notifyPeers`/`daemon` | public + 包私有 |
| 931–1093 | `acceptLoop`/`connectLoop`/`waitHandshake` | 连接簇 |
| 1094–1228 | `adopt`/`readLoop`/`heartbeatLoop` | 连接簇 |
| 1229–1265 | `deleteMessage`/`deleteMessages`/`canSendMsgDel` | **public 壳 + 委托 `hsync`**（现成范例） |
| 1266–1512 | **`handleFrame`（~247 行）**：帧分发大 switch，含各帧内联处理 | 最大单块 |
| 1513–1622 | `onHello`/`isSelected`/`setState` | 握手/状态 |
| 1624–1690 | 接收态字段：`rxMap`/`dirMap`/`MAX_KEEP_DIRS`/`dirMissingBox`/`resumeWaiters`/`inFlight`/`inFlightPeer`/`hasActiveTransfer`/`verifyMap`/`VERIFY_TIMEOUT_MS`/`PARTIAL_MAX_AGE_MS`/`STALE_RX_MS` | 跨簇共享（收发都读） |
| 1692–1847 | `uiSendExec` + `sendText`/`sendClip` + 文件发送公开入口（`sendFile`×2/`retryFileSend` 委托 `send`）+ 流量同意委托（→ `consent`） | 含多个 **public 壳** |
| 1848–1936 | `sendFolder`（入口：校验 + 入队 + 委托 `send.doSendFolder*`）+ `DIR_*` 常量 | public |
| 1939–1985 | `final class DirProgress`（进度聚合，`add(long)`） | 内部类 |
| 1986–2049 | `sendOrDrop`/`cancelFile` | 包私有 + public |
| 2050–2206 | **剪贴板簇**：`sendClipNotify`/`pullPeerClip`/`pushLocalClipIfAny`/`syncNow`/`clipBuffer`/`flushPending`×2 | 可整簇搬走 |
| 2207–2277 | `static final class BlockReader` + `Next`（双缓冲读源，`recycle`/`cancel`） | 静态内部类 |
| 2285–2337 | `emit`/`trySend`/`newMid`/`hex`/`waitFor`/`wakeUp`/`log` | 被广泛调用的公共工具 |

**已经拆出去的（不要再动）**：`LinkReceive`/`LinkSend`/`LinkHsync`/`LinkCall`/`LinkConsent`/`LinkNames` —— 全部是 `final class Xxx { final Link L; Xxx(Link L){...} }` 形态，由 `Link` 第 691–695 行持有实例，`Link` 内保留 `public` 一行转发方法。

#### 2.2 建议拆成哪些新文件

> 全部放在 `pc/src/com/phonepctransfer/pc/`（包 `com.phonepctransfer.pc`）；命名与既有 `LinkXxx` 一致；**只挪代码**。

**Tier-1（低风险，建议本轮就做，共 3 个新文件，约移出 910 行）**

| 新文件（完整路径） | 装什么（行来源） | 形态 | 约行数 |
|---|---|---|---|
| `pc/src/com/phonepctransfer/pc/LinkConn.java` | 连接与会话生命周期：`acceptLoop`/`connectLoop`/`waitHandshake`/`adopt`/`readLoop`/`heartbeatLoop`（931–1228）+ 保活重连簇（241–368）+ `clearPendingFor`（437–457）+ `myId`/`myName`/`port`（677–690）+ `daemon`（931） | `final class LinkConn { final Link L; LinkConn(Link L){this.L=L;} }` | ~430 |
| `pc/src/com/phonepctransfer/pc/LinkFrame.java` | 帧分发：`handleFrame`（1266–1512）整块搬走；`readLoop` 改为 `L.conn.frame.handleFrame(s, j)` | 同上 | ~250 |
| `pc/src/com/phonepctransfer/pc/LinkClip.java` | 剪贴板与待发补发：`sendClipNotify`/`pullPeerClip`/`pushLocalClipIfAny`/`syncNow`/`clipBuffer`/`flushPending`×2（2050–2206）；原 public 方法在 `Link` 留一行转发 | 同上 | ~230 |

**Tier-2（中风险，可选，另 2 个新文件）**

| 新文件（完整路径） | 装什么 | 主要阻力 |
|---|---|---|
| `pc/src/com/phonepctransfer/pc/LinkSession.java` | 把 `final class Session`（697–846）由**非静态内部类**提为同包顶层类 | 4 个兄弟文件的 `import com.phonepctransfer.pc.Link.Session;` 必须改为 `import com.phonepctransfer.pc.LinkSession;`；原非静态内部类隐含 outer `Link` 引用，改顶层后**构造时必须显式传 `Link`**（现构造是 `Session(Socket s, boolean out)`，内部访问 `Link.this` 的端口/名字/监听器等）→ 需给 `LinkSession` 加 `final Link L` 字段并加形参，风险较高，故放 Tier-2 |
| `pc/src/com/phonepctransfer/pc/LinkMsg.java` | `sendText`/`sendClip`/`sendOrDrop`/`trySend`/`emit`（1706–1749、1750–1796、1986–2023、2285–2302） | `emit`/`trySend` 被 `LinkReceive`/`LinkHsync`/`LinkSend` 调用（`L.emit(...)`），搬走要先确认全部调用点 |

**不建议动**：`State`/`Listener`/`PeerCaps`（公开 API，留在 `Link`）、`sessions`/`byPeer`/`rxMap`/`dirMap`/`inFlight`/`verifyMap` 等跨簇共享状态（见 2.3）、`Session` 若不想改兄弟文件就原地不动、`BlockReader`/`DirProgress`（各自闭环，搬走收益低）。

#### 2.3 共享可变状态归属唯一（拆分后谁"拥有"）

> 原则：**每个可变状态只许有一个属主类**；跨类访问一律走 `L.xxx`（即从属主读/写），绝不复制第二份。

| 状态 | 唯一属主 | 谁读它 |
|---|---|---|
| `running` / `state` / `peerLabel` / `serverSocket` | `Link` | `LinkConn`（循环与状态机）、`PcMain` |
| `sessions`(CopyOnWriteArrayList) / `byPeer`(CHM) | `Link` | `LinkConn`、`LinkFrame`、`LinkReceive`、`LinkSend`、`LinkHsync` |
| `keepAliveExec` / `keepAliveJobs` / `keepAliveFastFails` | **`LinkConn`** | 仅 `LinkConn` 内部（`Link.stop()` 目前会遍历 `keepAliveJobs` 取消 → 改成调 `conn.shutdownKeepAlive()`） |
| `acceptThread` / `connectThread` / `heartbeatThread` / `reconnectLock` | **`LinkConn`** | 仅 `LinkConn`（`start`/`stop` 通过 `conn.start()/conn.stop()` 转发） |
| `selectedIp` / `selectedLabel` / `pendingConnectIps` / `onGroupFallback` / `connectingIp` / `stateNote` | `Link`（**热路径，且 `PcMain`/UI 直接读 `selectedIp()`**）→ 只有 `groupCandidates`/`clearPendingFor` 可搬 `LinkConn`，用 `L.pendingConnectIps` 访问 | `LinkConn`、`LinkSend`、`ChatWindow` |
| `rxMap` / `dirMap` / `dirMissingBox` / `resumeWaiters` / `inFlight` / `inFlightPeer` / `verifyMap` / `cancelled` / `dirSendState` | `Link` | `LinkReceive`（主）、`LinkSend`（主）、`LinkFrame`、`LinkHsync` |
| `uiSendExec` / `fileSendExec` | `Link` | `LinkMsg`(Tier-2)、`LinkSend`、`LinkClip` |
| `clip`（注入的 `Clip` 实例） | `Link` | `LinkClip`、`PcMain` |
| `listener` | `Link` | 全部（回调） |
| `store` | `Link` | 全部（`L.store`） |
| `dataAnsBoxes`（`LinkConsent` 持有） | `LinkConsent` | 仅 `LinkConsent` |
| `Session.writeLock`（`ReentrantLock`） | `Session` 自身 | 每条会话独占，**绝不可提升为全局锁** |
| `BlockReader` 的 `ready`/`free` 队列 | `BlockReader` | 每实例独占 |

**三条硬约束**：
1. `rxMap`/`dirMap` 的 `synchronized (rxMap)` 监视器必须仍是**同一个对象**（属主 `Link`）；若谁在 `LinkReceive` 里再 `new` 一份 map，等于换了锁。
2. `histLock`（`Store`）与 `reconnectLock`（`Link`）是两把互不相关的锁，**不能合并、也不能因为搬家换 new 出来的新对象**。
3. `Session.writeLock` 是**按会话**的写串行锁；搬 `Session` 时若把 `writeLock` 改成 `static`，会退化成全局串行、带宽骤降 —— 这是最容易犯且最难发现的错误。

#### 2.4 必须留在 `Link.java` 的公开签名 / 被引用项（一个都不能少、不能改）

- **公开类型**：`Link.State`、`Link.Listener`（含所有回调名与 default 方法）、`Link.PeerCaps`（含 `vid()/cam()/hs()/hd()/dl()/dm()/vcodec()`）—— 被 `PcMain`/`CallManager`/`ChatWindow` 实现/调用。
- **公开方法（留壳或原样）**：`start`/`stop`/`isConnected`/`isConnectedIp`/`isDeviceConnected`/`connectedIps`/`currentPeerIp`/`state`/`peerLabel`/`selectedIp`/`selectedLabel`/`setListener`/`setClip`/`setStore`/`setLocalVideoCaps`/`setLocalVidCap`/`selectTarget`/`selectAndConnect`/`connectTo`×3/`sendText`/`sendClip`/`sendFile`×2/`retryFileSend`/`sendFolder`/`hasActiveTransfer`/`cancelFile`/`deleteMessage`/`deleteMessages`/`canSendMsgDel`/`ensureDataConsent`/`ensureDataConsentAsync`/`replyDataAsk`/`newConsentMid`/`sendClipNotify`/`pullPeerClip`/`syncNow`/`clipBuffer`/`flushPending`×2。
- **包私有但被兄弟文件/测试引用（**签名与类名不可变**）**：`sessionOf`/`sessionByGroup`/`target`/`refreshState`/`notifyPeers`/`daemon`/`newMid`/`hex`/`emit`/`trySend`/`log`/`sendOrDrop`/`setState`/`groupCandidates`/`fileSendQueueDepth`/`enqueueFileSend`；以及**所有被 `LinkReceive`/`LinkSend`/`LinkHsync`/`LinkCall`/`LinkConsent` 以 `L.xxx` 触达的字段**（`rxMap`/`dirMap`/`dirMissingBox`/`resumeWaiters`/`inFlight`/`inFlightPeer`/`verifyMap`/`cancelled`/`dirSendState`/`uiSendExec`/`sessions`/`byPeer`/`store`/`clip`/`listener`/`selectedIp`/`localVidCap`/`localVCodecs`/`onGroupFallback` 等）。搬哪块代码，就要保证它读到的字段**仍是 `Link` 上的同一个字段**（`L.` 前缀），不要新建同名字段。
- **`Link.Session`**：只要不动 Tier-2，就保持"非静态内部类、类名 `Session`、构造签名 `Session(Socket, boolean)`"不变。
- **`LinkNames.createUniqueFile(File,String)`（static）**：见 2.6 的"测试反射"陷阱，它已不在 `Link` 上。

#### 2.5 构建影响 —— `sources.txt` 与 `ClassCheck` 的确切结论

**（1）`build/sources.txt`：动态枚举，无需任何改动。**
`build.bat` 第 27–28 行是
```
del /q "build\sources.txt" 2>nul
dir /s /b "src\*.java" > "build\sources.txt"
```
第 32 行把 `@"build\sources.txt"` 喂给 `javac`。这是**每次构建重新扫描 `src\` 生成**，**不是手工维护的显式清单**。
→ 结论：拆分后新增的 `LinkConn.java`/`LinkFrame.java`/`LinkClip.java`（以及 Tier-2 两个）**只要落在 `pc/src/` 下就会被自动编译**，`build.bat`、`sources.txt` 都**不需要改**。唯一要求：文件必须放在 `pc/src/` 树内（第 8 行 `cd /d "%~dp0"` 决定相对基目录是 `pc/`）。

**（2）`ClassCheck`：存在，但**不在 `tools/`**；它是**动态**自检，无需同步改类清单。**
- 实际路径：`pc/build/probe/ClassCheck.java`（旁边还有 `pc/build/probe/SubnetCheck.java`）。你列的"`pc/tools/` 下……以及若存在的 `ClassCheck.java`、`probe/SubnetCheck.java`"里，`probe/SubnetCheck.java` 实际落在 `pc/build/probe/`。
- 机制（`ClassCheck.java` 全文 32 行）：打开传入的 jar（`new ZipFile(a[0])`），遍历 zip 条目，收集所有 `com/phonepctransfer/**/*.class`，对每个类执行 `Class.forName(name, false, loader)`（`initialize=false`，**不跑静态块**），统计 `own classes/ok/bad`，全通过输出 `SMOKE PASS`、否则 `SMOKE FAIL` 并列 `BAD xxx`。
- → 结论：**它不硬编码类清单**，拆分后新增的类会被自动扫到，**`ClassCheck.java` 不用改**；它只做"链接期"校验（抓缺类/坏常量池），**抓不到逻辑/线程/并发错误**。运行时机：**必须对着构建产物 jar**跑（即 `build.bat` 成功之后的 `dist/PhonePcTransfer-PC.jar`），且 classpath 需要 concentus（jar 已自带 `io/`，故只需 `-cp` 指向该 jar 即可）。
- `SubnetCheck.java`（21 组用例测 `Discovery.subnetPrefixOf`）与本次拆分**无关**，除非同时改 `Discovery`。

**（3）逐条：拆分后必须同步改的文件**
| 改动 | 必须同步修改的文件 | 说明 |
|---|---|---|
| 新增 `LinkConn`/`LinkFrame`/`LinkClip` | 只改 `Link.java`（加 `final LinkXxx xxx = new LinkXxx(this);` 字段 + 若干 `public` 转发方法 + 把 `private` 成员降为包私有） | `build.bat`/`sources.txt`/`ClassCheck` 均**不用改** |
| 把 `Link` 成员由 `private` 降为包私有 | 只改 `Link.java` | 只降可见性，**不得升为 public** |
| Tier-2 提出 `Link.Session` → `LinkSession` | `LinkReceive.java`、`LinkSend.java`、`LinkCall.java`、`LinkHsync.java` 的 `import com.phonepctransfer.pc.Link.Session;` → `import com.phonepctransfer.pc.LinkSession;`；以及所有 `new Session(...)` 调用点（若 `Session` 在别处被 new） | 若想零风险就**别做 Tier-2** |
| Tier-2 新增 `LinkMsg` | `Link.ts`…（不存在）；只改 `Link.java` | 同 Tier-1 |
| **测试反射**（既有隐患，见 2.6） | `StoreTest.java` 或 `LinkNames.java` 二选一 | **与拆分无关，但必须先决定口径** |

#### 2.6 风险与回退

**风险（按危险度排序）**

1. ~~**`StoreTest` 靠反射点名 `Link.createUniqueFile`，而该方法已搬到 `LinkNames` —— 这个测试现在就是坏的（真实隐患，已核）**。~~
   **已修（2026-09-22，1.0.6）**：`StoreTest` 改为**同包直调** `LinkNames.createUniqueFile(...)`（`LinkNames` 是包私有类，`StoreTest` 与它同包，反射本来就不必要），
   顺带补了 9 条断言把「序号必须插在扩展名前」钉死（`a.txt` → `a (1).txt`、`备份.tar.gz` → `备份.tar (1).gz`、`文件夹` → `文件夹 (1)`），跑完打印 `NAME PROBE PASS`。
   保留这段记录是为了提醒：**凡是"把带反射契约的方法搬走"的动作，都要回头核 `StoreTest`/`TestClient` 里的 `Class.forName` + `getDeclaredMethod` 是否已失效**。

2. **可见性翻转的外溢**：搬 `handleFrame`/连接循环要求把一批 `private` 成员降为包私有。若图省事升成 `public`，会扩大 API、违反"不改公开签名"；若漏降，编译报 `has private access in Link`（这一步至少**编译期可发现**，属可接受风险）。
3. **锁对象被"复制"**：`rxMap` 的监视器、`histLock`、`reconnectLock`、`Session.writeLock` 一旦在搬家过程中变成新 new 出来的对象或 `static`，会静默改变并发语义（竞态/死锁/带宽骤降）。**编译期完全发现不了，只在真机高并发下暴露。**
4. **`Session` 是非静态内部类**：它被 4 个兄弟文件按 `Link.Session` 引用，且隐含 `Link.this`。Tier-2 提顶层极易引入编译错或 NPE 语义变化。
5. **`emit`/`trySend`/`newMid`/`hex` 被多文件调用**：搬走前必须全量 grep 调用点，否则编译期报错（可发现，但会反复返工）。
6. **无效验证**：`ClassCheck` 只做链接期校验（`initialize=false`），**通过 ≠ 拆分正确**；不能拿它当回归证明。

**回退方案**：拆分前先 `git`-无关地整目录备份（本仓库**不是 git 仓库**，见环境说明）——
```bash
cp -r "<repo>/pc/src/com/phonepctransfer/pc" \
      "<workspace>/.workbuddy/tmp/pc-src-backup-$(date +%Y%m%d-%H%M%S)"
```
按 **Tier-1 三个文件分三次提交式推进**，每完成一个就 `javac` 全量编译 + `build.bat` 出 jar + `ClassCheck` 冒烟；任何一步失败，直接用备份回滚该文件的改动。**不要一次拆三个。**

#### 2.7 验证步骤（沙箱内可行）

JDK（`build.bat` 用的是 Android Studio JBR，但沙箱里可用项目自带的 21）：
`<repo>/pc/tools/jdk21/jdk-21.0.2/bin/`

```bash
cd "<repo>/pc"
JC="tools/jdk21/jdk-21.0.2/bin/javac.exe"
JR="tools/jdk21/jdk-21.0.2/bin/java.exe"
CP="lib/concentus-1.0.2.jar"

## 1) 全量编译（等价 build.bat 的编译步，输出到独立目录，不污染 build/classes）
find src -name '*.java' > /tmp/pp_sources_split.txt
"$JC" -encoding UTF-8 --release 17 -Xlint:none -cp "$CP" -d build/classes_split_check @/tmp/pp_sources_split.txt

## 2) 存量自测（注意 StoreTest 目前会因反射失效而抛异常，见 2.6 风险 1 → 属预期）
"$JR" -cp "build/classes_split_check;$CP" com.phonepctransfer.pc.StoreTest

## 3) 子网口径自检（与本次拆分无关，作对照；源码在 pc/build/probe/ 而非 pc/tools/）
"$JC" -d build/classes_split_check build/probe/SubnetCheck.java
"$JR" -cp "build/classes_split_check;$CP" SubnetCheck   # 期望结尾：SUBNET CHECK PASS
```

出 jar + 冒烟（Windows 侧，需 JBR 在 `C:/Program Files\Android\Android Studio\jbr`）：
```bash
cmd //c build.bat
"$JR" -cp "build/probe" ClassCheck "dist/PhonePcTransfer-PC.jar"   # 期望结尾：SMOKE PASS
```
> 若 `ClassCheck` 的 classpath 需要同时看到 `io.*`，直接用 jar 自身即可（`io/` 已打进 `dist/PhonePcTransfer-PC.jar`）。

**真机/端到端回归（沙箱内做不了，交用户）**：至少覆盖
① 双端互发文本/剪贴板；② 单文件续传（中途断网重连）；③ 文件夹含空目录 + 完整性核对（配 `tools/verify_tree.py` 逐文件校验）；④ 语音通话建立/挂断；⑤ 视频方向；⑥ 历史同步 hsync 与 msg_del 删除同步；⑦ 数据流量确认拒绝路径（气泡红色叹号 → 重试）；⑧ 托盘退出后端口 28765/8767 释放。

---

### 三、`Store.java` 拆分方案（1957 行 → 目标 ≤ ~900 行）

#### 3.1 现状测绘（按行号分段）

| 行号区间 | 内容 | 现状 |
|---|---|---|
| 32–40 | 类声明 + 路径字段：`public final Path root`/`defaultReceivedDir`/`logDir`；`private final Path configFile`/`historyDir` | `root`/`defaultReceivedDir`/`logDir` **public final**（`ChatBubbles` 直接读 `store.root`） |
| 42–68 | 配置字段：`deviceId`/`deviceName`/`tcpPort`/`udpPort`/`autoWriteClipboard`/`allowConnectOut`/`saveDir`/`lastIp`/`videoEnabled`/`videoMaxLevel`/`ffmpegPath`/`ffmpegHwaccel` + `peerList` | 全部 `private`（拆分需降为包私有或提供包私有访问器） |
| 70–99 | 分组状态字段：`bindings`/`deviceRemarks`/`groupCurIp`/`rotRemote`（`LinkedHashMap`）+ `ckeys`(CHM，**热路径**) + `mergeTried` + `histLock` | `histLock` 是历史 IO 专用锁 |
| 101–111 | 构造：建 `received`/`logs`/`history` 目录，调 `migrateLegacyHistory()` | — |
| 119–146 | `migrateLegacyHistory()`（把旧的混装 `history.jsonl` 改名归档） | 磁盘副作用 |
| 147–161 | **`convKeyOf(ip)`（发送热路径，走 `ckeys` 缓存）** | **public，绝不能挪到需要持锁的地方** |
| 162–207 | `bindDevice(ip, deviceId)`（更新绑定 + `ckeys`） | public synchronized |
| 208–240 | `migrateGroupState(oldKey,newKey)` | private |
| 241–364 | 分组读写：`remarkOf`/`setGroupRemark`/`curIpOf`/`setGroupCurIp`/`rotRemoteOf`/`setRotRemoteOf` + `Group`(静态内部类，含 `displayName`/`multi`) | public synchronized（`Group` 被 `ChatDevices` 用） |
| 365–423 | `groups()`（按设备聚合出 `List<Group>`）/`groupOfIp(ip)` | public synchronized |
| 424–448 | `safeKey(ip)`（**static**，历史文件名净化）/`historyFile(ip)` | private |
| 449–506 | `mergeLegacyHistory(ip, dest)`（首次归并老历史） | private |
| 507–596 | **`load()`**（读 config.json，含 `seedGroupKeys` 播种、`mergeConfigFrom` 兜底） | public synchronized |
| 597–627 | `seedGroupKeys()`/`readStringMap(...)` | private |
| 628–659 | **`save()`**（写 config.json） | public synchronized |
| 660–664 | `defaultName()`（static：`DESKTOP-xxx` 兜底名） | private static |
| 665–696 | 配置 getter/setter：`deviceId`/`deviceName`/`tcpPort`/`udpPort`/`autoWriteClipboard`(±set)/`allowConnectOut` | 全 public synchronized |
| 697–722 | `saveDir`/`setSaveDir`/`lastIp`/`setLastIp` | 全 public synchronized |
| 723–782 | 视频与 ffmpeg：`videoEnabled`(±set)/`videoMaxLevel`(±set)/`ffmpegPath`(±set)/`ffmpegHwaccel`(±set) | 全 public synchronized |
| 783–811 | `receivedDir()`/`receivedDirText()`（带非法目录回落 `defaultReceivedDir`） | public |
| 812–897 | 设备列表：`peers()`/`findPeer(ip)` | public synchronized / private |
| 898–957 | `updatePeer`/`removePeer` | public synchronized |
| 958–1043 | `peerIps`/`addPeerIp`/`pruneStaleIps(maxAgeDays,keepMin)`/`removePeerIp` | public synchronized |
| 1044–1329 | **历史簇**：`appendHistory`/`hasMid`/`removeFromHistory(mid)`/`removeFromHistory(Collection)`/`removeFromHistoryEverywhere(mid)`/`loadHistory(ip,max)`/`mergeRow(old,neu)` —— 全部 `synchronized (histLock)` | public（部分） |
| 1330–1525 | **待发簇**：`pendingFile`/`addPending`/`removePending`×2/`clearPending`/`clearConversation`/`loadPending`/`pendingPeerIp`/`loadPendingRaw`/`writePendingRaw`/`toMsg` | 多数 `synchronized(this)` |
| 1526–1647 | **半成品簇**：`putPartial`/`getPartial`/`removePartial`/`purgeStalePartials`/`partialsFile`/`loadPartials`/`writePartials` | public synchronized |
| 1648–1955 | **根目录与老数据迁移（多为 static）**：`defaultRoot`(**public static**)/`explicitRoot`/`migrateLegacyData`/`legacyRoots`/`configLastSeen`/`mergeConfigFrom`(实例)/`mergeStringMap`/`copyHistoryDir`/`mergeJsonl`/`midsIn`/`midOf` + `MIGRATED_FLAG` | 被 `PcMain:264` 调用 `Store.defaultRoot()` |

**跨文件已确认调用面（拆分后必须原样可用）**：`store.root`（`ChatBubbles`）、`Store.Group`（`ChatDevices`）、`Store.defaultRoot()`（`PcMain`）、`store.convKeyOf`/`peers`/`upsertPeer`/`removePeer`/`bindDevice`/`setGroupRemark`/`groups`/`groupOfIp`/`remarkOf`/`deviceId`/`deviceName`/`tcpPort`/`allowConnectOut`/`autoWriteClipboard`/`saveDir`/`setSaveDir`/`lastIp`/`setLastIp`/`receivedDir`/`receivedDirText`/`videoEnabled`/`videoMaxLevel`/`ffmpegPath`/`ffmpegHwaccel`/`load`/`save`/`appendHistory`/`loadHistory`/`removeFromHistory`/`removeFromHistoryEverywhere`/`hasMid`/`addPending`/`loadPending`/`clearPending`/`putPartial`/`getPartial`/`removePartial`/`purgeStalePartials`/`addPeerIp`/`peerIps`/`pruneStaleIps`。

#### 3.2 建议拆成哪些新文件

> `Store` 是**数据持有类**（字段多、方法薄），因此采用与 `Link` 相同的既有范式：**新类持 `final Store S`，`Store` 保留 public 方法一行转发**；被搬走的**文件路径类**方法改由新类持有。所有新文件放 `pc/src/com/phonepctransfer/pc/`。

**推荐（4 个新文件，约移出 940 行；`Store.java` → 约 1000 行）**

| 新文件（完整路径） | 装什么（行来源） | 形态要点 | 约行数 |
|---|---|---|---|
| `pc/src/com/phonepctransfer/pc/StoreMigration.java` | 根目录发现 + 老数据/老配置迁移：`defaultRoot`/`explicitRoot`/`legacyRoots`/`configLastSeen`/`migrateLegacyData`/`mergeConfigFrom`/`mergeStringMap`/`copyHistoryDir`/`mergeJsonl`/`midsIn`/`midOf` + `MIGRATED_FLAG`（1648–1955） | 绝大多数是 `static`，**几乎零实例依赖**；只有 `mergeConfigFrom` 需传入 `Store`（`static void mergeConfigFrom(Store S, Path cfg)`），故把被它写到的配置字段降为包私有。`defaultRoot()` 保持 `public static`（`PcMain` 在调） | ~310 |
| `pc/src/com/phonepctransfer/pc/StoreHistory.java` | 历史 jsonl 簇：`historyFile`/`migrateLegacyHistory`/`mergeLegacyHistory`/`appendHistory`/`hasMid`/`removeFromHistory`×2/`removeFromHistoryEverywhere`/`loadHistory`/`mergeRow`/`clearConversation` 中的历史部分（119–146、432–506、1044–1329、1412–1438 的历史半） | `final class StoreHistory { final Store S; StoreHistory(Store S){this.S=S;} }`；**锁 `histLock` 随簇一起搬入本类**（它只被历史 IO 使用，搬走=锁对象不变） | ~330 |
| `pc/src/com/phonepctransfer/pc/StorePending.java` | 待发队列簇：`pendingFile`/`addPending`/`removePending`×2/`clearPending`/`loadPending`/`pendingPeerIp`/`loadPendingRaw`/`writePendingRaw`/`toMsg`（1330–1410、1439–1525） | 同上；`clearConversation` 跨两簇（既要 `histLock` 又要 `synchronized(this)`）→ **保留在 `Store` 协调**，内部分别调 `history.clear()`/`pending.clearPeer()` | ~200 |
| `pc/src/com/phonepctransfer/pc/StorePartials.java` | 断点续传半成品簇：`partialsFile`/`putPartial`/`getPartial`/`removePartial`/`purgeStalePartials`/`loadPartials`/`writePartials`（1526–1647） | 同上 | ~125 |

**可选 Tier-2（+1 个文件，再移出约 380 行）**

| 新文件 | 装什么 | 阻力 |
|---|---|---|
| `pc/src/com/phonepctransfer/pc/StorePeers.java` | 设备列表 + 分组键：`bindDevice`/`groups`/`groupOfIp`/`remarkOf`/`setGroupRemark`/`curIpOf`/`setGroupCurIp`/`rotRemoteOf`/`setRotRemoteOf`/`seedGroupKeys`/`migrateGroupState`/`safeKey`/`peers`/`findPeer`/`upsertPeer`/`updatePeer`/`removePeer`/`peerIps`/`addPeerIp`/`removePeerIp`/`pruneStaleIps` + `Group`（70–99、162–423、424–431、597–614、812–1043） | **`convKeyOf` 与 `ckeys` 必须留在 `Store`**（发送热路径、任意线程可调）。若把 `ckeys` 一起搬走，则 `convKeyOf` 也留在 `Store`、只是内部把 `bindings` 换成经 `peers` 类访问 → 会引入跨类调用开销与锁顺序风险，**收益/风险不划算** |

**不建议动**：`convKeyOf` + `ckeys`（热路径）、`Group`（若不做 Tier-2）、`load()`/`save()`（配置序列化的唯一读写口，动了就等于重写配置格式）、`root`/`defaultReceivedDir`/`logDir`（`public final`，`ChatBubbles` 直接读 `store.root`）。

#### 3.3 共享可变状态归属唯一（拆分后谁"拥有"）

| 状态 | 唯一属主 | 谁读它 |
|---|---|---|
| `root`/`defaultReceivedDir`/`logDir`（final） | `Store` | `ChatBubbles`（`store.root`）、`PcMain`、`Log.init` |
| `configFile`（final） | `Store` | `load`/`save`/`mergeConfigFrom` |
| 全部配置字段（`deviceId`…`ffmpegHwaccel`） | `Store` | `load`/`save`、`PcMain`、`CallManager`、`Link`（`store.tcpPort()`/`deviceId()`/`deviceName()`/`allowConnectOut()`） |
| `bindings`/`deviceRemarks`/`groupCurIp`/`rotRemote`/`peerList` | `Store`（Tier-2 后为 `StorePeers`，`Store` 只留 `ckeys` 与转发） | `StorePeers`、`ChatDevices` |
| **`ckeys`(CHM) + `convKeyOf`** | **`Store`（不可搬）** | `Link.groupCandidates`/`clearPendingFor`/`sessionByGroup`（热路径） |
| `mergeTried` | `Store` | `StoreHistory`（归并去重） |
| `histLock` | **`StoreHistory`（随簇搬走）** | 仅历史簇；`clearConversation` 通过 `history` 协调 |
| 历史文件句柄 `historyDir`（final） | `Store` | `StoreHistory`（`S.historyDir`） |
| **部分文件的"当前锁"**：历史=`histLock`、待发=`synchronized(this)`、半成品=`synchronized(this)`、配置=`synchronized(this)` | 各自簇 | **注意：待发/半成品/配置三簇目前共享 `this` 监视器！** 拆开后若各自 `synchronized(this)`（即各自新对象）→ 锁粒度变细。这**改变了并发语义**：原先"配置写 + 待发写"互斥，拆后不再互斥。→ **必须逐个确认这些方法彼此不共享不变式**（现状看它们各自只碰自己的文件，故安全），但这是本方案**唯一必须人工复核**的并发点 |
| `partialsFile()`/`pendingFile()` 的路径计算 | 各自簇 | 由 `S.root` 派生，无状态 |

#### 3.4 必须留在 `Store.java` 的公开签名 / 被引用项

- **公开字段**：`public final Path root` / `defaultReceivedDir` / `logDir`（`ChatBubbles:937` 用 `W.store.root.resolve("thumb_cache")`，**绝不能改成方法**）。
- **公开类型**：`Store.Group`（`ChatDevices` 用 `new ArrayList<Store.Group>()` 与 `group.multi()`/`group.curIp`/`group.ips`/`group.key`）；`public static Path defaultRoot()`（`PcMain:264`）。
- **公开方法**：`load`/`save`/`convKeyOf`/`bindDevice`/`remarkOf`/`setGroupRemark`/`curIpOf`/`setGroupCurIp`/`rotRemoteOf`/`setRotRemoteOf`/`groups`/`groupOfIp`/`deviceId`/`deviceName`/`tcpPort`/`udpPort`/`autoWriteClipboard`(+set)/`allowConnectOut`/`saveDir`(+set)/`lastIp`(+set)/`videoEnabled`(+set)/`videoMaxLevel`(+set)/`ffmpegPath`(+set)/`ffmpegHwaccel`(+set)/`receivedDir`/`receivedDirText`/`peers`/`upsertPeer`/`updatePeer`/`removePeer`/`peerIps`/`addPeerIp`/`removePeerIp`/`pruneStaleIps`/`appendHistory`/`hasMid`/`removeFromHistory`×2/`removeFromHistoryEverywhere`/`loadHistory`/`addPending`/`removePending`×2/`clearPending`/`clearConversation`/`loadPending`/`pendingPeerIp`/`putPartial`/`getPartial`/`removePartial`/`purgeStalePartials`/`updatePeer`。
  → 装箱原则：**方法名/参数/返回类型一律照抄**，实现体改为 `return history.loadHistory(ip, max);` 这样的一行转发（`Link` 里 `replyDataAsk`/`newConsentMid` 就是范例）。
- **构造函数 `public Store(Path root) throws IOException`**：签名与"建 received/logs/history 三目录 + `migrateLegacyHistory()`"的顺序**不可变**；`StoreTest`/`PcMain`/`Store:1740` 都 `new Store(root)`。
- **`safeKey(String)`（static）**：历史文件名口径，搬走需同步 `historyFile`。

#### 3.5 构建影响 —— `sources.txt` 与 `ClassCheck` 的确切结论

与 §2.5 **完全一致**：
1. `build/sources.txt` 由 `build.bat` 第 27–28 行 `dir /s /b "src\*.java"` **动态生成**，非手工清单 → 新增 `StoreMigration`/`StoreHistory`/`StorePending`/`StorePartials`（及可选 `StorePeers`）**放对目录即自动编译**，`build.bat`/`sources.txt` **不用改**。
2. `ClassCheck` 在 `pc/build/probe/ClassCheck.java`（**不在 `pc/tools/`**），机制是"扫 jar 里 `com/phonepctransfer/**/*.class` → 逐个 `Class.forName(name,false,loader)`"，**不硬编码类清单** → **不用改**；只做链接期校验，抓不到逻辑/并发错误；须对 `dist/PhonePcTransfer-PC.jar` 跑，期望 `SMOKE PASS`。
3. **拆分后必须同步改的文件（逐条）**：
   | 改动 | 必须改的文件 |
   |---|---|
   | 新增 4（或 5）个 `StoreXxx.java` | 只改 `Store.java`（加 `final StoreXxx xxx = new StoreXxx(this);` + public 一行转发 + 把被跨类访问的 `private` 字段/方法降为包私有） |
   | `private` → 包私有字段降级 | 只改 `Store.java`（**不得升 public**） |
   | `Store.Group` 若搬到 `StorePeers` | `ChatDevices.java`（`Store.Group` 会变成 `StorePeers.Group`，或需在 `Store` 保留 `public static final class Group` 别名/原地不动）→ **建议 `Group` 原地不动**，避免改 UI |
   | `defaultRoot()` 若搬到 `StoreMigration` 则须保持 `public static` | `PcMain.java`（`Store.defaultRoot()`）→ **或在 `Store` 保留 `public static Path defaultRoot(){ return StoreMigration.defaultRoot(); }`** |
   | `mergeConfigFrom` 改为 `static(Store, Path)` | 只改 `Store.java` + 新增的 `StoreMigration.java` |
   | **测试**：`StoreTest` 只碰 `Store` 的公开面（`new Store(root)`/`load`/`saveDir`/`setSaveDir`/`receivedDirText`）→ **不受影响**；它对 `LinkNames.createUniqueFile` 的调用已在 1.0.6 改为同包直调并加断言（原反射 `Link.createUniqueFile` 的隐患已消除） | `StoreTest.java` |

#### 3.6 风险与回退

**风险（按危险度排序）**

1. **锁粒度被无意改变（本方案第一风险）**：现状 `addPending`/`putPartial`/配置 setter 等大量方法用 `synchronized(this)`（同一个 `Store` 监视器）。拆成 3–4 个类后，若各自 `synchronized(this)` 就是**各自的新对象**，锁的互斥范围从"全 Store 串行"变成"各簇独立"。现状分析显示各簇只碰自己的文件、不共享不变式，**但这是一个必须逐方法复核的语义变化**——复核漏一处就是偶发数据损坏。
2. **`histLock` 归属**：必须**随历史簇搬进 `StoreHistory` 且保持同一个对象**（不能 new 第二把，也不能让 `Store` 与 `StoreHistory` 各持一把）。
3. **`convKeyOf`/`ckeys` 热路径**：任何"顺手把分组逻辑搬走"的动作都会给发送热路径加一层跨类调用，并可能引入 `bindings` 与 `ckeys` 两份状态不一致（协议级故障：会话去重/设备分组错乱）。
4. **`public final` 字段被误改成方法**：`store.root` 被 `ChatBubbles` 直接读，改成 `root()` 会编译失败（可发现，但牵连 UI 文件）。
5. **`Store.Group` 的公开类型名**：一旦搬走，`ChatDevices` 里的 `Store.Group` 全部要改（编译期可发现，但属不必要的连带改动）。
6. **`load()`/`save()` 的配置序列化**：字段名即 config.json 键名，搬家时若把字段"顺手挪到新类并改名"，会静默丢弃用户配置（`saveDir`/`peers`/`bindings` 全丢）。**只挪代码，不许改字段名。**

**回退方案**：同 §2.6，先整目录备份；**每个新文件独立推进**，每个都跑 `javac` 全量 + `build.bat` + `ClassCheck`；`StoreHistory` 最先做（收益大、风险中），`StorePeers`（Tier-2）**留最后做或不做**。

#### 3.7 验证步骤（沙箱内可行）

```bash
cd "<repo>/pc"
JC="tools/jdk21/jdk-21.0.2/bin/javac.exe"
JR="tools/jdk21/jdk-21.0.2/bin/java.exe"
CP="lib/concentus-1.0.2.jar"

find src -name '*.java' > /tmp/pp_sources_split.txt
"$JC" -encoding UTF-8 --release 17 -Xlint:none -cp "$CP" -d build/classes_split_check @/tmp/pp_sources_split.txt

## Store 行为回归：跑 StoreTest（它只用 Store 公开面；前 5 项应正常，第 6 项见 2.6 隐患）
"$JR" -cp "build/classes_split_check;$CP" com.phonepctransfer.pc.StoreTest

## 配置文件/历史文件落盘口径：对比拆分前后生成的 config.json 字节
##   （StoreTest 会打印它自建的 config.json，肉眼比对字段名与顺序）
```

出 jar + 冒烟：`cmd //c build.bat` 后 `"$JR" -cp "build/probe" ClassCheck "dist/PhonePcTransfer-PC.jar"`（期望 `SMOKE PASS`）。

**真机/端到端回归（交用户）**：① 首次启动迁移老数据（把 `pc/history.jsonl` 放回去看是否归档成 `history.legacy-*.jsonl`）；② `%APPDATA%\PhonePcTransfer` 下 `config.json`/`history/*.jsonl`/`partials.json`/`pending.jsonl` 四件套读写正常；③ 改保存目录 → 重启仍生效；④ 待发消息跨重启补发；⑤ 断点续传跨重启找回半成品；⑥ 设备备注/分组键（同一手机换网段仍归同一设备）不丢。

---

### 四、速查：要改 X → 去哪个文件/函数

| 你要改… | 去这里 |
|---|---|
| 连接/重连/断线保活、半开判定、握手超时 | `Link.java`：`connectTo`(625)、`scheduleKeepAlive`(318)、`noteSessionDrop`(356)、`heartbeatLoop`(1190)、`readLoop`(1135)、`STALE_RX_MS`(1687)；拆分后 → `LinkConn` |
| 收到某类帧要做什么（新帧类型/改行为） | `Link.java`：`handleFrame`(1266)；先看 `Protocol.java` 对应构造函数与 `docs/PROTOCOL.md` |
| 同设备多地址候选顺序 / "点 A 连上 B"回落 | `Link.groupCandidates`(406) + `onGroupFallback`(395) + `Store.convKeyOf`；UI 侧 `ChatWindow`/`ChatDevices` |
| 设备同名/多网卡聚合、备注、当前地址、视频旋转角 | `Store.java`：`convKeyOf`(147)、`bindDevice`(162)、`remarkOf`(241)、`curIpOf`(272)、`rotRemoteOf`/`setRotRemoteOf`(310/325)、`groups`(365)、`groupOfIp`(410) |
| 数据流量确认（弹窗/拒绝/红色叹号重试） | `Link.java`：`ensureDataConsent(Async)`(1852/1858)、`replyDataAsk`(1851 壳) → `LinkConsent.java`；结果码 `DataConsent.java`；气泡 `ChatBubbles`(`DenyRetryIcon`) |
| 文件/文件夹发送、续传协商、发送失败补发 | `Link.java`：`sendFile`(1803/1805 壳)、`sendFolder`(1865) → `LinkSend.java`（`doSendFolder*`/`negotiateResume`/`resendPendingFile`） |
| 文件/文件夹接收、落盘、`dir_end` 完整性核对、dir_missing 补发 | `LinkReceive.java`（`onFileQuery`/`onChunk`/`onDirMeta`/`onDirEnd`/`onDirMissing`/`finishDirReceiving`） |
| 文件名净化/非法字符/重名 `(1)`（**序号插在扩展名前**）/相对路径 | PC：`LinkNames.java`（`sanitize`/`segSanitize`/`sanitizeRelPath`/`createUniqueFile`/`createUniqueDir`）；安卓：`util/FileIO.kt`（`sanitize`/`uniqueFileName`/`uniqueFolderName`）—— 两端同口径，见 `StoreTest` 断言。**是否改名一律按实况**：PC 靠 `Files.createFile` 原子创建；安卓 `uniqueFileName` 把「占坑 / MediaStore 条目 / 目录里有没有文件」都再验一遍**文件还在不在**（占坑里那条路径已消失、或条目是自家文件的幽灵条目 → 名字空闲、用回原名），回退目录另由 `createExclusiveIn` 原子换号（2026-10-14） |
| 历史同步（hsync）、删除消息同步（msg_del） | `LinkHsync.java`（`startHsync`/`onHsyncMids`/`onHsyncPull`/`onHsyncMsg`/`onRemoteMsgDel`）+ `Store.loadHistory`/`removeFromHistoryEverywhere` |
| 剪贴板同步（监听/写入/拉取/待发补发） | `Link.java`：`sendClipNotify`(2050)、`pullPeerClip`(2062)、`pushLocalClipIfAny`(2079)、`syncNow`(2093)、`flushPending`(2115/2126) + `Clip.java`（400ms 轮询、`setClipboard`） |
| 语音通话（编解码/码率/通话状态机/窗口） | `CallManager.java`（状态机）+ `VoiceEngine.java`（Opus，concentus）+ `VoiceBitrateController.java` + `VoiceCallWindow.java`；发送侧 `LinkCall.sendAudioTo` |
| 视频通话（只收不发/解码/旋转/窗口） | 信令 `LinkCall.java`；解码 `FfmpegDecoder.java`（`bin/ffmpeg.exe`）；显示 `VideoWindow.java`；装配 `PcMain.installVideoUi`(167)；开关 `Store.videoEnabled`/`videoMaxLevel` |
| 聊天窗口布局/气泡/缩略图/手势/设备列表 | `ChatWindow.java`（窗口与心跳）、`ChatBubbles.java`（气泡）、`ChatDevices.java`（左侧设备行）、`ImageViewer.java`（看图） |
| 拖拽发送的"是否允许/目标设备校验" | `FileDrop.java`（`confirmAndSend`）；必须非管理员运行 |
| 系统托盘 / 单实例 / 启动装配 / 防火墙提示 | `PcMain.java`（`GUARD_PORT=8767`、`Tray`、`callAsync`）+ `run.bat`/`run_admin.bat`（netsh 28765/28766） |
| 端口 / 设备名 / 保存目录 / ffmpeg 路径等配置项 | `Store.java`（getter/setter）+ `pc/config.json`（键名一一对应）；**不要改字段名** |
| 协议帧格式/字段/能力位协商 | `Protocol.java` + `docs/PROTOCOL.md`（协议唯一权威源；改协议两处同改） |
| 设备发现（UDP 广播/网段扫描/子网口径） | `Discovery.java`（`scanOnce`/`subnetPrefixOf`/`recvLoop`）；自检在 `pc/build/probe/SubnetCheck.java` |
| 打包/版本号/安装包 | `make-msi.sh`（工程根目录；`APP_VER`、ProductVersion 编码）+ `build.bat`（工程根目录；jar）；产物 `pc/dist/PhonePcTransfer-PC.jar`、`out/手机电脑互传-*.msi` |
| 编译报"缺类/坏常量池"类问题、发布前冒烟 | `pc/build/probe/ClassCheck.java`（对 jar 跑，期望 `SMOKE PASS`） |
| 传文件夹是否漏/坏 | `pc/tools/verify_tree.py`（逐文件名字+字节数，可选 `--md5`） |
| 目录树细节 | `pc/build/sources.txt`（构建时动态生成的源文件清单） |

---

#### 附：本次核对到的既有问题清单（不在拆分范围，建议单独立项）

1. ~~**`StoreTest` 反射 `Link.createUniqueFile` 已失效**（方法已迁至 `LinkNames`）→ `StoreTest` 第 6 项必然抛 `NoSuchMethodException`（§2.6 风险 1）。~~
   **已修（2026-09-22，1.0.6）**：改同包直调 `LinkNames`，并加了重名落盘名断言（详见 §2.6 风险 1）。
2. **`LinkReceive.java:12-13` 的注释与实情不符**：注释称 `Rx/DirRx/VerifyState` "转为顶层包私有类"，实际它们是 `LinkReceive` 的 `static final class` 内部类（`Link` 靠 `import ...LinkReceive.Rx` 引用）。
3. **`ClassCheck.java` / `SubnetCheck.java` 位于 `pc/build/probe/` 而非 `pc/tools/`**，且 `build/` 是构建临时目录 → 有被清理脚本/手工 `rm -rf build` 一并删掉的风险，建议迁入 `pc/tools/`（改名 `probe/`）并纳入版本管理。（仅建议，未改动）
4. `pc/tools/InkProbe.java` 的公开类名是 `E`，与文件名不一致（只能靠单文件源码启动跑）。
5. `pc/build/` 下存在大量一次性目录（`x`/`x2`…`x5`、`vtest`/`vtest2`、`classes-old-*`、`classes-polluted-*`），属历史调试残留。
6. `src/` 下 `StoreTest.java`/`TestClient.java` 会被 `dir /s /b "src\*.java"` 一并编进产物 jar（含测试 main）。

