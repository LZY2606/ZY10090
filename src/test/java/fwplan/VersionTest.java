package fwplan;

import fwplan.model.Version;

public final class VersionTest {

    public static void run() {
        Test.check(Version.of("1.10.0").compareTo(Version.of("1.9.0")) > 0, "数字段比较");
        Test.check(Version.of("2.0").compareTo(Version.of("2.0.0")) == 0, "缺段补 0");
        Test.check(Version.of("2.0.0").compareTo(Version.of("2.0.0-rc1")) > 0, "正式版高于预发布");
        Test.fails(() -> Version.of("1..0"), RuntimeException.class, "invalid", "非法版本拒绝");

        Version.Range open = Version.Range.parse("[1.0.0,2.0.0)");
        Test.check(open.contains(Version.of("1.0.0")), "闭下界");
        Test.check(!open.contains(Version.of("2.0.0")), "开上界");
        Test.check(open.contains(Version.of("1.5.0")), "区间内");
        Version.Range all = Version.Range.parse("*");
        Test.check(all.contains(Version.of("99.0.0")), "* 无界");
        Test.fails(() -> Version.Range.parse("[2.0,1.0)"), RuntimeException.class, "upper", "倒挂区间拒绝");
        Test.check(Version.Range.parse("[1.0,2.0)").overlaps(Version.Range.parse("(1.5,3.0]")), "区间相交");
        Test.check(!Version.Range.parse("[1.0,1.5)").overlaps(Version.Range.parse("[1.5,3.0]")), "区间不相交");
    }
}
