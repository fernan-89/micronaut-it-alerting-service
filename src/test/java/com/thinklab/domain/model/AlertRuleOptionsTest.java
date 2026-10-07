package com.thinklab.domain.model;

import com.thinklab.domain.model.AlertRule.Options;
import com.thinklab.domain.model.AlertRule.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertRuleOptionsTest {

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private AlertRule rule(Options options) {
        return AlertRule.createNew(UUID.randomUUID(), org, "n", null, Severity.LOW, Severity.LOW, requester, options, "op");
    }

    private void rejects(Options options) {
        assertThrows(IllegalArgumentException.class, () -> rule(options));
    }

    @Test
    @DisplayName("options are kept, replaced on update, and a request that leaves the reopen time out gets the default")
    void kept() {
        Options valid = new Options("THINKLAB_ALERT_HOOK_OPS", "THINKLAB_ALERT_HOOK_ONCALL", 15, 45);
        AlertRule rule = rule(valid);

        assertEquals(valid, rule.getOptions());
        assertEquals(new Options(null, null, null, 30), Options.of(null, null, null, null));
        assertEquals(new Options("THINKLAB_ALERT_HOOK_A", null, null, 0), Options.of("THINKLAB_ALERT_HOOK_A", null, null, 0));
        rule.update("n", null, Severity.LOW, Severity.LOW, requester, Options.NONE, "op");
        assertEquals(Options.NONE, rule.getOptions());
    }

    @Test
    @DisplayName("a target is the NAME of a THINKLAB_ALERT_HOOK_ variable, never an address or any other variable")
    void targets() {
        rejects(new Options("MONGODB_URI", null, null, 0));
        rejects(new Options("https://hooks.example.com/x", null, null, 0));
        rejects(new Options("THINKLAB_ALERT_HOOK_", null, null, 0));
        rejects(new Options(null, "hooks", 5, 0));
        rule(new Options("THINKLAB_ALERT_HOOK_A_1", null, null, 0));
    }

    @Test
    @DisplayName("an escalation needs a target and a time between 1 and 1440 minutes, and a time needs a target")
    void escalation() {
        rejects(new Options(null, null, 5, 0));
        rejects(new Options(null, "THINKLAB_ALERT_HOOK_B", null, 0));
        rejects(new Options(null, "THINKLAB_ALERT_HOOK_B", 0, 0));
        rejects(new Options(null, "THINKLAB_ALERT_HOOK_B", 1441, 0));
        rule(new Options(null, "THINKLAB_ALERT_HOOK_B", 1, 0));
        rule(new Options(null, "THINKLAB_ALERT_HOOK_B", 1440, 0));
    }

    @Test
    @DisplayName("the reopen time is between 0 (never) and 1440 minutes; options are mandatory")
    void reopenTime() {
        rejects(new Options(null, null, null, -1));
        rejects(new Options(null, null, null, 1441));
        rejects(null);
        rule(new Options(null, null, null, 0));
        rule(new Options(null, null, null, 1440));
    }
}
