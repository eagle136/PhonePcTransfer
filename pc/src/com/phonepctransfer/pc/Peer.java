package com.phonepctransfer.pc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备列表里的一台对端设备（与 Android 端 data/Peer.kt 对齐）。
 *  - `ip`        连接地址（用户可改）
 *  - `remark`    用户备注名（右键可改）；为空时回退到广播名、再回退到 IP
 *  - `lastId/…`  UDP 自动发现时记录的对方标识与广播名
 *  - `devs`      同一个 IP 上出现过的**其它**设备身份（2026-10-05 同 IP 多设备）
 */
public final class Peer {

    public String ip = "";
    public String remark = "";
    public String lastId = "";
    public String lastName = "";
    public long lastSeenAt = 0L;

    /**
     * 同一个 IP 上出现过的全部设备身份（含当前 lastId 那一台）：
     * id + 广播名 + **它自己**最近一次广播/握手的时间。
     *
     * <p>为什么需要：两台不同的物理设备可能落在同一个 IP 上（同一路由 DHCP 复用、或者
     * 一端主动连入导致本端只能看到对方的 IP）。旧模型「一个 IP = 一台设备」会让两台设备
     * 抢同一行 —— 广播每 2s 交替覆盖 lastId，组键来回漂移、备注/当前地址被反复搬运
     *（用户看到的「切换不过去 + 卡」）。现在：谁先在这个 IP 上被识别谁就是**活动设备**，
     * 后来者只登记在这里当**别名**（列表里显示成「名字-2」那一行），等用户手动确认
     *（断开旧连接）才切换（见 Store.promoteDev）；活动设备自己那条记录用来判「它是否已离开」。
     */
    public final List<Dev> devs = new ArrayList<Dev>();

    /** 别名设备上限：同 IP 超过这个数就不再记新的（防止异常广播把配置撑爆） */
    public static final int MAX_DEVS = 8;

    public Peer() {
    }

    public Peer(String ip) {
        this.ip = ip == null ? "" : ip;
    }

    /** 列表里显示的名字：备注 > 广播名 > IP */
    public String displayName() {
        if (remark != null && !remark.isEmpty()) {
            return remark;
        }
        if (lastName != null && !lastName.isEmpty()) {
            return lastName;
        }
        return ip == null ? "" : ip;
    }

    /** 该 IP 上的别名设备（另一个 deviceId） */
    public static final class Dev {
        public String id = "";
        public String name = "";
        public long seenAt = 0L;

        public Dev() {
        }

        public Dev(String id, String name, long seenAt) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.seenAt = seenAt;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id == null ? "" : id);
            m.put("name", name == null ? "" : name);
            m.put("seenAt", Long.valueOf(seenAt));
            return m;
        }

        public static Dev fromJson(Map<String, Object> m) {
            Dev d = new Dev();
            d.id = Json.str(m, "id", "");
            d.name = Json.str(m, "name", "");
            d.seenAt = Json.lng(m, "seenAt", 0L);
            return d;
        }
    }

    /** 取某个别名的记录（没有返回 null） */
    public Dev dev(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Dev d : devs) {
            if (id.equals(d.id)) {
                return d;
            }
        }
        return null;
    }

    /**
     * 记一次「这个 IP 上又看到了这台设备」。
     *
     * <p>时间戳每次都刷新（静默，不算结构变化）；返回值只表示**用户可见的结构**动了
     * —— 新出现的设备身份、或者名字变了。沿用 lastSeenAt 的老口径：每 2s 一次广播
     * 不能让界面/状态栏刷屏。
     */
    public boolean noteDev(String id, String name, long seenAt) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        String n = name == null ? "" : name;
        Dev d = dev(id);
        if (d == null) {
            if (devs.size() >= MAX_DEVS) {
                return false;
            }
            devs.add(new Dev(id, n, seenAt));
            return true;
        }
        boolean changed = false;
        if (!n.isEmpty() && !n.equals(d.name)) {
            d.name = n;
            changed = true;
        }
        if (seenAt > d.seenAt) {
            d.seenAt = seenAt;
        }
        return changed;
    }

    /** 摘掉一条别名（它变成活动设备、或所在地址被删） */
    public boolean removeDev(String id) {
        Dev d = dev(id);
        if (d == null) {
            return false;
        }
        devs.remove(d);
        return true;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ip", ip == null ? "" : ip);
        m.put("remark", remark == null ? "" : remark);
        m.put("lastId", lastId == null ? "" : lastId);
        m.put("lastName", lastName == null ? "" : lastName);
        m.put("lastSeenAt", Long.valueOf(lastSeenAt));
        // 没有别名时**不写这个 key**：老配置格式一字不变（向后兼容、便于人工比对）
        if (!devs.isEmpty()) {
            List<Object> arr = new ArrayList<Object>();
            for (Dev d : devs) {
                arr.add(d.toJson());
            }
            m.put("devs", arr);
        }
        return m;
    }

    public static Peer fromJson(Map<String, Object> m) {
        Peer p = new Peer();
        p.ip = Json.str(m, "ip", "");
        p.remark = Json.str(m, "remark", "");
        p.lastId = Json.str(m, "lastId", "");
        p.lastName = Json.str(m, "lastName", "");
        p.lastSeenAt = Json.lng(m, "lastSeenAt", 0L);
        Object arr = m.get("devs");
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                Map<String, Object> dm = Json.obj(o);
                if (dm == null) {
                    continue;
                }
                Dev d = Dev.fromJson(dm);
                if (!d.id.isEmpty() && p.dev(d.id) == null && p.devs.size() < MAX_DEVS) {
                    p.devs.add(d);
                }
            }
        }
        return p;
    }
}

