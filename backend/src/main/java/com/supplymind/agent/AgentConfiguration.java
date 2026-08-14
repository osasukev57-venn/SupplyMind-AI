package com.supplymind.agent;

import com.supplymind.agent.api.AgentQueryController;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter;
import com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter;
import com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter;
import com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter;
import com.supplymind.agent.infrastructure.springai.SpringAiLlmService;
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.orchestration.ToolExecutor;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.history.HistoryQueryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * D6 Agent Spring wiring: seven read-only Tool Adapters, the Java tool executor, evidence
 * verifier, template fallback, report store and the Agent orchestrator. The LLM port is the
 * Spring AI adapter, created only when a ChatClient is configured; otherwise the port is an
 * unavailable stub and the pipeline deterministically uses the Java template fallback - the
 * application context always starts, even without an API key.
 */
@Configuration
public class AgentConfiguration {

    @Bean
    HistoryQueryService agentHistoryQueryService(DataRoot dataRoot) {
        return new HistoryQueryService(dataRoot);
    }

    @Bean
    SeriesResolveToolAdapter seriesResolveToolAdapter(ConfigManagementService configManagementService) {
        return new SeriesResolveToolAdapter(configManagementService);
    }

    @Bean
    HistoryQueryToolAdapter historyQueryToolAdapter(HistoryQueryService historyQueryService) {
        return new HistoryQueryToolAdapter(historyQueryService);
    }

    @Bean
    PeriodMetricsToolAdapter periodMetricsToolAdapter(HistoryQueryService historyQueryService) {
        return new PeriodMetricsToolAdapter(historyQueryService);
    }

    @Bean
    QualityInspectToolAdapter qualityInspectToolAdapter(HistoryQueryService historyQueryService) {
        return new QualityInspectToolAdapter(historyQueryService);
    }

    @Bean
    CostImpactToolAdapter costImpactToolAdapter(HistoryQueryService historyQueryService) {
        return new CostImpactToolAdapter(historyQueryService);
    }

    @Bean
    WarningExplainToolAdapter warningExplainToolAdapter(DataRoot dataRoot) {
        return new WarningExplainToolAdapter(dataRoot);
    }

    @Bean
    ProvenanceTraceToolAdapter provenanceTraceToolAdapter(
            DataRoot dataRoot, HistoryQueryService historyQueryService
    ) {
        return new ProvenanceTraceToolAdapter(dataRoot, historyQueryService);
    }

    @Bean
    ToolExecutor toolExecutor(
            SeriesResolveToolAdapter seriesResolveToolAdapter,
            HistoryQueryToolAdapter historyQueryToolAdapter,
            PeriodMetricsToolAdapter periodMetricsToolAdapter,
            QualityInspectToolAdapter qualityInspectToolAdapter,
            CostImpactToolAdapter costImpactToolAdapter,
            WarningExplainToolAdapter warningExplainToolAdapter,
            ProvenanceTraceToolAdapter provenanceTraceToolAdapter
    ) {
        return new ToolExecutor(seriesResolveToolAdapter, historyQueryToolAdapter,
                periodMetricsToolAdapter, qualityInspectToolAdapter, costImpactToolAdapter,
                warningExplainToolAdapter, provenanceTraceToolAdapter);
    }

    @Bean
    SupplyMindToolCallbackProvider supplyMindToolCallbackProvider(
            SeriesResolveToolAdapter seriesResolveToolAdapter,
            HistoryQueryToolAdapter historyQueryToolAdapter,
            PeriodMetricsToolAdapter periodMetricsToolAdapter,
            QualityInspectToolAdapter qualityInspectToolAdapter,
            CostImpactToolAdapter costImpactToolAdapter,
            WarningExplainToolAdapter warningExplainToolAdapter,
            ProvenanceTraceToolAdapter provenanceTraceToolAdapter
    ) {
        return new SupplyMindToolCallbackProvider(List.of(
                seriesResolveToolAdapter, historyQueryToolAdapter, periodMetricsToolAdapter,
                qualityInspectToolAdapter, costImpactToolAdapter, warningExplainToolAdapter,
                provenanceTraceToolAdapter));
    }

    @Bean
    EvidenceRefVerifier evidenceRefVerifier(DataRoot dataRoot) {
        return new EvidenceRefVerifier(dataRoot);
    }

    @Bean
    TemplateFallbackService templateFallbackService() {
        return new TemplateFallbackService();
    }

    @Bean
    ReportStore reportStore(DataRoot dataRoot, AtomicFileStore atomicFileStore) {
        return new ReportStore(dataRoot, atomicFileStore);
    }

    @Bean
    LLMService.Port agentLlmPort(
            ObjectProvider<ChatClient> chatClientProvider,
            SupplyMindToolCallbackProvider supplyMindToolCallbackProvider,
            @Value("${supplymind.agent.llm.provider:}") String provider,
            @Value("${supplymind.agent.llm.model:}") String model
    ) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return new LLMService.Port() {
                @Override
                public LLMService.LLMResponse analyze(LLMService.LLMRequest request) {
                    return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "not_configured");
                }
            };
        }
        return new SpringAiLlmService(chatClient, supplyMindToolCallbackProvider, provider, model);
    }

    @Bean
    com.supplymind.agent.application.AgentResponseVerifier agentResponseVerifier(
            @Value("${supplymind.agent.llm.api-key:}") String apiKey
    ) {
        return new com.supplymind.agent.application.AgentResponseVerifier(
                apiKey == null || apiKey.isBlank() ? java.util.List.of() : java.util.List.of(apiKey));
    }

    @Bean
    AgentOrchestrator agentOrchestrator(
            ToolExecutor toolExecutor,
            LLMService.Port agentLlmPort,
            TemplateFallbackService templateFallbackService,
            EvidenceRefVerifier evidenceRefVerifier,
            ReportStore reportStore,
            com.supplymind.agent.application.AgentResponseVerifier agentResponseVerifier
    ) {
        return new AgentOrchestrator(toolExecutor, agentLlmPort, templateFallbackService,
                evidenceRefVerifier, reportStore, agentResponseVerifier);
    }

    @Bean
    AgentQueryController agentQueryController(AgentOrchestrator agentOrchestrator) {
        return new AgentQueryController(agentOrchestrator);
    }
}
