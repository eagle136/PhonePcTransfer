package com.phonepctransfer.pc;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * 图片解码工具：JDK 内置 ImageIO 读不了 webp/avif/heic 时的两级兜底。
 *
 * ① 系统解码（首选）：Windows 装了 WebP/AVIF/HEIF 图片扩展（WIC 解码器）后，系统本身
 *    就能显示这些图。通过 PowerShell + WPF BitmapDecoder（底层即 WIC）借系统之力解码，
 *    输出 jpg。无任何额外依赖，Windows 自带 PowerShell。
 * ② ffmpeg 兜底：系统未装对应扩展（或非 Windows）且本机有 ffmpeg 时走它。
 *
 * 都不可用时优雅降级（返回 null → 界面退回只显示文件名，不影响其他功能）。
 */
public final class ImageCodec {

    private ImageCodec() {
    }

    /** ffmpeg 可执行文件路径（惰性探测一次，找不到则 null） */
    private static volatile String ffmpegPath;
    private static volatile boolean ffmpegProbed;

    /** 探测本机 ffmpeg（PATH 或常见固定位置） */
    public static String ffmpeg() {
        if (!ffmpegProbed) {
            ffmpegProbed = true;
            String[] candidates = {
                    "ffmpeg", "ffmpeg.exe",
                    "C:\\ffmpeg\\bin\\ffmpeg.exe", "C:\\ffmpeg\\bin\\ffmpeg",
                    "C:\\ffmpeg\\bin\\ffmpeg.exe", "C:\\ffmpeg\\bin\\ffmpeg",
            };
            for (String c : candidates) {
                try {
                    Process p = new ProcessBuilder(c, "-version")
                            .redirectErrorStream(true).start();
                    p.getInputStream().close();
                    if (p.waitFor() == 0) {
                        ffmpegPath = c;
                        break;
                    }
                    p.destroyForcibly();
                } catch (Throwable ignore) {
                }
            }
        }
        return ffmpegPath;
    }

    /**
     * 读图：先走 JDK 内置 ImageIO，读不出（webp/avif/heic 等）再走系统 WIC，最后 ffmpeg。
     *
     * @param src    源图片文件
     * @param maxDim 解码时长边上限（降采样，避免解出巨图）；传 <=0 表示不缩放
     */
    public static BufferedImage read(File src, int maxDim) {
        return decode(src, maxDim, null);
    }

    /**
     * 读图并缓存：若 {@code cacheOut} 非空则把解码结果落成 jpg 缓存文件
     * （下次直接 ImageIO 读缓存，省去重复起外部进程的开销）。
     */
    public static BufferedImage readCached(File src, int maxDim, File cacheOut) {
        if (cacheOut != null && cacheOut.isFile() && cacheOut.length() > 0) {
            try {
                BufferedImage img = ImageIO.read(cacheOut);
                if (img != null) {
                    return img;
                }
            } catch (Throwable ignore) {
            }
        }
        return decode(src, maxDim, cacheOut);
    }

    /** 解码主流程：ImageIO（源端降采样）→ 系统 WIC → ffmpeg */
    private static BufferedImage decode(File src, int maxDim, File cacheOut) {
        if (src == null || !src.isFile()) {
            return null;
        }
        BufferedImage img = readViaImageIO(src, maxDim);
        if (img != null) {
            return img;
        }
        img = readViaSystem(src, maxDim, cacheOut);
        if (img != null) {
            return img;
        }
        return readViaFfmpeg(src, maxDim, cacheOut);
    }

