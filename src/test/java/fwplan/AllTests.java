package fwplan;

public final class AllTests {

    public static void main(String[] args) {
        int failures = 0;
        Class<?>[] classes = {
                VersionTest.class,
                PlannerTest.class,
                SimulatorTest.class,
                CryptoTest.class,
                StoreTest.class,
                EndToEndTest.class,
        };
        for (Class<?> cls : classes) {
            try {
                var run = cls.getMethod("run");
                run.invoke(null);
                System.out.println("PASS " + cls.getSimpleName());
            } catch (java.lang.reflect.InvocationTargetException e) {
                failures++;
                e.getCause().printStackTrace();System.out.println("FAIL " + cls.getSimpleName() + ": " + e.getCause());
            } catch (Throwable t) {
                failures++;
                System.out.println("FAIL " + cls.getSimpleName() + ": " + t);
            }
        }
        if (failures > 0) {
            System.out.println(failures + " 个测试类失败");
            System.exit(1);
        }
        System.out.println("全部测试通过");
    }
}
