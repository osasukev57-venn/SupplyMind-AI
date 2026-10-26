package com.supplymind.agent;
import com.supplymind.agent.orchestration.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class QueryPlannerTest {
 private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),ZoneOffset.UTC);
 private AgentOrchestrator.AgentQueryInput input(String q,String start,String end,String theme){return new AgentOrchestrator.AgentQueryInput(q,"MAT.AZ91D.SIMULATION",start,end,null,null,null,null,null,"FORMAL",theme);}
 @Test void defaultUsesLatestBusinessDateInsteadOfHostToday(){var plan=QueryPlanner.plan(input("综合分析",null,null,"COMPREHENSIVE"),CLOCK,()->LocalDate.parse("2026-10-22"));assertEquals("2026-10-01",plan.startDate());assertEquals("2026-10-22",plan.endDate());assertEquals("month",plan.grain());assertEquals("2026-10",plan.month());}
 @Test void explicitConflictRequiresUserCorrection(){assertThrows(IllegalArgumentException.class,()->QueryPlanner.plan(input("2026-10-13 至 2026-10-22","2026-10-01","2026-10-22",null),CLOCK,()->null));}
 @Test void synonymsUseOneSharedRequirement(){for(String word:new String[]{"月均","月均值","月度均值","月度平均"}){var in=input(word+" 2025-12-01 至 2026-02-20",null,null,null);assertTrue(QueryPlanner.requiresMonthly(in));var p=QueryPlanner.plan(in,CLOCK,()->{fail("Explicit dates must not need latest discovery");return null;});assertEquals("2025-12-01",p.periodStart());assertEquals("2026-02-28",p.periodEnd());}}
 @Test void explicitThemeOverridesKeywordAndRejectsUnsupportedTheme(){var p=QueryPlanner.plan(input("月度均值", "2026-10-13","2026-10-22","HISTORY"),CLOCK,()->null);assertNull(p.grain());assertFalse(QueryPlanner.requiresMonthly(p));assertThrows(IllegalArgumentException.class,()->QueryPlanner.plan(input("analysis",null,null,"WRITE"),CLOCK,()->null));}
 @Test void explicitHistoryDoesNotInheritOtherKeywords(){var p=QueryPlanner.plan(input("综合分析、月均和预警", "2026-10-13","2026-10-22","HISTORY"),CLOCK,()->null);assertNull(p.grain());assertNull(p.month());}
}
