# 文件夹传输 · 双向回归验证记录（2026-09-17）

## 目标

验证 v2.6「文件夹完整性闭环 + 落盘竞态修复」在**两个方向**都能做到
「文件一个不少、字节一个不差」：

| 方向 | 发起方 | 接收方 | 结果 |
|---|---|---|---|
| PC → 手机 | PC（无头推送器驱动真实 `Link.sendFolder`） | 手机 a12（192.168.1.14，有线） | ✅ 75/75 字节级一致 |
| 手机 → PC | 手机 App（SAF 选目录，真人路径） | PC GUI（D:\Downloads） | ✅ 75/75 MD5 级一致 |

测试素材：`C:\testdata\jianpian` —— 75 个文件 / 76,886,752 字节 / 6 个子目录（含 `.dll`、`.ico`、`.exe`）。

## 手机 → PC 方向（本次）

时间线（PC 端 `pc/logs/pc.log`）：

```
01:35:50.444 [I] 开始接收文件夹 jianpian (1)（75 个文件）→ D:\Downloads\jianpian (1) (1)
01:35:55.070 [I] 文件夹接收完成 jianpian (1)：75/75，全部校验通过
```

手机端（`adb logcat -s PP-Link`）：

```
01:35:59.635 I PP-Link : 文件夹发送完成 jianpian (1)（75 个文件，73.3 MB）
```

* 耗时 4.6 s；PC 端日志中**没有任何** `落盘失败` / `落盘字节数不符` / `dir_missing` / 补发记录
  （对照修复前：同一素材是一堆 `落盘失败 … Stream Closed` + 3 个坏文件）。
* 落地目录名是 `jianpian (1) (1)`：手机侧树名本来就是 `jianpian (1)`，而 PC 侧同名目录已被
  上一轮（被掐断的）测试占用 → 接收端按常规加了后缀。

### 独立核对（不看两端的自述）

```
python pc/tools/verify_tree.py "C:/testdata/jianpian" "D:/Downloads/jianpian (1) (1)" --md5

源端   : C:/testdata/jianpian
目标端 : D:/Downloads/jianpian (1) (1)
源端   : 75 个 / 76886752 字节
目标端 : 75 个 / 76886752 字节
缺失   : 0 []
多出   : 0 []
大小不符: 0
MD5 不符: 0
0 字节  : 0 []
结论: ✅ 75/75 全部一致（无漏件、无截断）
```

## 复现方法（两端都可无头跑）

```bash
# PC → 手机：驱动真实 Link.sendFolder，落到手机 Download/手机电脑互传/<文件夹>/
java -cp "dist/PhonePcTransfer-PC.jar;build/probe" com.phonepctransfer.pc.FolderPushTest \
     192.168.1.14 "C:/testdata/jianpian" 300

# 手机 → PC：PC 端挂着等对端推过来（保持在线，收到即自检并清点落地）
java -cp "dist/PhonePcTransfer-PC.jar;build/probe" com.phonepctransfer.pc.ReceiveDaemon \
     192.168.1.14 600

# 收官：逐文件比对（名字 + 字节数，加 --md5 做内容级）
python pc/tools/verify_tree.py <源目录> <接收目录> --md5
```

手机侧发起（无外部入口，只能走界面）用 `pc/tools/ui_auto.py`：dump 界面 → 找节点 → 点击。

## 踩坑记录

1. **`input tap` 在 MIUI 上静默失效**：必须走 root —— `adb shell "su -c 'input tap x y'"`。
   未 root 注入时点击完全没反应（设置页都点不开），极易误判成「坐标算错」。
2. **SAF 选择文件夹后还有一层系统授权框**（「要允许手机电脑互传访问 … 中的文件吗？」），
   它不在 uiautomator 的 dump 里，得靠截图 + 像素定位按钮坐标（浅蓝 `#A8C7FA` 系）再点「允许」。
3. **守护脚本的完成判定绝不能看 `Msg.status`**：接收侧气泡创建时的默认值就是 `OK`，
   一看就以为收完 → `System.exit` → 把还在传的连接掐断。手机端会报
   `文件夹发送失败(SocketException: Broken pipe)`，PC 端只落一个 0 字节文件。
   正确判据是 App 自己的收尾日志：`文件夹接收完成` / `文件夹接收不完整` / `文件夹接收失败`。
4. **PC GUI 与无头测试器不能同时跑**：TCP 28765 只有一个能 bind，
   无头进程会反复 `Address already in use` 空转。跑测试前先确认 PC 端没有 GUI 实例
   （`netstat -ano | grep 28765` 看占用 PID）。
