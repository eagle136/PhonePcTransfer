# ============================================================================
#  手机电脑互传 PC 端 —— 卸载时「是否保留用户数据」脚本
#
#  谁调用：MSI 卸载流程里的**两条 immediate 自定义动作**（见 make-msi.sh 的
#          wxi-inject 段）。整份文件在构建时被转成 UTF-16LE base64 内联进
#          MSI 命令行，命令行的**最前面**再拼一行 `$env:PHONEPCTRANSFER_UNINSTALL_ASK="1";`
#          / `="0";` 来告诉脚本这次该不该问用户：
#            1 = 有界面卸载（UILevel>2，含 设置→应用、控制面板、msiexec /x）→ 弹窗问；
#            0 = 无人值守卸载（/qn，UILevel=2）→ 不问，直接保留数据。
#          两条动作在 WiX 里用条件 `UILevel > 2` / `UILevel <= 2` 互斥，
#          MSI 自己就会跳过不该跑的那条（日志里是 "Skipping action: ... (condition is false)"）。
#
#  ⚠ 本文件是**源**，不是运行时被读取的文件：构建时读进来、编码后内联，安装目录里没有它。
#     为什么用 -EncodedCommand 内联而不是 -File 引脚本文件：
#       1) 命令行里只有 ASCII：脚本正文的方括号会被 MSI 当属性引用吃掉；若安装器回退到
#          en-US 语言包（数据库 codepage 1252）中文还会让 light.exe 报 LGHT0311
#          （见 make-msi.sh 的 -J-Duser.language 说明）；
#       2) -EncodedCommand 不受 PowerShell 执行策略（含组策略 AllSigned）限制；
#       3) 不依赖安装目录里还有没有我们的文件。
#     ⚠ 命令行里**不能**再挂位置参数：实测 powershell.exe 在 -EncodedCommand 后面接任何
#        参数会直接报「无法处理命令，因为已使用 -Command 或 -EncodedCommand 指定了命令」并 rc=1。
#        所以「这次是不是无人值守」由构建期烤进载荷的环境变量传递，不靠参数、也不靠
#        父进程命令行（2026-10-05 实测：自定义动作里读 msiexec 父进程的 CommandLine 恒为空）。
#
#  行为（2026-10-05 用户定调「卸载时允许选择是否保留用户数据」）：
#    · 有界面卸载：弹窗问「是否一并删除用户数据」——「是」删、「否」保留；
#        默认按钮是「否」，右上角 ✕ / ESC 也按**保留**处理（删除必须是明确选择）。
#        弹窗是同步的：脚本退出前 MSI 一直等在这条动作上，所以不会「先卸完再问」。
#    · 无人值守卸载（/qn）：**不弹窗**、保留数据，免得批量/脚本卸载被一个对话框卡死。
#    · 删数据只删数据根（默认 %APPDATA%\PhonePcTransfer）里除 received\ 之外的东西：
#        received\ = 用户收到的文件默认落点，属于「已经传输保存的文件」，
#        按用户口径**不在删除范围内**；saveDir 指向别处时更不会碰。
#    · 两种情况下都顺手清掉应用自己铺的中文快捷方式（桌面 + 开始菜单组里的 手机电脑互传.lnk）：
#        MSI 只认得它自己铺的 PhonePcTransfer.lnk，而且 RemoveFolder 只在目录**空**时
#        才删目录 —— 中文 .lnk 会把开始菜单组目录留成一个空壳（用户实测残留）。
#
#  可用环境变量：
#    PHONEPCTRANSFER_UNINSTALL_ASK   1=弹窗问 / 0=不问按保留处理（构建期烤进载荷，正常不需要手动设）
#    PHONEPCTRANSFER_HOME            覆盖数据根（与 PC 端 Store 的口径一致，测试用）
#    PHONEPCTRANSFER_UNINSTALL_ANSWER = keep|delete  跳过弹窗直接给答案（测试/自动化用）
# ============================================================================

$ErrorActionPreference = 'SilentlyContinue'

# ---- 日志位置：优先 %TEMP%，写不了退回 %LOCALAPPDATA% / %APPDATA%（自定义动作里的 TEMP 可能是别人家的）----
function Get-LogFile {
    foreach ($dir in @($env:TEMP, $env:LOCALAPPDATA, $env:APPDATA)) {
        if ([string]::IsNullOrWhiteSpace($dir)) { continue }
        if ([System.IO.Directory]::Exists($dir)) { return (Join-Path $dir 'PhonePcTransfer-uninstall.log') }
    }
    return $null
}
$logFile = Get-LogFile

