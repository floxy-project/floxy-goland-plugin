package com.floxy.viewer.validate

import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValidatorTest {

    @Test
    fun `fork with two branches should not report missing branches`() {
        val code = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("parallel-test", 1).
                Step("start", "init-service").
                Fork("parallel-work",
                    func(branch *floxy.Builder) { 
                        branch.Step("task-a", "service-a") 
                    },
                    func(branch *floxy.Builder) { 
                        branch.Step("task-b", "service-b") 
                    },
                ).
                JoinStep("merge", []string{"task-a", "task-b"}, floxy.JoinStrategyAll).
                Step("end", "final-service").
                Build()
            }
        """.trimIndent()

        val analyzer = VisitorBasedFlowAnalyzer()
        val flows = analyzer.collectFlowsFromText(code)
        assertEquals(1, flows.size)
        val model = flows[0]

        val validator = Validator()
        val issues = validator.validate(model)

        val noBranches = issues.any { it.message.contains("Fork 'parallel-work' has no branches") }
        assertFalse(noBranches, "Validator incorrectly reports missing branches for a valid fork")
    }

    @Test
    fun `join with a single incoming branch should produce a warning`() {
        val code = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("single-join", 1).
                Step("start", "init").
                Fork("one-branch",
                    func(branch *floxy.Builder) {
                        branch.Step("only", "svc")
                    }
                ).
                JoinStep("merge", []string{"only"}, floxy.JoinStrategyAll).
                Build()
            }
        """.trimIndent()

        val analyzer = VisitorBasedFlowAnalyzer()
        val flows = analyzer.collectFlowsFromText(code)
        assertEquals(1, flows.size)
        val model = flows[0]

        val validator = Validator()
        val issues = validator.validate(model)

        assertTrue(
            issues.any { it.severity == "WARN" && it.message.contains("Join 'merge' has only one incoming branch") },
            "Expected a WARN about join with only one incoming branch"
        )
    }

    @Test
    fun `multiple onFailure from the same step should be an error`() {
        val code = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("failures", 1).
                Step("do", "handler").
                OnFailure("comp1", "h1").
                OnFailure("comp2", "h2").
                Build()
            }
        """.trimIndent()

        val analyzer = VisitorBasedFlowAnalyzer()
        val flows = analyzer.collectFlowsFromText(code)
        assertEquals(1, flows.size)
        val model = flows[0]

        val issues = Validator().validate(model)
        assertTrue(
            issues.any { it.severity == "ERROR" && it.message.contains("multiple onFailure targets") },
            "Expected ERROR about multiple onFailure targets"
        )
    }
}
