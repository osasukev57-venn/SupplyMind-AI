package com.supplymind.agent.narrative;

import com.supplymind.foundation.codec.JsonV1Codec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdditionalCloudReplayTest {
    final NativeProseReplayTest base=new NativeProseReplayTest();
    AdditionalCloudReplayTest() throws Exception {}
    @Test void publishedSimulationIsADescriptionOfExplicitSimulationOriginNotACommercialAlias() {
        assertEquals("FULL",check("TREND","趋势上升，数据来自已发布的模拟数据。",List.of("fact-20")).adoption());
        assertEquals("NONE",check("TREND","趋势上升，数据来自已发布的上海有色网。",List.of("fact-20")).adoption());
        assertEquals("NONE",check("TREND","趋势上升，数据来自模拟数据，不是非真实市场数据。",List.of("fact-20")).adoption());
        assertEquals("NONE",check("TREND","趋势上升，数据来源不是模拟数据。",List.of("fact-20")).adoption());
    }
    @Test void periodFrameIsNotMisclassifiedAsCurrentValueBusinessDate() {
        String text="MAT.AZ91D.SIMULATION在2026-10-15~2026-10-22监测区间触发高风险预警：当前值25500.00元/吨，基准值23250.00元/吨，相对变化超阈值5%，风险等级判定为HIGH。预警标识符为2e973b219109dd627e241d9973c9ffe1，规则版本为warning-relative-change-v1-custom-r4。该预警已于2026-10-25T17:06:15.339511200+08:00被人工确认，备注说明‘人工验收：模拟数据已核对；确认仅表示已知悉，不代表风险消失’。";
        var ids=base.pack.facts().stream().filter(f->f.factType().startsWith("warning.")).map(f->f.factId()).toList();
        var result=check("WARNING",text,ids);
        assertEquals("FULL",result.adoption(),result.toString());
        assertEquals("NONE",check("WARNING",text.replace("2026-10-15~","2026-10-12~"),ids).adoption());
    }
    @Test void jointCountsAreVerifiedForBothStatusesAndNegativeUniversalClaimIsTrueOnlyForMixedSamples() {
        String text="2026-10-13至2026-10-22共应发布8个观测值，实际发布8个，缺失0个，完整行数8个，完整性达100%。该完整性指标由Java基于已核验证据计算得出，仅反映指标证据层面的完备性；底层样本中VERIFIED与VERIFIED_WITH_NOTICE各占4个，未全部为VERIFIED。";
        var ids=base.pack.facts().stream().filter(f->f.factType().startsWith("quality.")).map(f->f.factId()).toList();
        var result=check("QUALITY",text,ids);
        assertEquals("FULL",result.adoption(),result.toString());
        assertEquals("NONE",check("QUALITY",text.replace("各占4个","各占3个"),ids).adoption());
        assertEquals("NONE",check("QUALITY",text.replace("各占4个","各占4个且完整率999%"),ids).adoption());
    }
    private NarrativeResult check(String kind,String text,List<String> ids) {
        return base.validator.validate(JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind",kind,"text",text,"factIds",ids)),"recommendations",List.of())),base.pack);
    }
}
