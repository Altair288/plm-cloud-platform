package com.plm.attribute.version.util;

import com.plm.common.version.util.AttributeLovImportUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AttributeLovImportUtilsTest {

    @Test
    void testSlugBasic() {
        String slugCn = AttributeLovImportUtils.slug("颜色");
        Assertions.assertTrue(slugCn.equals("颜色") || slugCn.startsWith("attr_")); // 允许保留中文或 fallback
        Assertions.assertEquals("material-thickness", AttributeLovImportUtils.slug("Material Thickness"));
    }

    @Test
    void testSlugFallback() {
        String s = AttributeLovImportUtils.slug("!!!@@@");
        Assertions.assertTrue(s.startsWith("attr_"));
    }

    @Test
    void testGenerateLovKeyDeterministic() {
        String k1 = AttributeLovImportUtils.generateLovKey("CAT001", "颜色");
        String k2 = AttributeLovImportUtils.generateLovKey("CAT001", "颜色");
        Assertions.assertEquals(k1, k2);
    }

    @Test
    void testNumericParse() {
        Assertions.assertNotNull(AttributeLovImportUtils.parseNumeric("12.50"));
        Assertions.assertNull(AttributeLovImportUtils.parseNumeric("ABC"));
    }

    @Test
    void testJsonHashStability() {
        String h1 = AttributeLovImportUtils.jsonHash("{ \n \"a\":1 } ");
        String h2 = AttributeLovImportUtils.jsonHash("{\"a\":1}");
        Assertions.assertEquals(h1, h2);
    }
}
