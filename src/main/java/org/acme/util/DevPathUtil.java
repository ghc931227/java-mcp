package org.acme.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Path resolution for dev tools.
 * Dev tools operate ONLY on source files under the project's src/ directory:
 *  - paths are relative to src/ (e.g. "main/java/org/acme/Foo.java")
 *  - absolute paths, environment variable expansion and traversal are rejected
 */
public final class DevPathUtil {

    /** Project root = process working directory (absolute, normalized) */
    public static final Path PROJECT_ROOT =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** Source root: only files under src/ can be read/written by dev tools */
    public static final Path SOURCE_ROOT = PROJECT_ROOT.resolve("src");

    private DevPathUtil() {
    }

    /**
     * Resolve a source-relative path to an absolute Path under PROJECT_ROOT/src.
     * Empty input resolves to SOURCE_ROOT.
     * 兼容两种输入："/"、"\"、"." 视为 src 根；前端树展示的项目根相对路径（"src/..."）自动剥掉前导 src/。
     */
    public static Path resolve(String userPath) {
        String cleaned = userPath == null ? "" : userPath.trim().replace('\\', '/');

        // 剥掉前导分隔符：Windows 上 Paths.get("/") 是"无盘符的根"，
        // resolve 后会变成盘符根目录（如 d:\），被误判为路径穿越
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        // 统一前缀语义：树节点路径以 src/ 开头，相对 src/ 解析时需剥离
        if (cleaned.equals("src")) {
            cleaned = "";
        } else if (cleaned.startsWith("src/")) {
            cleaned = cleaned.substring(4);
        }
        if (cleaned.isEmpty() || cleaned.equals(".")) {
            return SOURCE_ROOT;
        }

        Path raw = Paths.get(cleaned);
        if (raw.isAbsolute()) {
            throw new SecurityException("Absolute paths are not allowed, use a path relative to src/: " + userPath);
        }

        Path p = SOURCE_ROOT.resolve(raw).normalize();
        // 防路径穿越：解析结果必须仍在 src/ 源码区内
        if (!p.startsWith(SOURCE_ROOT)) {
            throw new SecurityException("Path traversal is not allowed: " + userPath);
        }
        return p;
    }
}
