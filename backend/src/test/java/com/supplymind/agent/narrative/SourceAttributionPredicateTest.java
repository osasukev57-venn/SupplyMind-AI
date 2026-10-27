package com.supplymind.agent.narrative;

import com.supplymind.foundation.codec.JsonV1Codec;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SourceAttributionPredicateTest {
    @Test void actualBoundedCloudRejectionReplaysAsAValidSourceNotANewAlias() throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/agent-v3-cloud/source-attribution-predicate-replay.json")) {
            var event=JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
            var fixture=new SourceAssertionRegressionTest();
            assertEquals("SOURCE_CONFLICT",event.path("reason").asText());
            assertEquals("标注为"+event.path("expected").asText(),event.path("asserted").asText());
            assertEquals("FULL",fixture.validate(event.path("marker").asText()+event.path("asserted").asText()+"。",fixture.pack).adoption());
        }
    }
    @ParameterizedTest @ValueSource(strings={"标注为","标注 为","标明是","注明为","标记为","记录为","显示为","记载为"})
    void attributionPredicatesDoNotBecomePartOfSourceName(String predicate) throws Exception {
        var fixture=new SourceAssertionRegressionTest();
        assertEquals("FULL",fixture.validate("数据来源"+predicate+"“模拟演示（非真实市场数据）”。",fixture.pack).adoption());
    }
    @ParameterizedTest @ValueSource(strings={
        "数据来源标注为路透社。",
        "数据来源标注不是模拟演示（非真实市场数据）。",
        "数据来源标注为模拟演示（非真实市场数据）及路透社。",
        "数据来源标注为。",
        "数据来源为标注为模拟演示（非真实市场数据）。"})
    void predicateSupportDoesNotStripArbitrarySourceWordsOrAdmitConflicts(String text) throws Exception {
        var fixture=new SourceAssertionRegressionTest();
        assertEquals("NONE",fixture.validate(text,fixture.pack).adoption());
    }
}
