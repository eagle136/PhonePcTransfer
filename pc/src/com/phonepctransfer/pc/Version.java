package com.phonepctransfer.pc;

// ==================================================================
// 运行时版本号：优先读 jar/classes 里的 version.properties（build_jar.py 打包时写入），
// 其次读 MANIFEST 的 Implementation-Version；都拿不到返回空串 ——
// 空串时「版本变化是否清数据」一律不提示、不动数据，避免误判把用户台账清了。
// ==================================================================

final class Version {

    private Version() {
    }

    static String current() {
        try (java.io.InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                String v = p.getProperty("version", "").trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Exception ignore) {
        }
        Package pkg = Version.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null ? "" : v.trim();
    }
}
