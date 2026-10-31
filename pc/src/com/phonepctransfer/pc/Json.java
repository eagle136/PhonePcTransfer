package com.phonepctransfer.pc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 / 序列化。
 * JDK 无内置 JSON 库，PC 端为保持"零外部依赖"自行实现，只覆盖本项目协议用到的子集：
 * object / array / string / number / true / false / null。
 */
public final class Json {

    private Json() {
    }

    // ==================== 解析 ====================

    public static Object parse(String s) {
        P p = new P(s);
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("JSON 尾部有多余字符 @" + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("不是 JSON 对象: " + s);
        }
        return (Map<String, Object>) o;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " @" + i);
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw err("意外结束");
            }
            return s.charAt(i);
        }

        Object value() {
            ws();
            char c = peek();
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    lit("true");
                    return Boolean.TRUE;
                case 'f':
                    lit("false");
                    return Boolean.FALSE;
                case 'n':
                    lit("null");
                    return null;
                default:
                    return number();
            }
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) {
                throw err("非法字面量，期望 " + w);
            }
            i += w.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw err("对象 key 必须是字符串");
                }
                String k = string();
                ws();
                if (peek() != ':') {
                    throw err("期望 :");
                }
                i++;
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw err("期望 , 或 }");
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<Object>();
            i++; // [
            ws();
            if (peek() == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return a;
                }
                throw err("期望 , 或 ]");
            }
        }

        String string() {
            if (peek() != '"') {
                throw err("期望 \"");
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("字符串未闭合");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw err("转义未完成");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw err("\\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw err("未知转义 \\" + e);
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (st == i) {
                throw err("非法数字");
            }
            String n = s.substring(st, i);
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                try {
                    return Long.valueOf(Long.parseLong(n));
                } catch (NumberFormatException ignore) {
                    // 溢出则退回 double
                }
            }
            return Double.valueOf(Double.parseDouble(n));
        }
    }

    // ==================== 序列化 ====================

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            escape(sb, (String) o);
        } else if (o instanceof Boolean) {
            sb.append(o.toString());
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("0");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (o instanceof Number) {
            sb.append(o.toString());
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                escape(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) o) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, e);
            }
            sb.append(']');
        } else {
            escape(sb, o.toString());
        }
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== 取值助手 ====================

    public static String str(Map<String, Object> m, String k, String def) {
        Object o = m == null ? null : m.get(k);
        return o == null ? def : String.valueOf(o);
    }

    public static long lng(Map<String, Object> m, String k, long def) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException ignore) {
                return def;
            }
        }
        return def;
    }

    public static int integer(Map<String, Object> m, String k, int def) {
        return (int) lng(m, k, def);
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Boolean) {
            return ((Boolean) o).booleanValue();
        }
        if (o instanceof String) {
            return Boolean.parseBoolean((String) o);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
