package com.supplymind.scheduling;

import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.storage.ConfigActivationStore;
import java.util.ArrayList;
import java.util.List;

/** One current cycle for the enabled automatic targets; no frozen USD/EUR dependency. */
public final class ActiveCurrentCollectionService {
    public record Result(String state, String businessDate, String message) {}
    private final ConfigActivationStore configs;
    private final BackfillOrchestrator intake;
    public ActiveCurrentCollectionService(ConfigActivationStore configs, BackfillOrchestrator intake) {
        this.configs = configs;
        this.intake = intake;
    }
    public Result runImmediateCycle() {
        var config = configs.readActiveConfig();
        if (config.mode() != Mode.FORMAL) {
            return new Result("IDLE", null, "当前配置不是正式采集模式");
        }
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (var item : config.items()) {
            if (!item.enabled() || !item.providerType().isExternalHttpProvider()) continue;
            try {
                var result = intake.collectCurrent(item.itemId());
                if (result.status() == BackfillOrchestrator.CurrentIntakeStatus.SUCCEEDED) {
                    succeeded.add(item.itemId());
                } else {
                    failed.add(item.itemId());
                }
            } catch (RuntimeException exception) {
                // Do not expose transport details or prevent independent targets from running.
                failed.add(item.itemId());
            }
        }
        if (succeeded.isEmpty() && failed.isEmpty()) {
            return new Result("IDLE", null, "没有启用的自动来源；人工或导入数据请在来源页处理");
        }
        String state = failed.isEmpty() ? "SUCCEEDED" : succeeded.isEmpty() ? "FAILED" : "PARTIAL_SUCCESS";
        return new Result(state, null, "已完成：" + String.join("、", succeeded)
                + (failed.isEmpty() ? "" : "；未完成：" + String.join("、", failed))
                + "。各项业务日期与实际来源见面板。");
    }
}
