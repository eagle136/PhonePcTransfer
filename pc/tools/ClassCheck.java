package build.probe;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/** 无 GUI 冒烟：把 jar 里自家类全部 Class.forName(name, false, loader) 走一遍（不跑静态块，不起 GUI）。 */
public class ClassCheck {
    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        List<String> own = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath)) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.startsWith("com/phonepctransfer/") && n.endsWith(".class")) {
                    own.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        }
        ClassLoader loader = ClassCheck.class.getClassLoader();
        int ok = 0, bad = 0;
        for (String cn : own) {
            try {
                Class.forName(cn, false, loader);
                ok++;
            } catch (Throwable t) {
                bad++;
                System.out.println("BAD " + cn + " -> " + t);
            }
        }
        System.out.println("own classes: " + own.size() + ", ok: " + ok + ", bad: " + bad);
        System.out.println(bad == 0 ? "SMOKE PASS" : "SMOKE FAIL");
    }
}
