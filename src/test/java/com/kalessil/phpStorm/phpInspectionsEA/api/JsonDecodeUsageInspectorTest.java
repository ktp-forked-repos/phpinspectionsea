package com.kalessil.phpStorm.phpInspectionsEA.api;

import com.kalessil.phpStorm.phpInspectionsEA.PhpCodeInsightFixtureTestCase;
import com.kalessil.phpStorm.phpInspectionsEA.inspectors.apiUsage.JsonDecodeUsageInspector;

final public class JsonDecodeUsageInspectorTest extends PhpCodeInsightFixtureTestCase {
    public void testIfFindsAllPatterns() {
        final JsonDecodeUsageInspector inspector = new JsonDecodeUsageInspector();
        inspector.DECODE_AS_ARRAY                = true;
        inspector.DECODE_AS_OBJECT               = false;
        myFixture.enableInspections(inspector);
        myFixture.configureByFile("testData/fixtures/api/json-decode.php");
        myFixture.testHighlighting(true, false, true);

        myFixture.getAllQuickFixes().forEach(fix -> myFixture.launchAction(fix));
        myFixture.setTestDataPath(".");
        myFixture.checkResultByFile("testData/fixtures/api/json-decode.fixed.php");
    }
}
