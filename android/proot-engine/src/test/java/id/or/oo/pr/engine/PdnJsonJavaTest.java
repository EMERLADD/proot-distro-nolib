package id.or.oo.pr.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdnJsonJavaTest {
    @Test public void validGrammarIncludesEscapesNumbersAndNestedContainers() {
        for (String value : new String[]{"{}", "[]", " \t\r\n{\"a\":[true,false,null,-1.25e+2,0,1E-2,{\"unicode\":\"中文😀\\u0041\\uABCD\\\"\\\\\\/\\b\\f\\n\\r\\t\"}]} ", "0", "-0", "1e2"}) PdnJson.validate(value);
    }
    @Test public void invalidGrammarCannotPassPermissiveJsonTokener() {
        for (String value : new String[]{"", "{version:1}", "{'a':1}", "{\"a\":1,}", "[1,]", "[1,,2]", "{\"a\"=1}", "{} trailing", "undefined", "True", "nul", "+1", "01", "-", ".1", "1.", "1e", "1e+", "\"\\x\"", "\"\\u00xz\"", "\"\\u00\"", "\"raw\nnewline\"", "\"unterminated", "\"\\", "\u000b0"}) {
            assertThrows(value, IllegalArgumentException.class, () -> PdnJson.validate(value));
        }
    }
    @Test public void deeplyNestedMetadataHasBoundedFailure() {
        String nested = "[".repeat(66) + "0" + "]".repeat(66);
        assertThrows(IllegalArgumentException.class, () -> PdnJson.validate(nested));
    }
}