    /**
     * ImageIO 读取，支持按 maxDim **源端降采样**（setSourceSubsampling，
     * 等价于 Android 的 inSampleSize）：解码时直接跳采样，避免巨图整幅
     * 解进内存（旧实现 ImageIO.read 无视 maxDim，几十 MP 的照片既慢又费堆）。
     */
    private static BufferedImage readViaImageIO(File src, int maxDim) {
        try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(src)) {
            if (iis == null) {
                return ImageIO.read(src);
            }
            java.util.Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                return ImageIO.read(src);
            }
            javax.imageio.ImageReader r = it.next();
            r.setInput(iis);
            try {
                int w = r.getWidth(0);
                int h = r.getHeight(0);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                int sample = 1;
                if (maxDim > 0) {
                    while (w / sample > maxDim || h / sample > maxDim) {
                        sample *= 2;
                    }
                }
                javax.imageio.ImageReadParam p = r.getDefaultReadParam();
                if (sample > 1) {
                    p.setSourceSubsampling(sample, sample, 0, 0);
                }
                return r.read(0, p);
            } finally {
                r.dispose();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // ==================== ① 系统 WIC（PowerShell + WPF BitmapDecoder） ====================

    /** Windows 判定 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /** PowerShell 单引号字符串转义（'' → 字面单引号） */
    private static String psq(String s) {
        return s.replace("'", "''");
    }

    /** WIC 路径连续失败计数（≥2 后熔断，避免每张缩略图都白起一次 PowerShell 进程） */
    private static volatile int wicFailures;
    private static final Object wicLock = new Object();

    /**
     * 借系统 WIC 解码器（经 PowerShell/WPF）把 webp/avif/heic 转成 jpg。
     * 系统装了对应图片扩展即可解（资源管理器能显示缩略图 = 扩展已装）。
     * 失败原因写 pc.log，连续失败 2 次后熔断直接走 ffmpeg。
     */
    static BufferedImage readViaSystem(File src, int maxDim, File cacheOut) {
        if (!isWindows() || wicFailures >= 2) {
            return null;
        }
        File out = null;
        File err = null;
        try {
            out = cacheOut != null ? cacheOut : File.createTempFile("ppt_wic_", ".jpg");
            if (out.getParentFile() != null && !out.getParentFile().exists()) {
                out.getParentFile().mkdirs();
            }
            err = new File(out.getAbsolutePath() + ".err");
            String script =
                    "$ErrorActionPreference='Stop'\n"
                    + "try {\n"
                    + "[void][System.Reflection.Assembly]::LoadWithPartialName('PresentationCore')\n"
                    + "$fs=[System.IO.File]::OpenRead('" + psq(src.getAbsolutePath()) + "')\n"
                    + "$dec=[System.Windows.Media.Imaging.BitmapDecoder]::Create($fs,"
                    + "[System.Windows.Media.Imaging.BitmapCreateOptions]::PreservePixelFormat,"
                    + "[System.Windows.Media.Imaging.BitmapCacheOption]::OnLoad)\n"
                    + "$frame=$dec.Frames[0]\n"
                    + "$fs.Close()\n"
                    + "$s=1.0\n"
                    + (maxDim > 0
                            ? "$s=[Math]::Min(1.0," + maxDim
                                    + "/[Math]::Max(1,[Math]::Max($frame.PixelWidth,$frame.PixelHeight)))\n"
                            : "")
                    + "$tb=New-Object System.Windows.Media.Imaging.TransformedBitmap\n"
                    + "$mt=New-Object System.Windows.Media.MatrixTransform -ArgumentList $s,0,0,$s,0,0\n"
                    + "$tb.BeginInit(); $tb.Source=$frame; $tb.Transform=$mt; $tb.EndInit()\n"
                    + "$fc=New-Object System.Windows.Media.Imaging.FormatConvertedBitmap\n"
                    + "$fc.BeginInit(); $fc.Source=$tb;"
                    + "$fc.DestinationFormat=[System.Windows.Media.PixelFormats]::Bgr32; $fc.EndInit()\n"
                    + "$enc=New-Object System.Windows.Media.Imaging.JpegBitmapEncoder\n"
                    + "$enc.QualityLevel=85\n"
                    + "$enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($fc))\n"
                    + "$fs2=[System.IO.File]::Create('" + psq(out.getAbsolutePath()) + "')\n"
                    + "$enc.Save($fs2); $fs2.Close()\n"
                    + "} catch {\n"
                    + "[System.IO.File]::WriteAllText('" + psq(err.getAbsolutePath())
                    + "', $_.Exception.ToString(), [System.Text.Encoding]::UTF8)\n"
                    + "exit 1\n"
                    + "}\n";
            String encoded = Base64.getEncoder()
                    .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectErrorStream(true).start();
            InputStream is = p.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                if (sb.length() < 3000) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                // 继续读完剩余输出，防止管道阻塞
            }
            is.close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("WIC 解码超时", sb.toString());
                return null;
            }
            if (p.exitValue() != 0) {
                fail("WIC 解码失败(exit=" + p.exitValue() + ")", errText(err));
                return null;
            }
            BufferedImage img = ImageIO.read(out);
            if (img == null) {
                fail("WIC 输出无法回读", errText(err));
                return null;
            }
            synchronized (wicLock) {
                wicFailures = 0;
            }
            return img;
        } catch (Throwable t) {
            fail("WIC 解码异常: " + t, "");
            return null;
        } finally {
            if (out != null && cacheOut == null && out.exists()) {
                out.delete();  // 仅临时文件用完即删；缓存文件保留
            }
            if (err != null && err.exists()) {
                err.delete();
            }
        }
    }

    /** WIC 失败记账：写日志，连续 2 次后熔断 */
    private static void fail(String why, String detail) {
        synchronized (wicLock) {
            wicFailures++;
        }
        String tail = detail == null ? "" : detail.trim();
        if (tail.length() > 800) {
            tail = tail.substring(0, 800);
        }
        Log.w("ImageCodec: " + why
                + (tail.isEmpty() ? "" : " —— " + tail.replaceAll("[\\r\\n\\t]", " ")));
    }

    /** 读取 PowerShell 写出的异常明文（读完即删） */
    private static String errText(File err) {
        if (err == null || !err.isFile()) {
            return "";
        }
        try {
            String s = new String(Files.readAllBytes(err.toPath()), StandardCharsets.UTF_8);
            err.delete();
            int cut = s.indexOf('\n');
            return cut > 0 ? s.substring(0, Math.min(s.length(), cut + 400)) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== ② ffmpeg 兜底 ====================

    /** 用 ffmpeg 解码成 jpg（可选写到 cacheOut），再交给 ImageIO */
    private static BufferedImage readViaFfmpeg(File src, int maxDim, File cacheOut) {
        String ff = ffmpeg();
        if (ff == null) {
            return null;
        }
        File tmp = null;
        try {
            tmp = cacheOut != null ? cacheOut : File.createTempFile("ppt_img_", ".jpg");
            if (tmp.getParentFile() != null && !tmp.getParentFile().exists()) {
                tmp.getParentFile().mkdirs();
            }
            java.util.List<String> cmd = new java.util.ArrayList<String>();
            cmd.add(ff);
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-i");
            cmd.add(src.getAbsolutePath());
            if (maxDim > 0) {
                cmd.add("-vf");
                cmd.add("scale=" + maxDim + ":" + maxDim + ":force_original_aspect_ratio=decrease");
            }
            cmd.add("-frames:v");
            cmd.add("1");
            cmd.add("-q:v");
            cmd.add("3");
            cmd.add(tmp.getAbsolutePath());
            cmd.add("-y");

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            while (is.read(buf) != -1) {
                // 丢弃输出，防止管道阻塞
            }
            is.close();
            if (p.waitFor() != 0) {
                return null;
            }
            return ImageIO.read(tmp);
        } catch (Throwable t) {
            return null;
        } finally {
            if (tmp != null && cacheOut == null && tmp.exists()) {
                tmp.delete();  // 仅临时文件用完即删；缓存文件保留
            }
        }
    }
}
