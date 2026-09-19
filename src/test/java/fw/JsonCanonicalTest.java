package fw;

import fw.json.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonCanonicalTest {

    @Test
    void canonicalSortsKeys() {
        Map<String, Object> a = Json.parseObject("{\"b\":1,\"a\":{\"z\":1,\"y\":2}}");
        Map<String, Object> b = Json.parseObject("{\"a\":{\"y\":2,\"z\":1},\"b\":1}");
        assertEquals(Json.canonicalString(b), Json.canonicalString(a));
    }

    @Test
    void roundTripsNumbersAndStrings() {
        assertEquals(42, ((Map<?, ?>) Json.parseObject("{\"n\":42}")).get("n"));
        assertEquals("x", ((Map<?, ?>) Json.parseObject("{\"s\":\"x\"}")).get("s"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("{bad}"));
    }
}
