package com.phonepctransfer.pc;

/** 开机自启往返验证：注册 → 读回 → 取消 → 读回（不留任何注册项）。 */
public final class AutostartProbe {
    public static void main(String[] args) {
        System.out.println("supported          = " + Autostart.supported());
        System.out.println("before             = " + Autostart.registeredCommand());
        String cmd = Autostart.launchCommand();
        System.out.println("launchCommand      = " + cmd);
        System.out.println("enable()           = " + Autostart.enable());
        System.out.println("after enable       = " + Autostart.registeredCommand());
        System.out.println("disable()          = " + Autostart.disable());
        System.out.println("after disable      = " + Autostart.registeredCommand());
    }
}
