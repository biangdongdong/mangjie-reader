package javax.lang.model;

/**
 * Android 上没有 javax.lang.model（那是 java.compiler 模块的东西），
 * 而 libs/rhino.jar 里的 org.mozilla.javascript.JavaMembers 在**静态初始化**里就要用：
 *
 *   STRICT_REFLECTIVE_ACCESS = SourceVersion.latestSupported().ordinal() > 8;
 *
 * 类加载时找不到这个类 → NoClassDefFoundError: org.mozilla.javascript.JavaMembers
 * → 所有 JS 访问 Java 对象的操作（source.getVariable、java.log、Packages.javax.crypto…）全废。
 * 这就是日志里那条「JS 失败：org.mozilla.javascript.JavaMembers」的真因。
 *
 * 这里补一个最小实现：只声明到 RELEASE_8，latestSupported() 返回它，ordinal()=8，
 * 于是 STRICT_REFLECTIVE_ACCESS=false，Rhino 按 Java 8 的宽松反射走（Android 上正合适）。
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4,
    RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8;

    public static SourceVersion latest() {
        return RELEASE_8;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }

    public static boolean isIdentifier(CharSequence name) {
        String s = name == null ? "" : name.toString();
        if (s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        String s = name == null ? "" : name.toString();
        if (s.isEmpty()) return false;
        for (String part : s.split("\\.", -1)) {
            if (!isIdentifier(part)) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        String v = s == null ? "" : s.toString();
        switch (v) {
            case "abstract": case "assert": case "boolean": case "break": case "byte":
            case "case": case "catch": case "char": case "class": case "const":
            case "continue": case "default": case "do": case "double": case "else":
            case "enum": case "extends": case "final": case "finally": case "float":
            case "for": case "goto": case "if": case "implements": case "import":
            case "instanceof": case "int": case "interface": case "long": case "native":
            case "new": case "package": case "private": case "protected": case "public":
            case "return": case "short": case "static": case "strictfp": case "super":
            case "switch": case "synchronized": case "this": case "throw": case "throws":
            case "transient": case "try": case "void": case "volatile": case "while":
            case "true": case "false": case "null": case "_":
                return true;
            default:
                return false;
        }
    }

    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        return isKeyword(s);
    }
}
