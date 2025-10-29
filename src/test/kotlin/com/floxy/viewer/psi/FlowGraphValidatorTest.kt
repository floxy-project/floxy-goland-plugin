package com.floxy.viewer.psi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FlowGraphValidatorTest {

    @Test
    fun `validator should not report errors for valid microservices example`() {
        val code = """
            workflowDef, err := floxy.NewBuilder("microservices-orchestration", 1).
                Step("validate-user", "user-service", floxy.WithStepMaxRetries(3)).
                OnFailure("compensate-user-validation", "compensation",
                    floxy.WithStepMaxRetries(1),
                    floxy.WithStepMetadata(map[string]any{
                        "action": "user_validation_failed",
                        "reason": "user_validation_error",
                    })).
                Fork("process-payment-and-inventory",
                    func(branch *floxy.Builder) {
                        branch.Step("process-payment", "payment-service", floxy.WithStepMaxRetries(3))
                    },
                    func(branch *floxy.Builder) {
                        branch.Step("check-inventory", "inventory-service", floxy.WithStepMaxRetries(2))
                    },
                ).
                JoinStep("send-notifications", []string{"process-payment", "check-inventory"}, floxy.JoinStrategyAll).
                Fork("track-analytics",
                    func(branch *floxy.Builder) {
                        branch.Step("track-event", "analytics-service", floxy.WithStepMaxRetries(1))
                    },
                    func(branch *floxy.Builder) {
                        branch.Step("audit-action", "audit-service", floxy.WithStepMaxRetries(1))
                    },
                ).
                JoinStep("finalize-order", []string{"track-event", "audit-action"}, floxy.JoinStrategyAll).
                Build()
        """.trimIndent()

        val analyzer = VisitorBasedFlowAnalyzer()
        val results = analyzer.analyzeFlowsDetailed(code)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals("microservices-orchestration", r.model.name)
        assertTrue(r.errors.isEmpty(), "Expected no validation errors, got: ${'$'}{r.errors}")
    }
}
