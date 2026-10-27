package com.supplymind.agent.narrative;

import com.supplymind.agent.evidence.EvidencePackV1;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinalCloudBoundaryReplayTest {
    final NativeProseReplayTest base=new NativeProseReplayTest();
    FinalCloudBoundaryReplayTest() throws Exception {}
    @Test void actualDatedTrendPointsKeepTheirIndividualCitationBindings() {
        String text="MAT.AZ91D.SIMULATION在2026-10-13至2026-10-22期间呈上升趋势（UPWARD），起始日2026-10-13值为23000.00元/吨，次日2026-10-14为23150.00元/吨，终点日2026-10-22为25500.00元/吨，累计变化+2500.00元/吨。全部样本均来自模拟演示（非真实市场数据）。";
        var ids=List.of("fact-20","fact-21","fact-0","fact-1","fact-19");
        assertEquals("FULL",check("TREND",text,ids,base.pack).adoption());
        assertEquals("NONE",check("TREND",text.replace("次日2026-10-14为23150.00","次日2026-10-14为23000.00"),ids,base.pack).adoption());
    }
    @Test void contrastingExistingGroupsDoesNotAssignTheOtherGroupsStatusToThisValue() {
        String text="MAT.AZ91D.SIMULATION在2026-10-01~2026-10-31月度周期的另一版本均值为23200.00元/吨，校验状态为VERIFIED_WITH_NOTICE，数据来源为模拟演示（非真实市场数据）。该值对应不同配置版本，与VERIFIED组独立统计，不合并计算。";
        assertEquals("FULL",check("METRICS",text,List.of("fact-9"),base.pack).adoption());
        assertEquals("NONE",check("METRICS",text.replace("23200.00","24800.00"),List.of("fact-9"),base.pack).adoption());
    }
    @Test void completeCurrentWarningMayCiteAllNineFieldsWithVerifiedItemUnitMetadata() {
        // Constructed complete citation list; the real diagnostic explicitly marked its old eight-id cap as truncated.
        String text="比较日期2026-10-15至2026-10-22，当前值25500.00元/吨，基准值23250.00元/吨，阈值5%，风险HIGH，预警编号2e973b219109dd627e241d9973c9ffe1，规则warning-relative-change-v1-custom-r4，已确认，确认时间2026-10-25T17:06:15.339511200+08:00；确认仅表示已知悉，不代表风险消失。";
        var ids=base.pack.facts().stream().filter(f->f.factType().startsWith("warning.")).map(EvidencePackV1.Fact::factId).toList();
        assertEquals(9,ids.size());
        var result=check("WARNING",text,ids,base.pack);
        assertEquals("FULL",result.adoption(),result.toString());
        String combined=text.replace("当前值25500.00元/吨，基准值23250.00元/吨，阈值5%","当前值25500.00元/吨较基准值23250.00元/吨超出5%阈值");
        assertEquals("FULL",check("WARNING",combined,ids,base.pack).adoption());
        assertEquals("NONE",check("WARNING",combined.replace("当前值25500.00","当前值23250.00"),ids,base.pack).adoption());
        assertEquals("NONE",check("WARNING",text.replace("元/吨","美元/吨"),ids,base.pack).adoption());
        assertEquals("NONE",check("WARNING",text.replace("5%","9.68%"),ids,base.pack).adoption());
    }
    private NarrativeResult check(String kind,String text,List<String> ids,EvidencePackV1 pack) {
        return base.validator.validate(com.supplymind.foundation.codec.JsonV1Codec.encodeCompact(
            Map.of("sections",List.of(Map.of("kind",kind,"text",text,"factIds",ids)),"recommendations",List.of())),pack);
    }
}