function Write-Log([string]$msg) {
    if ($null -eq $logFile) { return }
    $line = ('[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg)
    try { Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8 } catch { }
}

# ---- 数据根：与 Store.defaultRoot() 同一口径（PHONEPCTRANSFER_HOME 优先）----
function Get-DataRoot {
    $home2 = $env:PHONEPCTRANSFER_HOME
    if (-not [string]::IsNullOrWhiteSpace($home2)) { return $home2.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($env:APPDATA)) { return (Join-Path $env:APPDATA 'PhonePcTransfer') }
    return (Join-Path $HOME '.phonepctransfer')
}

# ---- 删用户数据：除 received\ 外全删；删空了连根目录一起收掉 ----
function Remove-UserData([string]$root) {
    if (-not (Test-Path -LiteralPath $root)) { return 'no-data' }
    $touched = New-Object System.Collections.ArrayList
    foreach ($child in @(Get-ChildItem -LiteralPath $root -Force)) {
        if ($child.Name -eq 'received') { continue }          # 已传输并保存的文件：不删
        Remove-Item -LiteralPath $child.FullName -Recurse -Force
        [void]$touched.Add($child.Name)
    }
    $rec = Join-Path $root 'received'
    if (Test-Path -LiteralPath $rec) {
        $left = @(Get-ChildItem -LiteralPath $rec -Force)
        if ($left.Count -eq 0) { Remove-Item -LiteralPath $rec -Recurse -Force }
    }
    $still = @(Get-ChildItem -LiteralPath $root -Force)
    if ($still.Count -eq 0) { Remove-Item -LiteralPath $root -Recurse -Force }
    if ($touched.Count -eq 0) { return 'empty' }
    return ($touched -join ',')
}

# ---- 清应用自己铺的快捷方式（桌面 + 开始菜单组；MSI 不认中文名那两条）----
function Remove-Shortcuts {
    $names = @('手机电脑互传.lnk', 'PhonePcTransfer.lnk')
    $desktop = [Environment]::GetFolderPath('Desktop')
    $group = Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs\PhonePcTransfer'
    $removed = New-Object System.Collections.ArrayList
    foreach ($dir in @($desktop, $group)) {
        if ([string]::IsNullOrEmpty($dir) -or -not (Test-Path -LiteralPath $dir)) { continue }
        foreach ($n in $names) {
            $f = Join-Path $dir $n
            if (Test-Path -LiteralPath $f) {
                Remove-Item -LiteralPath $f -Force
                if (-not (Test-Path -LiteralPath $f)) { [void]$removed.Add($f) }
            }
        }
        # 开始菜单那个组目录：只有空了才收掉，别误删用户自己放的东西
        if ($dir -eq $group) {
            $left = @(Get-ChildItem -LiteralPath $dir -Force)
            if ($left.Count -eq 0) { Remove-Item -LiteralPath $dir -Recurse -Force }
        }
    }
    return ($removed -join ' | ')
}

# ---- 弹窗问一句：返回 'delete' / 'keep' ----
function Ask-KeepOrDelete([string]$root) {
    Add-Type -AssemblyName PresentationFramework
    $text = @"
卸载 手机电脑互传

是否一并删除用户数据？

  「是」删除：设备列表、设备备注、聊天记录、待发队列、保存位置等设置
              全部删除（$root）。
              已经传输并保存到接收目录的文件不受影响。

  「否」保留：以上数据留在原处，重新安装后会自动接着用。

（无论选哪个，程序本身都会被卸载）
"@
    $r = [System.Windows.MessageBox]::Show(
            $text, '卸载 手机电脑互传',
            [System.Windows.MessageBoxButton]::YesNo,
            [System.Windows.MessageBoxImage]::Question,
            [System.Windows.MessageBoxResult]::No)
    if ($r -eq [System.Windows.MessageBoxResult]::Yes) { return 'delete' }
    return 'keep'
}

# ============================== 主流程 ==============================

function Invoke-Main {
    $root = Get-DataRoot
    # ASK 由构建期烤进载荷：'1' = 有界面卸载（该问），其余（含没设）= 无人值守，不问、保留。
    $askMode = $env:PHONEPCTRANSFER_UNINSTALL_ASK
    Write-Log ("开始：数据根=" + $root + ' 弹窗模式=[' + $askMode + ']')

    # 卸载中的程序先收掉：它还在托盘里跑的话，删掉的数据会被它退出时又写回来
    $proc = Get-Process -Name 'PhonePcTransfer' -ErrorAction SilentlyContinue
    if ($null -ne $proc) {
        $proc | Stop-Process -Force
        Write-Log '已结束运行中的 PhonePcTransfer.exe'
    }

    $answer = $env:PHONEPCTRANSFER_UNINSTALL_ANSWER
    if ($answer -eq 'delete' -or $answer -eq 'keep') {
        Write-Log ('答案来自环境变量 PHONEPCTRANSFER_UNINSTALL_ANSWER=' + $answer)
    } elseif ($askMode -eq '1') {
        $answer = Ask-KeepOrDelete $root
    } else {
        $answer = 'keep'
        Write-Log '无人值守卸载（载荷里 ASK=0）：不弹窗，保留用户数据'
    }

    if ($answer -eq 'delete') {
        $touched = Remove-UserData $root
        Write-Log ('用户选择删除 → 已删: ' + $touched)
    } else {
        Write-Log '用户选择保留 → 用户数据原样不动'
    }

    $sc = Remove-Shortcuts
    Write-Log ('快捷方式清理: ' + $sc)
    Write-Log '结束'
}

try {
    Invoke-Main
    exit 0
} catch {
    Write-Log ('异常（忽略，不影响卸载）: ' + $_)
    exit 0
}
