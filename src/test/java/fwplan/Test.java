package fwplan;

/** 极简断言工具（无外部依赖）。 */
public final class Test {

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void eq(Object actual, Object expected, String message) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(message + " — 期望: <" + expected + "> 实际: <" + actual + ">");
        }
    }

    public static void fails(Runnable runnable, Class<? extends Throwable> type, String contains, String message) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                throw new AssertionError(message + " — 异常类型错误: " + t.getClass() + " " + t.getMessage(), t);
            }
            if (contains != null && !String.valueOf(t.getMessage()).contains(contains)) {
                throw new AssertionError(message + " — 异常信息缺少 <" + contains + ">: " + t.getMessage(), t);
            }
            return;
        }
        throw new AssertionError(message + " — 期望抛出 " + type.getSimpleName() + " 但未抛出");
    }
}
